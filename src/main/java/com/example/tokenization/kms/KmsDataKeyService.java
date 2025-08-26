package com.example.tokenization.kms;

import com.example.tokenization.config.AwsKmsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import java.util.Arrays;
import java.util.Base64;

/**
 * Service for managing AWS KMS data key operations with caching.
 * 
 * This service encapsulates:
 * - Generation of new data keys via KMS
 * - Decryption of encrypted data keys with caching to reduce KMS calls
 * - Proper cache key management using Base64-encoded encrypted data keys
 * 
 * Security considerations:
 * - Plaintext data keys are zeroed after use
 * - Cache has bounded size and TTL to limit exposure
 * - Only encrypted data keys are used as cache keys
 */
@Service
@Slf4j
public class KmsDataKeyService {

    @Autowired
    private KmsClient kmsClient;

    @Autowired
    private AwsKmsConfig awsKmsConfig;

    @Autowired
    private DataKeyCache dataKeyCache;

    /**
     * Generates a new AES-256 data key using the configured KMS key.
     * 
     * @return GenerateDataKeyResponse containing both plaintext and encrypted data key
     */
    public GenerateDataKeyResponse generateDataKey() {
        log.debug("Generating new data key from KMS");
        
        return kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(awsKmsConfig.getKeyId())
                .keySpec("AES_256")
                .build());
    }

    /**
     * Decrypts an encrypted data key, using cache to avoid repeated KMS calls.
     * 
     * @param encryptedDataKey the encrypted data key bytes
     * @return plaintext data key bytes (caller should zero after use)
     */
    public byte[] decryptDataKey(byte[] encryptedDataKey) {
        String cacheKey = Base64.getEncoder().encodeToString(encryptedDataKey);
        
        // Try cache first
        byte[] cachedKey = dataKeyCache.get(cacheKey);
        if (cachedKey != null) {
            log.debug("Data key cache hit for key: {}", cacheKey.substring(0, Math.min(10, cacheKey.length())) + "...");
            // Return a copy to prevent cache pollution if caller zeros the array
            return Arrays.copyOf(cachedKey, cachedKey.length);
        }

        // Cache miss - decrypt via KMS
        log.debug("Data key cache miss, decrypting via KMS for key: {}", cacheKey.substring(0, Math.min(10, cacheKey.length())) + "...");
        
        byte[] plainDataKey = kmsClient.decrypt(DecryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(encryptedDataKey))
                .build()).plaintext().asByteArray();

        // Cache the result (store a copy to prevent cache pollution)
        dataKeyCache.put(cacheKey, Arrays.copyOf(plainDataKey, plainDataKey.length));
        
        return plainDataKey;
    }

    /**
     * Convenience method to get both encrypted and plaintext data key from generation response.
     */
    public DataKeyPair generateAndExtractDataKey() {
        GenerateDataKeyResponse response = generateDataKey();
        
        byte[] plainDataKey = response.plaintext().asByteArray();
        byte[] encryptedDataKey = response.ciphertextBlob().asByteArray();
        
        return new DataKeyPair(plainDataKey, encryptedDataKey);
    }

    /**
     * Simple data class to hold both forms of a data key.
     */
    public static class DataKeyPair {
        private final byte[] plainDataKey;
        private final byte[] encryptedDataKey;

        public DataKeyPair(byte[] plainDataKey, byte[] encryptedDataKey) {
            this.plainDataKey = plainDataKey;
            this.encryptedDataKey = encryptedDataKey;
        }

        public byte[] getPlainDataKey() {
            return plainDataKey;
        }

        public byte[] getEncryptedDataKey() {
            return encryptedDataKey;
        }

        /**
         * Zeros the plaintext data key for security.
         */
        public void clearPlainDataKey() {
            if (plainDataKey != null) {
                Arrays.fill(plainDataKey, (byte) 0);
            }
        }
    }
}
