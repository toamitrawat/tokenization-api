package com.example.tokenization.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "tokenization")
@Validated
public record TokenizationProperties(
    @NotBlank String hmacKeyBase64,
    @Positive int maxCollisionRetries,
    @Valid Kms kms
) {
    public record Kms(@Valid Cache cache) {
        public record Cache(
            @Positive int maxSize,
            @Positive int ttlSeconds
        ) {}
    }
}
