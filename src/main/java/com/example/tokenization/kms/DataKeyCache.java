package com.example.tokenization.kms;

import com.example.tokenization.config.TokenizationProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DataKeyCache {

    private final Cache<String, byte[]> cache;

    public DataKeyCache(TokenizationProperties props) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.kms().cache().maxSize())
                .expireAfterWrite(Duration.ofSeconds(props.kms().cache().ttlSeconds()))
                .build();
    }

    public byte[] get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, byte[] value) {
        cache.put(key, value);
    }
}
