package com.example.tokenization.kms;

import com.example.tokenization.config.AwsKmsProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import java.util.Arrays;
import java.util.Base64;

@Service
@Slf4j
@RequiredArgsConstructor
public class KmsDataKeyService {

    private final KmsClient kmsClient;
    private final AwsKmsProperties awsKmsProperties;
    private final DataKeyCache dataKeyCache;

    @CircuitBreaker(name = "kms")
    @Retry(name = "kms")
    public DataKeyPair generateAndExtractDataKey() {
        log.debug("Generating new data key from KMS");

        GenerateDataKeyResponse response = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(awsKmsProperties.kms().keyId())
                .keySpec("AES_256")
                .build());

        byte[] plainDataKey = response.plaintext().asByteArray();
        byte[] encryptedDataKey = response.ciphertextBlob().asByteArray();

        return new DataKeyPair(plainDataKey, encryptedDataKey);
    }

    @CircuitBreaker(name = "kms")
    @Retry(name = "kms")
    public byte[] decryptDataKey(byte[] encryptedDataKey) {
        String cacheKey = Base64.getEncoder().encodeToString(encryptedDataKey);

        byte[] cachedKey = dataKeyCache.get(cacheKey);
        if (cachedKey != null) {
            log.debug("Data key cache hit");
            return Arrays.copyOf(cachedKey, cachedKey.length);
        }

        log.debug("Data key cache miss, decrypting via KMS");

        byte[] plainDataKey = kmsClient.decrypt(DecryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(encryptedDataKey))
                .build()).plaintext().asByteArray();

        dataKeyCache.put(cacheKey, Arrays.copyOf(plainDataKey, plainDataKey.length));

        return plainDataKey;
    }

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

        public void clearPlainDataKey() {
            if (plainDataKey != null) {
                Arrays.fill(plainDataKey, (byte) 0);
            }
        }
    }
}
