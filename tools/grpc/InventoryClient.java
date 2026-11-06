import com.google.protobuf.Duration;
import dev.conveyor.proto.inventory.v1.CommitRequest;
import dev.conveyor.proto.inventory.v1.GetAvailabilityRequest;
import dev.conveyor.proto.inventory.v1.InventoryServiceGrpc;
import dev.conveyor.proto.inventory.v1.Line;
import dev.conveyor.proto.inventory.v1.ReleaseReason;
import dev.conveyor.proto.inventory.v1.ReleaseRequest;
import dev.conveyor.proto.inventory.v1.ReservationStatus;
import dev.conveyor.proto.inventory.v1.ReserveRequest;
import dev.conveyor.proto.inventory.v1.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Talks to inventory-svc's gRPC API the way the order saga will.
 *
 *   java -cp 'build/inventory-svc-extracted/inventory-svc.jar:build/inventory-svc-extracted/lib/*' tools/grpc/InventoryClient.java \
 *     localhost:9083 reserve us-west B09E3779B1:3 [ttlSeconds]
 *   ... commit <reservation-id> | release <reservation-id> | avail <sku>...
 *   ... burst <sku> <buyers> <region>     (every buyer reserves 1 unit at the same moment)
 */
public class InventoryClient {
    public static void main(String[] a) throws Exception {
        String[] hp = a[0].split(":");
        ManagedChannel channel = ManagedChannelBuilder.forAddress(hp[0], Integer.parseInt(hp[1])).usePlaintext().build();
        try {
            var inv = InventoryServiceGrpc.newBlockingStub(channel);
            switch (a[1]) {
                case "reserve" -> {
                    ReserveRequest.Builder r = ReserveRequest.newBuilder()
                            .setIdempotencyKey("cli-" + UUID.randomUUID())
                            .setOrderId("order-cli-" + System.currentTimeMillis())
                            .setShipToRegion(a[2]);
                    int i = 3;
                    for (; i < a.length && a[i].contains(":"); i++) {
                        String[] l = a[i].split(":");
                        r.addLines(Line.newBuilder().setSku(l[0]).setQuantity(Integer.parseInt(l[1])));
                    }
                    if (i < a.length) {
                        r.setHoldTtl(Duration.newBuilder().setSeconds(Long.parseLong(a[i])));
                    }
                    long t0 = System.nanoTime();
                    ReserveResponse resp = inv.reserve(r.build());
                    System.out.printf("(%.1f ms)%n%s", (System.nanoTime() - t0) / 1e6, resp);
                }
                case "commit" -> System.out.print(inv.commit(CommitRequest.newBuilder().setReservationId(a[2]).build()));
                case "release" -> System.out.print(inv.release(ReleaseRequest.newBuilder()
                        .setReservationId(a[2])
                        .setReason(ReleaseReason.RELEASE_REASON_ORDER_CANCELLED)
                        .build()));
                case "avail" -> System.out.print(inv.getAvailability(GetAvailabilityRequest.newBuilder()
                        .addAllSkus(Arrays.asList(a).subList(2, a.length))
                        .build()));
                case "burst" -> burst(channel, a[2], Integer.parseInt(a[3]), a[4]);
                default -> throw new IllegalArgumentException(a[1]);
            }
        } finally {
            channel.shutdownNow();
        }
    }

    static void burst(ManagedChannel channel, String sku, int buyers, String region) throws Exception {
        var inv = InventoryServiceGrpc.newBlockingStub(channel);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<long[]>> calls = new ArrayList<>();
        long start;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < buyers; i++) {
                calls.add(pool.submit(() -> {
                    ReserveRequest request = ReserveRequest.newBuilder()
                            .setIdempotencyKey("burst-" + UUID.randomUUID()) // one key for all retries
                            .setOrderId("burst-order-" + UUID.randomUUID())
                            .setShipToRegion(region)
                            .addLines(Line.newBuilder().setSku(sku).setQuantity(1))
                            .build();
                    go.await();
                    long t0 = System.nanoTime();
                    for (int attempt = 0; ; attempt++) {
                        try {
                            ReserveResponse r = inv.reserve(request);
                            return new long[] {r.getStatus().getNumber(), System.nanoTime() - t0, System.nanoTime(), attempt};
                        } catch (io.grpc.StatusRuntimeException e) {
                            var code = e.getStatus().getCode();
                            boolean retryable = code == io.grpc.Status.Code.RESOURCE_EXHAUSTED
                                    || code == io.grpc.Status.Code.UNAVAILABLE;
                            if (!retryable || attempt >= 30) {
                                throw e;
                            }
                            // exponential backoff with full jitter (Builders' Library), capped at 2 s
                            long cap = Math.min(2_000, 50L << Math.min(attempt, 6));
                            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(cap + 1));
                        }
                    }
                }));
            }
            start = System.nanoTime();
            go.countDown();
        }
        Map<ReservationStatus, Integer> outcomes = new EnumMap<>(ReservationStatus.class);
        long[] latencies = new long[buyers];
        long last = 0;
        int errors = 0;
        long retries = 0;
        int maxAttempts = 0;
        for (int i = 0; i < buyers; i++) {
            try {
                long[] r = calls.get(i).get();
                outcomes.merge(ReservationStatus.forNumber((int) r[0]), 1, Integer::sum);
                latencies[i] = r[1];
                last = Math.max(last, r[2]);
                retries += r[3];
                maxAttempts = Math.max(maxAttempts, (int) r[3] + 1);
            } catch (Exception e) {
                errors++;
            }
        }
        System.out.printf("retries after RESOURCE_EXHAUSTED/UNAVAILABLE: %d in total, at most %d attempts for one buyer%n",
                retries, maxAttempts);
        Arrays.sort(latencies);
        System.out.printf("%d buyers for %s from %s: %s, errors %d, all answered after %.2f s%n",
                buyers, sku, region, outcomes, errors, (last - start) / 1e9);
        System.out.printf("time to a final answer per buyer, ms: p50 %.1f  p99 %.1f  max %.1f%n",
                latencies[buyers / 2] / 1e6, latencies[(int) (buyers * 0.99)] / 1e6, latencies[buyers - 1] / 1e6);
    }
}
