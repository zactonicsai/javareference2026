package com.example.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * S3 client wired to LocalStack by default.
 *
 * - Path-style addressing (LocalStack doesn't do virtual-hosted style without DNS plumbing).
 * - Tight HTTP timeouts so a missing LocalStack at startup doesn't stall context init.
 * - Bucket auto-creation if missing; opt-out via app.s3.skip-bucket-check=true (used in tests).
 */
@Slf4j
@Configuration
public class S3Config {

    @Value("${app.s3.endpoint:http://localhost:4566}")
    private String endpoint;

    @Value("${app.s3.region:us-east-1}")
    private String region;

    @Value("${app.s3.access-key:test}")
    private String accessKey;

    @Value("${app.s3.secret-key:test}")
    private String secretKey;

    @Value("${app.s3.bucket:demo-uploads}")
    private String bucket;

    @Value("${app.s3.path-style:true}")
    private boolean pathStyle;

    @Value("${app.s3.skip-bucket-check:false}")
    private boolean skipBucketCheck;

    @Bean
    public S3Client s3Client() {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .httpClient(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(10))
                        .build())
                .build();
        if (!skipBucketCheck) ensureBucket(client, bucket);
        return client;
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .build();
    }

    private void ensureBucket(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("S3 bucket '{}' already exists", bucket);
        } catch (NoSuchBucketException nsb) {
            log.info("S3 bucket '{}' missing, creating it", bucket);
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ce) {
                log.warn("Failed to create bucket '{}': {}", bucket, ce.getMessage());
            }
        } catch (Exception e) {
            log.warn("S3 bucket check failed (continuing anyway): {}", e.getMessage());
        }
    }
}
