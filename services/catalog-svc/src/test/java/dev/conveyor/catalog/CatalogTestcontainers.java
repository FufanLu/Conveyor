package dev.conveyor.catalog;

import dev.conveyor.platform.test.TestJwts;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real backing services, in containers, with the same images as deploy/compose. Shared by
 * every integration test: Spring caches the context, so the containers start once per run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CatalogTestcontainers {

    /** Row 1 of the deterministic seed: 'B0' + hex(1 * 2654435761 mod 2^32). */
    public static final String KNOWN_SKU = "B09E3779B1";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> opensearch() {
        return new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.9.0"))
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow")
                        .forPort(9200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    @Bean
    DynamicPropertyRegistrar openSearchUrl(GenericContainer<?> opensearch) {
        return registry -> registry.add(
                "conveyor.catalog.search.url",
                () -> "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200));
    }

    /** Trusts tokens minted by TestJwts instead of discovering Keycloak. */
    @Bean
    JwtDecoder jwtDecoder() {
        return TestJwts.decoder();
    }
}
