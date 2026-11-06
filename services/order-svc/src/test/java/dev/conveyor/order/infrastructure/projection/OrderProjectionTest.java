package dev.conveyor.order.infrastructure.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import dev.conveyor.order.HttpTestClient;
import dev.conveyor.order.OrderTestcontainers;
import dev.conveyor.platform.test.TestJwts;
import dev.conveyor.proto.order.v1.OrderEvent;
import dev.conveyor.proto.order.v1.OrderLine;
import dev.conveyor.proto.order.v1.OrderPlaced;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;

/** The CQRS read side, fed straight from Kafka (the Debezium leg is exercised in chapter 02). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(OrderTestcontainers.class)
class OrderProjectionTest {

    @Autowired
    private KafkaTemplate<String, byte[]> kafka;

    @Autowired
    private OpenSearchClient openSearch;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private Environment environment;

    private HttpTestClient http;

    @BeforeEach
    void client() {
        http = new HttpTestClient(environment);
    }

    @Test
    void eventsBecomeSearchableAndDuplicatesAreDropped() throws Exception {
        String orderId = "0ABCDEFGH"
                + String.format("%04d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000));
        OrderEvent placed = event(orderId, 1, "B0DAA66D13");

        kafka.send(OrderTopics.ORDER_EVENTS, orderId, placed.toByteArray()).get();
        eventually(() -> indexedVersion(orderId) == 1);

        double duplicatesBefore = counter("duplicate");
        kafka.send(OrderTopics.ORDER_EVENTS, orderId, placed.toByteArray()).get(); // redelivery
        eventually(() -> counter("duplicate") > duplicatesBefore);
        assertThat(indexedVersion(orderId)).isEqualTo(1);

        HttpResponse<String> ops = http.get(
                "/v1/ops/orders?sku=B0DAA66D13&status=PLACED",
                "Authorization",
                "Bearer " + TestJwts.token("ops", "ops"));
        assertThat(ops.statusCode()).isEqualTo(200);
        assertThat(ops.body()).contains("\"orderId\":\"" + orderId + "\"");

        HttpResponse<String> customer =
                http.get("/v1/ops/orders", "Authorization", "Bearer " + TestJwts.token("alice", "customer"));
        assertThat(customer.statusCode()).isEqualTo(403);
    }

    private long indexedVersion(String orderId) {
        try {
            var doc = openSearch.get(g -> g.index("orders").id(orderId), OrderDocument.class);
            return doc.found() && doc.source() != null ? doc.source().version() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private double counter(String outcome) {
        var c = registry.find("order.projection.events").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private static OrderEvent event(String orderId, long version, String sku) {
        Instant now = Instant.now();
        return OrderEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrderId(orderId)
                .setOrderVersion(version)
                .setOccurredAt(
                        Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .setPlaced(OrderPlaced.newBuilder()
                        .setUserId("user-projection-test")
                        .setTotalCents(250)
                        .setCurrency("USD")
                        .setShipToRegion("us-east")
                        .addLines(OrderLine.newBuilder()
                                .setSku(sku)
                                .setQuantity(1)
                                .setUnitPriceCents(250)))
                .build();
    }

    private static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 60 s");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
