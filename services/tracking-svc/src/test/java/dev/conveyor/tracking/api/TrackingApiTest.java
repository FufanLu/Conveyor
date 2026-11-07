package dev.conveyor.tracking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Timestamp;
import dev.conveyor.platform.test.TestJwts;
import dev.conveyor.proto.order.v1.OrderEvent;
import dev.conveyor.proto.order.v1.OrderPlaced;
import dev.conveyor.proto.order.v1.OrderStatusChanged;
import dev.conveyor.tracking.TrackingTestcontainers;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "conveyor.tracking.position-write-interval=1s")
@Import(TrackingTestcontainers.class)
class TrackingApiTest {

    private static final ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>> SSE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    Environment environment;

    @Autowired
    KafkaTemplate<String, byte[]> kafka;

    @Autowired
    MeterRegistry meters;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    private WebTestClient http;

    /**
     * The live consumer starts at the latest offset of a brand-new group, so it sees only what
     * is produced after it owns its partitions. Tests that post and then expect a live push wait
     * for that first (a slow CI runner once took long enough for the posts to land before it).
     */
    @BeforeEach
    void liveConsumerOwnsItsPartitions() {
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            var container = listeners.getListenerContainer("tracking-live");
            var assigned = container == null ? null : container.getAssignedPartitions();
            return assigned != null && assigned.size() == 24;
        });
    }

    @BeforeEach
    void client() {
        http = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static String newShipment() {
        return "T"
                + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36)
                        .toUpperCase(java.util.Locale.ROOT);
    }

    /** Publishes OrderPlaced (and optionally the saga's status change) like order-svc's outbox would. */
    private void order(String orderId, String user, String finalStatus) {
        Instant now = Instant.now();
        Timestamp ts = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();
        kafka.send(
                        "orders.events",
                        orderId,
                        OrderEvent.newBuilder()
                                .setEventId(UUID.randomUUID().toString())
                                .setOrderId(orderId)
                                .setOrderVersion(1)
                                .setOccurredAt(ts)
                                .setPlaced(
                                        OrderPlaced.newBuilder().setUserId(user).setShipToRegion("us-east"))
                                .build()
                                .toByteArray())
                .join();
        if (finalStatus != null) {
            OrderStatusChanged.Builder change = OrderStatusChanged.newBuilder().setStatus(finalStatus);
            if ("CONFIRMED".equals(finalStatus)) {
                change.setPromisedDelivery(Timestamp.newBuilder().setSeconds(1_794_000_000L));
            }
            kafka.send(
                            "orders.events",
                            orderId,
                            OrderEvent.newBuilder()
                                    .setEventId(UUID.randomUUID().toString())
                                    .setOrderId(orderId)
                                    .setOrderVersion(2)
                                    .setOccurredAt(ts)
                                    .setStatusChanged(change)
                                    .build()
                                    .toByteArray())
                    .join();
        }
    }

    private static String positions(String shipmentId, long fromSeq, int count) {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seq = fromSeq + i;
            events.add(
                    "{\"shipmentId\":\"%s\",\"seq\":%d,\"recordedAt\":\"%s\",\"position\":{\"lat\":%.5f,\"lon\":%.5f,\"speed\":11.5}}"
                            .formatted(shipmentId, seq, Instant.now(), 40.7 + seq * 0.001, -74.0 - seq * 0.001));
        }
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private WebTestClient.ResponseSpec post(String courier, String body) {
        return http.post()
                .uri("/v1/couriers/{c}/pings", courier)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec getShipment(String id, String token) {
        return http.get()
                .uri("/v1/shipments/{id}", id)
                .header("Authorization", "Bearer " + token)
                .exchange();
    }

    private double counter(String name, String outcome) {
        var c = meters.find(name).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void anOrderBecomesAShipmentOnlyItsOwnerCanSee() {
        String id = newShipment();
        order(id, "user-ann", "CONFIRMED");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> getShipment(id, TestJwts.tokenForSubject("user-ann"))
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("READY")
                        .jsonPath("$.promisedDelivery")
                        .isEqualTo("2026-11-06T21:20:00Z"));

        getShipment(id, TestJwts.tokenForSubject("user-bob")).expectStatus().isNotFound();
        getShipment(id, TestJwts.tokenForSubject("someone", "ops"))
                .expectStatus()
                .isOk();
        http.get().uri("/v1/shipments/{id}", id).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aCancelledOrderNeverBecomesReadyAndIgnoresLateScans() {
        String id = newShipment();
        order(id, "user-ann", "PAYMENT_FAILED");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> getShipment(id, TestJwts.tokenForSubject("user-ann"))
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("CANCELLED"));

        post(
                        "courier-1",
                        "{\"events\":[{\"shipmentId\":\"%s\",\"seq\":1,\"recordedAt\":\"%s\",\"scan\":{\"type\":\"PICKED\",\"location\":\"east-1\"}}]}"
                                .formatted(id, Instant.now()))
                .expectStatus()
                .isAccepted();
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> getShipment(id, TestJwts.tokenForSubject("user-ann"))
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("CANCELLED"));
    }

    @Test
    void reportsAreStoredOnceEvenWhenTheHandheldResendsTheBatch() {
        String id = newShipment();
        order(id, "user-ann", "CONFIRMED");
        double storedBefore = counter("tracking.persist.events", "stored");
        double duplicatesBefore = counter("tracking.persist.events", "duplicate");
        String batch = positions(id, 1, 20);

        post("courier-7", batch)
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.accepted")
                .isEqualTo(20);
        post("courier-7", batch).expectStatus().isAccepted(); // the ack was lost; the handheld retries

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(counter("tracking.persist.events", "stored") - storedBefore)
                    .isEqualTo(20);
            assertThat(counter("tracking.persist.events", "duplicate") - duplicatesBefore)
                    .isEqualTo(20);
        });
        getShipment(id, TestJwts.tokenForSubject("user-ann"))
                .expectBody()
                .jsonPath("$.lastSeq")
                .isEqualTo(20)
                .jsonPath("$.lat")
                .isEqualTo(40.72);
    }

    @Test
    void theStreamSendsHistoryThenLiveWithoutGapsOrRepeatsAndResumesFromLastEventId() {
        String id = newShipment();
        String token = TestJwts.tokenForSubject("user-ann");
        order(id, "user-ann", "CONFIRMED");
        post("courier-3", positions(id, 1, 5)).expectStatus().isAccepted();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> getShipment(id, token)
                        .expectBody()
                        .jsonPath("$.lastSeq")
                        .isEqualTo(5));

        Flux<ServerSentEvent<Map<String, Object>>> stream = http.get()
                .uri("/v1/shipments/{id}/events", id)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(SSE)
                .getResponseBody()
                .filter(e -> e.data() != null);

        StepVerifier.create(stream.map(ServerSentEvent::id).take(8))
                .expectNext("1", "2", "3", "4", "5") // history from DynamoDB
                .then(() ->
                        post("courier-3", positions(id, 4, 4)).expectStatus().isAccepted()) // 4,5 resent + 6,7
                .expectNext("6", "7")
                .then(() ->
                        post("courier-3", positions(id, 8, 1)).expectStatus().isAccepted())
                .expectNext("8")
                .expectComplete()
                .verify(Duration.ofSeconds(60));

        // A reconnect with Last-Event-ID: 6 gets 7 and 8 only.
        Flux<String> resumed = http.get()
                .uri("/v1/shipments/{id}/events", id)
                .header("Authorization", "Bearer " + token)
                .header("Last-Event-ID", "6")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .returnResult(SSE)
                .getResponseBody()
                .filter(e -> e.data() != null)
                .map(ServerSentEvent::id);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> getShipment(id, token)
                        .expectBody()
                        .jsonPath("$.lastSeq")
                        .isEqualTo(8));
        StepVerifier.create(resumed.take(2))
                .expectNext("7", "8")
                .expectComplete()
                .verify(Duration.ofSeconds(60));
    }

    @Test
    void theOpsMapNeedsRoleOpsAndConflatesToTheLatestPositionPerShipment() {
        http.get()
                .uri("/v1/ops/map")
                .header("Authorization", "Bearer " + TestJwts.tokenForSubject("user-ann"))
                .exchange()
                .expectStatus()
                .isForbidden();

        String a = newShipment();
        String b = newShipment();
        Flux<ServerSentEvent<List<Map<String, Object>>>> map = http.get()
                .uri("/v1/ops/map")
                .header("Authorization", "Bearer " + TestJwts.tokenForSubject("op", "ops"))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<List<Map<String, Object>>>>() {})
                .getResponseBody()
                .filter(e -> e.data() != null);

        StepVerifier.create(map.map(e -> e.data().stream()
                                .filter(d -> a.equals(d.get("shipmentId")) || b.equals(d.get("shipmentId")))
                                .toList())
                        .filter(dots -> !dots.isEmpty())
                        .take(1))
                .then(() -> {
                    post("c", positions(a, 1, 30)).expectStatus().isAccepted();
                    post("c", positions(b, 1, 30)).expectStatus().isAccepted();
                })
                .assertNext(dots -> {
                    // 60 reports arrive within one 500 ms frame, so the frame holds one dot per shipment.
                    assertThat(dots).extracting(d -> d.get("shipmentId")).doesNotHaveDuplicates();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(60));
    }

    @Test
    void invalidBatchesAreRefusedBeforeKafka() {
        post("c", "{\"events\":[]}").expectStatus().isBadRequest();
        post(
                        "c",
                        "{\"events\":[{\"shipmentId\":\"S1\",\"seq\":1,\"recordedAt\":\"2026-11-06T00:00:00Z\",\"position\":{\"lat\":91,\"lon\":0}}]}")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.detail")
                .value(d -> assertThat((String) d).contains("lat in [-90, 90]"));
        post(
                        "c",
                        "{\"events\":[{\"shipmentId\":\"S1\",\"seq\":1,\"recordedAt\":\"2026-11-06T00:00:00Z\",\"scan\":{\"type\":\"TELEPORTED\"}}]}")
                .expectStatus()
                .isBadRequest();
    }
}
