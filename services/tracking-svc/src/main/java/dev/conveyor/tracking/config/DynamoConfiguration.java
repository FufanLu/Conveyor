package dev.conveyor.tracking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;

/** The real AWS SDK v2 async client. Pointed at DynamoDB Local, it needs any static credentials. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrackingProperties.class)
class DynamoConfiguration {

    @Bean(destroyMethod = "close")
    DynamoDbAsyncClient dynamoDb(TrackingProperties p) {
        DynamoDbAsyncClientBuilder b = DynamoDbAsyncClient.builder()
                .region(Region.of(p.region()))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(256)
                        .connectionAcquisitionTimeout(Duration.ofSeconds(5)));
        if (p.dynamoEndpoint() != null && !p.dynamoEndpoint().toString().isBlank()) {
            b.endpointOverride(p.dynamoEndpoint())
                    .credentialsProvider(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return b.build();
    }
}
