package com.example.tokenization.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class AwsKmsConfig {

    private final AwsKmsProperties props;

    @Bean
    public KmsClient kmsClient() {
        KmsClientBuilder builder = KmsClient.builder()
                .region(Region.of(props.region()))
                .overrideConfiguration(cfg -> cfg
                        .apiCallTimeout(Duration.ofMillis(props.kms().apiTimeoutMs()))
                        .apiCallAttemptTimeout(Duration.ofMillis(props.kms().connectTimeoutMs()))
                        .retryPolicy(RetryPolicy.builder().numRetries(3).build()));

        if (props.profile() != null && !props.profile().isBlank()) {
            builder = builder.credentialsProvider(
                    ProfileCredentialsProvider.builder()
                            .profileName(props.profile())
                            .build()
            );
        }
        return builder.build();
    }
}
