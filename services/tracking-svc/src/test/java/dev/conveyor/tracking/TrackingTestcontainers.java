package dev.conveyor.tracking;

import dev.conveyor.platform.test.TestJwts;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Kafka and the official DynamoDB Local, the same images as compose. */
@TestConfiguration(proxyBeanMethods = false)
public class TrackingTestcontainers {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    }

    @Bean
    GenericContainer<?> dynamodb() {
        return new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.3.2"))
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
                .withExposedPorts(8000)
                .waitingFor(Wait.forListeningPort());
    }

    @Bean
    DynamicPropertyRegistrar trackingInfrastructure(GenericContainer<?> dynamodb) {
        return registry -> registry.add(
                "conveyor.tracking.dynamo-endpoint",
                () -> "http://" + dynamodb.getHost() + ":" + dynamodb.getMappedPort(8000));
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder() {
        return TestJwts.reactiveDecoder();
    }
}
