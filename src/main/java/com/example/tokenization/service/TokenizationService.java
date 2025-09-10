package com.example.tokenization.service;

import com.example.tokenization.config.AwsKmsConfig;
import com.example.tokenization.entity.CardToken;
import com.example.tokenization.repository.CardTokenRepository;
import com.example.tokenization.exception.TokenNotFoundException;
import com.example.tokenization.exception.TokenizationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.example.tokenization.crypto.TokenDerivationService;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

@Service
@Slf4j
public class TokenizationService {

    @Autowired
    private KmsClient kmsClient;

    @Autowired
    private AwsKmsConfig awsKmsConfig;

    @Autowired
    private CardTokenRepository repository;

    private static final int IV_SIZE = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_RETRY = 10;
    
    @Autowired
    private TokenDerivationService tokenDerivationService;

    /**
     * Tokenizes the provided PAN, generating or reusing a deterministic token.
     *
     * Observability:
     * - Logs are structured (JSON) and never include full PANs; only last 4 digits are emitted.
     * - Failures are logged with stack traces and wrapped in TokenizationException for consistent 500 mapping.
     * - Consider adding Micrometer @Timed or manual timers to track latency and success/error counts.
     * - If you propagate correlation IDs, populate MDC (e.g., MDC.put("traceId", ...)) in a web filter.
     */
    @Transactional
    public String tokenize(String pan) {
        try {
            String last4 = last4(pan);
            log.info("Tokenize request received for CC ending {}", last4);
            // Deterministic lookup by HMAC of PAN
            String panHash = tokenDerivationService.computePanHash(pan);
            Optional<CardToken> existingByHash = repository.findByPanHash(panHash);
            if (existingByHash.isPresent()) {
                log.info("Returning existing token for CC ending {}", last4);
                return existingByHash.get().getToken();
            }

            // KMS interaction is a key external dependency; consider timing and tagging these calls for metrics.
            GenerateDataKeyResponse dataKeyResponse = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                    .keyId(awsKmsConfig.getKeyId())
                    .keySpec("AES_256")
                    .build());

            byte[] plainDataKey = dataKeyResponse.plaintext().asByteArray();
            byte[] encryptedDataKey = dataKeyResponse.ciphertextBlob().asByteArray();

            try {
                int attempts = 0;
                while (attempts < MAX_RETRY) {
                    attempts++;

                    byte[] iv = new byte[IV_SIZE];
                    new SecureRandom().nextBytes(iv);

                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(plainDataKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
                    byte[] ciphertext = cipher.doFinal(pan.getBytes(StandardCharsets.UTF_8));

                    // Deterministic token from panHash (with counter to resolve rare collisions)
                    int counter = 0;
                    String token;
                    while (true) {
                        token = tokenDerivationService.deriveTokenFromHash(panHash, counter);
                        Optional<CardToken> collision = repository.findByToken(token);
                        if (collision.isPresent()) {
                            if (panHash.equals(collision.get().getPanHash())) {
                                log.warn("Duplicate creation for same PAN hash; reusing token for CC ending {}", last4);
                                return collision.get().getToken();
                            }
                            if (counter++ >= MAX_RETRY) {
                                log.error("Too many token collisions");
                                throw new TokenizationException("Too many token collisions");
                            }
                            continue;
                        }
                        break;
                    }

                    CardToken ct = new CardToken();
                    ct.setToken(token);
                    ct.setPanHash(panHash);
                    ct.setCollisionCounter(counter);
                    ct.setEncryptedPan(ciphertext);
                    ct.setNonce(iv);
                    ct.setEncryptedDataKey(encryptedDataKey);
                    ct.setAad(null);
                    repository.save(ct);
                    // Success path: minimal, PII-safe log line.
            log.info("Token created successfully for CC ending {}", last4);
                    return token;
                }
                log.error("Too many token collisions");
                throw new TokenizationException("Too many token collisions");
            } finally {
                Arrays.fill(plainDataKey, (byte)0);
            }
        } catch (Exception ex) {
        String last4 = last4(pan);
        // Error path: include exception with stack trace; keep PAN redacted.
        log.error("Tokenization failed for CC ending {}: {}", last4, ex.getMessage(), ex);
            throw new TokenizationException("Tokenization failed", ex);
        }
    }

    public String detokenize(String token) {
        try {
            Optional<CardToken> opt = repository.findByToken(token);
            if (!opt.isPresent()) {
                log.warn("Token not found: {}", token);
                throw new TokenNotFoundException("Token not found");
            }
            CardToken ct = opt.get();

            byte[] encryptedDataKey = ct.getEncryptedDataKey();
            byte[] plainDataKey = kmsClient.decrypt(DecryptRequest.builder()
                    .ciphertextBlob(SdkBytes.fromByteArray(encryptedDataKey))
                    .build()).plaintext().asByteArray();

            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(plainDataKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, ct.getNonce()));
                byte[] plaintext = cipher.doFinal(ct.getEncryptedPan());
                String pan = new String(plaintext, StandardCharsets.UTF_8);
                log.info("Detokenization successful for token: {} (CC ending {})", token, last4(pan));
                return pan;
            } finally {
                Arrays.fill(plainDataKey, (byte)0);
            }
        } catch (TokenNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Detokenization failed for token {}: {}", token, ex.getMessage(), ex);
            throw new TokenizationException("Detokenization failed", ex);
        }
    }

    // derive and hashing moved to TokenDerivationService

    private String last4(String cc) {
        if (cc == null || cc.length() < 4) return "****";
        return cc.substring(cc.length() - 4);
    }
}
