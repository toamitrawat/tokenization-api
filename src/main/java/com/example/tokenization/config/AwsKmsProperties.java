package com.example.tokenization.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "aws")
@Validated
public record AwsKmsProperties(
    @NotBlank String region,
    String profile,
    @Valid Kms kms
) {
    public record Kms(
        @NotBlank String keyId,
        @Positive int apiTimeoutMs,
        @Positive int connectTimeoutMs
    ) {}
}
