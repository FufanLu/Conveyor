package dev.conveyor.order;

import com.sun.net.httpserver.HttpServer;
import dev.conveyor.platform.test.TestJwts;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Two Postgres databases (the two physical shard groups), Kafka, OpenSearch, and a stub
 * catalog that prices a fixed set of SKUs and can be switched to failing.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OrderTestcontainers {

    public static final Map<String, Long> PRICES = Map.of(
            "B09E3779B1", 1999L,
            "B03C6EF362", 4500L,
            "B0DAA66D13", 250L);
    public static final AtomicBoolean CATALOG_FAILING = new AtomicBoolean();
    private static final HttpServer CATALOG = startCatalog();

    @Bean
    PostgreSQLContainer pg0() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine")).withDatabaseName("orders");
    }

    @Bean
    PostgreSQLContainer pg1() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine")).withDatabaseName("orders");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
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
    DynamicPropertyRegistrar orderInfrastructure(
            PostgreSQLContainer pg0, PostgreSQLContainer pg1, GenericContainer<?> opensearch) {
        return registry -> {
            for (var db : Map.of("pg0", pg0, "pg1", pg1).entrySet()) {
                String prefix = "conveyor.order.databases." + db.getKey();
                registry.add(prefix + ".url", db.getValue()::getJdbcUrl);
                registry.add(prefix + ".username", db.getValue()::getUsername);
                registry.add(prefix + ".password", db.getValue()::getPassword);
            }
            registry.add(
                    "conveyor.order.search.url",
                    () -> "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200));
            registry.add(
                    "conveyor.order.catalog.url",
                    () -> "http://localhost:" + CATALOG.getAddress().getPort());
        };
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return TestJwts.decoder();
    }

    private static HttpServer startCatalog() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/products/", exchange -> {
                String sku = exchange.getRequestURI().getPath().substring("/v1/products/".length());
                int status;
                String body;
                if (CATALOG_FAILING.get()) {
                    status = 500;
                    body = "{}";
                } else if (PRICES.containsKey(sku)) {
                    status = 200;
                    body = "{\"sku\":\"" + sku + "\",\"price\":{\"amountCents\":" + PRICES.get(sku)
                            + ",\"currency\":\"USD\"},\"version\":1}";
                } else {
                    status = 404;
                    body = "{}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
