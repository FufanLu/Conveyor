package dev.conveyor.inventory.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.inventory.InventoryTestcontainers;
import dev.conveyor.inventory.StockFixtures;
import dev.conveyor.inventory.application.FulfillmentCenters;
import dev.conveyor.inventory.application.ReservationService;
import dev.conveyor.inventory.application.ReservationStore;
import dev.conveyor.inventory.application.ReserveCommand;
import dev.conveyor.inventory.domain.FulfillmentCenter;
import dev.conveyor.inventory.domain.Line;
import dev.conveyor.inventory.domain.Reservation;
import dev.conveyor.proto.inventory.v1.InventoryServiceGrpc;
import dev.conveyor.proto.inventory.v1.ReservationStatus;
import dev.conveyor.proto.inventory.v1.ReserveRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Overselling, expiry, crash leftovers and Redis restarts, against real Postgres and Redis. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(InventoryTestcontainers.class)
class StockConsistencyTest {

    @Autowired
    private ReservationService reservations;

    @Autowired
    private ReservationStore store;

    @Autowired
    private RedisStockGate gate;

    @Autowired
    private StockReconciler reconciler;

    @Autowired
    private FulfillmentCenters fcs;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ManagedChannel channel;

    /**
     * Through the real entry point: the gRPC service with its admission bulkhead, and clients
     * that retry RESOURCE_EXHAUSTED with jittered backoff and the same idempotency key, as the
     * saga does. (Calling the service layer with 1,000 threads at once bypasses the bulkhead and
     * only measures how long Redis takes to drain an unbounded queue on a loaded machine.)
     */
    @Test
    void aThousandBuyersForAHundredUnitsNeverOversell() throws Exception {
        String sku = StockFixtures.sku(jdbc, Map.of("east-1", 60, "west-1", 40));
        var inventory = InventoryServiceGrpc.newBlockingStub(channel);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReservationStatus>> buyers = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1_000; i++) {
                buyers.add(pool.submit(() -> {
                    ReserveRequest request = ReserveRequest.newBuilder()
                            .setIdempotencyKey("k-" + UUID.randomUUID())
                            .setOrderId("order-" + UUID.randomUUID())
                            .setShipToRegion("us-east")
                            .addLines(dev.conveyor.proto.inventory.v1.Line.newBuilder()
                                    .setSku(sku)
                                    .setQuantity(1))
                            .build();
                    go.await();
                    for (int attempt = 0; ; attempt++) {
                        try {
                            return inventory.reserve(request).getStatus();
                        } catch (StatusRuntimeException e) {
                            if (e.getStatus().getCode() != Status.Code.RESOURCE_EXHAUSTED || attempt > 50) {
                                throw e;
                            }
                            Thread.sleep(
                                    ThreadLocalRandom.current().nextLong(Math.min(1_000, 20L << Math.min(attempt, 6))));
                        }
                    }
                }));
            }
            go.countDown();
            int held = 0;
            int rejected = 0;
            for (Future<ReservationStatus> b : buyers) {
                switch (b.get()) {
                    case RESERVATION_STATUS_HELD -> held++;
                    case RESERVATION_STATUS_REJECTED -> rejected++;
                    default -> throw new AssertionError("unexpected " + b.get());
                }
            }
            assertThat(held).isEqualTo(100);
            assertThat(rejected).isEqualTo(900);
        }
        assertThat(gate.available(sku, fcs.all()).values().stream()
                        .mapToLong(Long::longValue)
                        .sum())
                .isZero();
        assertThat(store.truth(sku, List.of("east-1", "west-1")))
                .containsEntry("east-1", 0L)
                .containsEntry("west-1", 0L);
        assertThat(jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM reservation_line WHERE sku = :sku")
                        .param("sku", sku)
                        .query(Long.class)
                        .single())
                .isEqualTo(100);
    }

    @Test
    void overdueHoldsExpireAndReturnTheirUnits() {
        String sku = StockFixtures.sku(jdbc, Map.of("south-1", 5));
        Reservation held = reservations.reserve(command(sku, 5, "us-south"));
        jdbc.sql("UPDATE reservation SET expires_at = now() - interval '1 second' WHERE reservation_id = :id")
                .param("id", held.id())
                .update();

        reservations.expireDue(100);

        assertThat(store.find(held.id()).orElseThrow().status())
                .isEqualTo(dev.conveyor.inventory.domain.ReservationStatus.EXPIRED);
        assertThat(reservations.availability(List.of(sku))).containsEntry(sku, 5L);
    }

    @Test
    void aClaimLeftBehindByACrashIsRepairedOnceItIsStable() {
        String sku = StockFixtures.sku(jdbc, Map.of("west-2", 20));
        List<FulfillmentCenter> west =
                fcs.all().stream().filter(f -> f.id().equals("west-2")).toList();
        reservations.reserve(command(sku, 3, "us-west")); // a real hold, so the pair counts as touched
        gate.claim(sku, 4, west); // claimed in Redis, then "the process died" before the insert

        assertThat(counter(sku, "west-2")).isEqualTo(13);
        assertThat(reconciler.reconcileRecent(Duration.ofMinutes(5))).isZero(); // first sighting: wait
        assertThat(reconciler.reconcileRecent(Duration.ofMinutes(5))).isEqualTo(1); // stable: repair

        assertThat(counter(sku, "west-2")).isEqualTo(17); // 20 - 3 held
    }

    @Test
    void aBusyCounterIsNotTouched() {
        String sku = StockFixtures.sku(jdbc, Map.of("central-1", 50));
        reservations.reserve(command(sku, 1, "us-central"));
        redis.opsForValue().decrement(RedisStockGate.key(sku, "central-1"), 2); // looks like in-flight claims

        reconciler.reconcileRecent(Duration.ofMinutes(5));
        redis.opsForValue().decrement(RedisStockGate.key(sku, "central-1"), 1); // still moving
        assertThat(reconciler.reconcileRecent(Duration.ofMinutes(5))).isZero();
    }

    @Test
    void aRestartedRedisDropsEveryCounterAndTheyRebuildFromPostgres() {
        String sku = StockFixtures.sku(jdbc, Map.of("east-2", 9));
        reservations.reserve(command(sku, 2, "us-east"));
        redis.opsForValue().set(RedisStockGate.key(sku, "east-2"), "99"); // as if decrements were lost

        reconciler.onRunId("before-restart");
        reconciler.onRunId("after-restart");

        assertThat(redis.hasKey(RedisStockGate.key(sku, "east-2"))).isFalse();
        assertThat(reservations.availabilityByFc(sku)).containsEntry("east-2", 7L);
    }

    private long counter(String sku, String fc) {
        return Long.parseLong(redis.opsForValue().get(RedisStockGate.key(sku, fc)));
    }

    private static ReserveCommand command(String sku, int quantity, String region) {
        return new ReserveCommand(
                "k-" + UUID.randomUUID(), "order-" + UUID.randomUUID(), List.of(new Line(sku, quantity)), region, null);
    }
}
