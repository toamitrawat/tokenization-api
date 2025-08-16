package com.example.tokenization.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

/**
 * Configures the AWS KMS client.
 *
 * Properties:
 * - aws.kms.key-id: The target CMK/Key ARN used to generate data keys.
 * - aws.region: AWS region string (e.g., ap-south-1).
 * - aws.profile: Optional named profile (e.g., rolesanywhere) for credentials.
 *
 * Observability notes:
 * - You can enable AWS SDK metrics/tracing (e.g., via OpenTelemetry SDK instrumentation) to observe KMS latencies and errors.
 * - Consider configuring retry/backoff and surfacing SDK retries as metrics counters.
 */
@Configuration
public class AwsKmsConfig {

    @Value("${aws.kms.key-id}")
    private String keyId;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.profile:}")
    private String awsProfile;

    /**
     * Builds a KmsClient with optional ProfileCredentialsProvider when aws.profile is set.
     */
    @Bean
    public KmsClient kmsClient() {
        KmsClientBuilder builder = KmsClient.builder()
                .region(Region.of(awsRegion));

        if (awsProfile != null && !awsProfile.isBlank()) {
            builder = builder.credentialsProvider(
                    ProfileCredentialsProvider.builder()
                            .profileName(awsProfile)
                            .build()
            );
        }
        return builder.build();
    }

    public String getKeyId() {
        return keyId;
    }
}
