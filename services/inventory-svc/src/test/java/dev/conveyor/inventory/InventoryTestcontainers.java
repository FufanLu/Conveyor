package dev.conveyor.inventory;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class InventoryTestcontainers {

    /** Row 1 of the seed: 1,000 units in each of the six FCs. */
    public static final String DEAL_SKU = "B09E3779B1";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine")).withDatabaseName("inventory");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
    }

    /** Talks to the service's in-process gRPC server (spring.grpc.server.inprocess.name). */
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel inventoryChannel() {
        return InProcessChannelBuilder.forName("inventory-test")
                .directExecutor()
                .build();
    }
}
