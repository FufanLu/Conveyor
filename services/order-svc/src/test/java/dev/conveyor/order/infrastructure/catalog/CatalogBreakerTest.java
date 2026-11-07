package dev.conveyor.order.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.conveyor.order.application.PricingUnavailableException;
import dev.conveyor.order.application.UnknownSkusException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The breaker against a real local HTTP server that can be switched between modes. */
class CatalogBreakerTest {

    enum Mode {
        OK,
        FAIL,
        UNKNOWN
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
    private final AtomicInteger hits = new AtomicInteger();
    private HttpServer server;
    private CatalogPricing pricing;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/products/", ex -> {
            hits.incrementAndGet();
            int status = switch (mode.get()) {
                case OK -> 200;
                case FAIL -> 500;
                case UNKNOWN -> 404;
            };
            byte[] body = "{\"sku\":\"B0TEST0001\",\"price\":{\"amountCents\":1999,\"currency\":\"USD\"},\"version\":1}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        server.start();
        pricing = new CatalogPricing(
                new CatalogProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        Duration.ofMillis(200),
                        Duration.ofMillis(300),
                        Duration.ofMillis(400),
                        4,
                        50,
                        Duration.ofMillis(800)),
                new SimpleMeterRegistry(),
                org.springframework.web.client.RestClient.builder());
    }

    @AfterEach
    void stop() {
        pricing.close();
        server.stop(0);
    }

    @Test
    void aFailingCatalogOpensTheBreakerAndOrdersAreRefusedWithoutCallingIt() throws Exception {
        mode.set(Mode.FAIL);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> pricing.quote(List.of("B0TEST0001")))
                    .isInstanceOf(PricingUnavailableException.class);
        }
        assertThat(pricing.breakerState()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = hits.get();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> pricing.quote(List.of("B0TEST0001")))
                .isInstanceOf(PricingUnavailableException.class)
                .hasMessageContaining("circuit open");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(50);
        assertThat(hits.get()).isEqualTo(before); // catalog was spared

        // catalog recovers; after the open period, trial calls close the breaker
        mode.set(Mode.OK);
        Thread.sleep(900);
        for (int i = 0; i < 5; i++) {
            assertThat(pricing.quote(List.of("B0TEST0001"))).containsKey("B0TEST0001");
        }
        assertThat(pricing.breakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void unknownSkusAreTheCustomersMistakeAndNeverOpenIt() {
        mode.set(Mode.UNKNOWN);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> pricing.quote(List.of("B0NOPE0000"))).isInstanceOf(UnknownSkusException.class);
        }
        assertThat(pricing.breakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
