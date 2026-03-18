package com.example.tokenization.service;

import com.example.tokenization.config.TokenizationProperties;
import com.example.tokenization.entity.CardToken;
import com.example.tokenization.repository.CardTokenRepository;
import com.example.tokenization.exception.TokenNotFoundException;
import com.example.tokenization.exception.TokenizationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.example.tokenization.crypto.TokenDerivationService;
import com.example.tokenization.kms.KmsDataKeyService;

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

    private static final int IV_SIZE = 12;
    private static final int GCM_TAG_BITS = 128;

    private final KmsDataKeyService kmsDataKeyService;
    private final CardTokenRepository repository;
    private final TokenDerivationService tokenDerivationService;
    private final SecureRandom secureRandom;
    private final int maxCollisionRetries;

    public TokenizationService(KmsDataKeyService kmsDataKeyService,
                               CardTokenRepository repository,
                               TokenDerivationService tokenDerivationService,
                               TokenizationProperties props) {
        this.kmsDataKeyService = kmsDataKeyService;
        this.repository = repository;
        this.tokenDerivationService = tokenDerivationService;
        this.secureRandom = new SecureRandom();
        this.maxCollisionRetries = props.maxCollisionRetries();
    }

    @Transactional
    public String tokenize(String pan) {
        try {
            String last4 = last4(pan);
            log.info("Tokenize request received for CC ending {}", last4);

            String panHash = tokenDerivationService.computePanHash(pan);
            Optional<CardToken> existingByHash = repository.findByPanHash(panHash);
            if (existingByHash.isPresent()) {
                log.info("Returning existing token for CC ending {}", last4);
                return existingByHash.get().getToken();
            }

            KmsDataKeyService.DataKeyPair dataKeyPair = kmsDataKeyService.generateAndExtractDataKey();
            byte[] plainDataKey = dataKeyPair.getPlainDataKey();
            byte[] encryptedDataKey = dataKeyPair.getEncryptedDataKey();

            try {
                byte[] iv = new byte[IV_SIZE];
                secureRandom.nextBytes(iv);

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(plainDataKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] ciphertext = cipher.doFinal(pan.getBytes(StandardCharsets.UTF_8));

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
                        if (counter++ >= maxCollisionRetries) {
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
                log.info("Token created successfully for CC ending {}", last4);
                return token;
            } finally {
                dataKeyPair.clearPlainDataKey();
            }
        } catch (TokenizationException ex) {
            throw ex;
        } catch (Exception ex) {
            String last4 = last4(pan);
            log.error("Tokenization failed for CC ending {}: {}", last4, ex.getMessage(), ex);
            throw new TokenizationException("Tokenization failed", ex);
        }
    }

    @Transactional(readOnly = true)
    public String detokenize(String token) {
        try {
            Optional<CardToken> opt = repository.findByToken(token);
            if (opt.isEmpty()) {
                log.warn("Token not found: {}", token);
                throw new TokenNotFoundException("Token not found");
            }
            CardToken ct = opt.get();

            byte[] encryptedDataKey = ct.getEncryptedDataKey();
            byte[] plainDataKey = kmsDataKeyService.decryptDataKey(encryptedDataKey);

            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(plainDataKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, ct.getNonce()));
                byte[] plaintext = cipher.doFinal(ct.getEncryptedPan());
                String pan = new String(plaintext, StandardCharsets.UTF_8);
                log.info("Detokenization successful for token: {} (CC ending {})", token, last4(pan));
                return pan;
            } finally {
                Arrays.fill(plainDataKey, (byte) 0);
            }
        } catch (TokenNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Detokenization failed for token {}: {}", token, ex.getMessage(), ex);
            throw new TokenizationException("Detokenization failed", ex);
        }
    }

    private String last4(String cc) {
        if (cc == null || cc.length() < 4) return "****";
        return cc.substring(cc.length() - 4);
    }
}
