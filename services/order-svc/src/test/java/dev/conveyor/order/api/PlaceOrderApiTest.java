package dev.conveyor.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.order.HttpTestClient;
import dev.conveyor.order.OrderTestcontainers;
import dev.conveyor.order.domain.OrderId;
import dev.conveyor.order.domain.Shards;
import dev.conveyor.order.infrastructure.shard.ShardGroup;
import dev.conveyor.order.infrastructure.shard.ShardRouter;
import dev.conveyor.platform.test.TestJwts;
import dev.conveyor.proto.order.v1.OrderEvent;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(OrderTestcontainers.class)
class PlaceOrderApiTest {

    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\":\"([0-9A-Z]{13})\"");
    private static final String BODY =
            "{\"items\":[{\"sku\":\"B03C6EF362\",\"quantity\":2},{\"sku\":\"B09E3779B1\",\"quantity\":1}],"
                    + "\"shipToRegion\":\"us-east\"}";

    @Autowired
    private Environment environment;

    @Autowired
    private ShardRouter router;

    private HttpTestClient http;
    private String user;
    private String token;

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(environment);
        user = "user-" + UUID.randomUUID(); // a fresh customer per test
        token = "Bearer " + TestJwts.tokenForSubject(user, "customer");
        OrderTestcontainers.CATALOG_FAILING.set(false);
    }

    @AfterEach
    void healCatalog() {
        OrderTestcontainers.CATALOG_FAILING.set(false);
    }

    @Test
    void placesAnOrderOnTheCustomersShardWithItsEventInTheSameTransaction() throws Exception {
        HttpResponse<String> response = place("key-" + UUID.randomUUID(), BODY);

        assertThat(response.statusCode()).isEqualTo(201);
        OrderId id = OrderId.parse(orderId(response));
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(l -> assertThat(l).endsWith("/v1/orders/" + id));
        // 2 x 45.00 + 1 x 19.99, priced by the catalog, not the client
        assertThat(response.body()).contains("\"totalCents\":10999").contains("\"status\":\"PLACED\"");

        int shard = Shards.forUser(user);
        assertThat(id.shard()).isEqualTo(shard);
        ShardGroup db = router.group(shard);
        String t = ShardRouter.suffix(shard);
        assertThat(db.jdbc()
                        .sql("SELECT count(*) FROM orders_" + t + " WHERE order_id = :id")
                        .param("id", id.value())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        byte[] payload = db.jdbc()
                .sql("SELECT payload FROM outbox WHERE aggregateid = :id")
                .param("id", id.toString())
                .query(byte[].class)
                .single();
        OrderEvent event = OrderEvent.parseFrom(payload);
        assertThat(event.getOrderId()).isEqualTo(id.toString());
        assertThat(event.getOrderVersion()).isEqualTo(1);
        assertThat(event.getPlaced().getTotalCents()).isEqualTo(10_999);
        assertThat(event.getPlaced().getLinesList())
                .extracting(l -> l.getSku() + "x" + l.getQuantity() + "@" + l.getUnitPriceCents())
                .containsExactly("B03C6EF362x2@4500", "B09E3779B1x1@1999");
        // and nothing about this order exists on the other database
        ShardGroup other = router.groups().stream()
                .filter(g -> !g.holds(shard))
                .findFirst()
                .orElseThrow();
        assertThat(other.jdbc()
                        .sql("SELECT count(*) FROM outbox WHERE aggregateid = :id")
                        .param("id", id.toString())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void retryWithTheSameKeyReplaysTheOriginalOrder() throws Exception {
        String key = "key-" + UUID.randomUUID();
        HttpResponse<String> first = place(key, BODY);
        HttpResponse<String> retry = place(key, BODY);

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(orderId(retry)).isEqualTo(orderId(first));
        assertThat(outboxRowsFor(orderId(first))).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentRequestIs422() throws Exception {
        String key = "key-" + UUID.randomUUID();
        place(key, BODY);

        HttpResponse<String> other =
                place(key, "{\"items\":[{\"sku\":\"B0DAA66D13\",\"quantity\":1}],\"shipToRegion\":\"us-east\"}");

        assertThat(other.statusCode()).isEqualTo(422);
        assertThat(other.body()).contains("already used for a different request");
    }

    @Test
    void twentyConcurrentRetriesCreateExactlyOneOrder() throws Exception {
        String key = "key-" + UUID.randomUUID();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return place(key, BODY);
                }));
            }
            go.countDown();
            Set<String> ids = new HashSet<>();
            int replayed = 0;
            for (Future<HttpResponse<String>> call : calls) {
                HttpResponse<String> r = call.get();
                assertThat(r.statusCode()).isEqualTo(201);
                ids.add(orderId(r));
                if (r.headers().firstValue("Idempotent-Replayed").isPresent()) {
                    replayed++;
                }
            }
            assertThat(ids).hasSize(1);
            assertThat(replayed).isEqualTo(19);
            assertThat(outboxRowsFor(ids.iterator().next())).isEqualTo(1);
        }
    }

    @Test
    void validation() throws Exception {
        assertThat(http.post("/v1/orders", BODY, "Authorization", token).statusCode())
                .isEqualTo(400); // no key
        assertThat(place("short", BODY).statusCode()).isEqualTo(400);
        assertThat(place("key-" + UUID.randomUUID(), "{\"items\":[],\"shipToRegion\":\"us-east\"}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(place(
                                "key-" + UUID.randomUUID(),
                                "{\"items\":[{\"sku\":\"B09E3779B1\",\"quantity\":11}],\"shipToRegion\":\"us-east\"}")
                        .statusCode())
                .isEqualTo(400);
        HttpResponse<String> unknown = place(
                "key-" + UUID.randomUUID(),
                "{\"items\":[{\"sku\":\"B0FFFFFFFF\",\"quantity\":1}],\"shipToRegion\":\"us-east\"}");
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("\"unknownSkus\":[\"B0FFFFFFFF\"]");
    }

    @Test
    void catalogOutageIs503AndLeavesTheKeyFreeForTheRetry() throws Exception {
        String key = "key-" + UUID.randomUUID();
        OrderTestcontainers.CATALOG_FAILING.set(true);

        HttpResponse<String> during = place(key, BODY);
        assertThat(during.statusCode()).isEqualTo(503);
        assertThat(during.headers().firstValue("Retry-After")).hasValue("2");

        OrderTestcontainers.CATALOG_FAILING.set(false);
        HttpResponse<String> after = place(key, BODY);
        assertThat(after.statusCode()).isEqualTo(201);
        assertThat(after.headers().firstValue("Idempotent-Replayed")).isEmpty(); // nothing was stored before
    }

    @Test
    void customersSeeOnlyTheirOwnOrdersNewestFirst() throws Exception {
        List<String> placed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            placed.add(orderId(place("key-" + UUID.randomUUID(), BODY)));
        }

        HttpResponse<String> mine = http.get("/v1/orders?limit=2", "Authorization", token);
        assertThat(ids(mine.body())).containsExactly(placed.get(2), placed.get(1));
        assertThat(mine.body()).contains("\"next\":\"" + placed.get(1) + "\"");
        HttpResponse<String> rest = http.get("/v1/orders?limit=2&before=" + placed.get(1), "Authorization", token);
        assertThat(ids(rest.body())).containsExactly(placed.get(0));
        assertThat(rest.body()).contains("\"next\":null");

        String stranger = "Bearer " + TestJwts.tokenForSubject("someone-else", "customer");
        assertThat(http.get("/v1/orders/" + placed.get(0), "Authorization", stranger)
                        .statusCode())
                .isEqualTo(404);
        assertThat(http.get("/v1/orders/" + placed.get(0), "Authorization", token)
                        .statusCode())
                .isEqualTo(200);
        assertThat(http.get("/v1/orders/not-an-id", "Authorization", token).statusCode())
                .isEqualTo(400);
        assertThat(http.get("/v1/orders/" + placed.get(0)).statusCode()).isEqualTo(401);
    }

    @Test
    void theSagaMovesAnOrderToOneTerminalStatusWithItsEventAndRetriesAreHarmless() throws Exception {
        String id = orderId(place("key-" + UUID.randomUUID(), BODY));
        String confirm = "{\"status\":\"CONFIRMED\",\"promisedDelivery\":\"2026-11-09T00:00:00Z\"}";

        HttpResponse<String> first = http.post("/internal/v1/orders/" + id + "/status", confirm);
        HttpResponse<String> retry = http.post("/internal/v1/orders/" + id + "/status", confirm);
        HttpResponse<String> other = http.post("/internal/v1/orders/" + id + "/status", "{\"status\":\"CANCELLED\"}");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body())
                .contains("\"status\":\"CONFIRMED\"")
                .contains("\"version\":2")
                .contains("\"promisedDelivery\":\"2026-11-09T00:00:00Z\"");
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).contains("\"version\":2");
        assertThat(other.statusCode()).isEqualTo(409);
        assertThat(other.body()).contains("\"currentStatus\":\"CONFIRMED\"");

        ShardGroup db = router.group(OrderId.parse(id).shard());
        List<byte[]> events = db.jdbc()
                .sql("SELECT payload FROM outbox WHERE aggregateid = :id ORDER BY created_at")
                .param("id", id)
                .query(byte[].class)
                .list();
        assertThat(events).hasSize(2);
        OrderEvent changed = OrderEvent.parseFrom(events.get(1));
        assertThat(changed.getOrderVersion()).isEqualTo(2);
        assertThat(changed.getStatusChanged().getStatus()).isEqualTo("CONFIRMED");
    }

    private HttpResponse<String> place(String key, String body) throws Exception {
        return http.post("/v1/orders", body, "Authorization", token, "Idempotency-Key", key);
    }

    private long outboxRowsFor(String orderId) {
        ShardGroup db = router.group(OrderId.parse(orderId).shard());
        return db.jdbc()
                .sql("SELECT count(*) FROM outbox WHERE aggregateid = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();
    }

    private static String orderId(HttpResponse<String> response) {
        Matcher m = ORDER_ID.matcher(response.body());
        assertThat(m.find()).as(response.body()).isTrue();
        return m.group(1);
    }

    private static List<String> ids(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = ORDER_ID.matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
