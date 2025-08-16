package com.example.tokenization.kms;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Short-lived cache for KMS-decrypted plaintext data keys, keyed by the encrypted data key bytes.
 * Strictly bounded in size and time to reduce KMS decrypt calls while limiting exposure.
 */
@Component
public class DataKeyCache {

    private final Cache<String, byte[]> cache;

    public DataKeyCache(
            @Value("${tokenization.kms.cache.maxSize:100}") int maxSize,
            @Value("${tokenization.kms.cache.ttlSeconds:30}") long ttlSeconds) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
    }

    public byte[] get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, byte[] value) {
        cache.put(key, value);
    }
}
