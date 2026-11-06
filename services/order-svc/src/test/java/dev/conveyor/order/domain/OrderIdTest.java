package dev.conveyor.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class OrderIdTest {

    @Test
    void layoutRoundTrips() {
        OrderId id = OrderId.of(123_456_789L, 11, 200, 1023);

        assertThat(id.shard()).isEqualTo(11);
        assertThat(id.worker()).isEqualTo(200);
        assertThat(id.sequence()).isEqualTo(1023);
        assertThat(id.createdAtEpochMillis()).isEqualTo(OrderId.EPOCH_MILLIS + 123_456_789L);
        assertThat(OrderId.parse(id.toString())).isEqualTo(id);
        assertThat(id.toString()).hasSize(13).matches("[0-9A-HJKMNP-TV-Z]{13}");
    }

    @Test
    void stringOrderIsNumericOrder() {
        OrderIdGenerator ids = new OrderIdGenerator(Clock.systemUTC(), 1);
        List<OrderId> generated = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            generated.add(ids.next(i % 16));
        }
        List<String> asStrings =
                generated.stream().map(OrderId::toString).sorted().toList();
        assertThat(asStrings)
                .isEqualTo(generated.stream().sorted().map(OrderId::toString).toList());
    }

    @Test
    void generatorIsUniqueAndIncreasingAcrossThreads() throws Exception {
        OrderIdGenerator ids = new OrderIdGenerator(Clock.systemUTC(), 7);
        Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<Future<?>> workers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) {
                workers.add(pool.submit(() -> {
                    long previous = 0;
                    for (int i = 0; i < 50_000; i++) {
                        long v = ids.next(3).value();
                        assertThat(v).isGreaterThan(previous); // increasing per thread
                        previous = v;
                        seen.add(v);
                    }
                }));
            }
            for (Future<?> w : workers) {
                w.get();
            }
        }
        assertThat(seen).hasSize(400_000);
    }

    @Test
    void sequenceExhaustionBorrowsTheNextMillisecond() {
        MutableClock clock =
                new MutableClock(Instant.parse("2026-11-06T12:00:00Z").toEpochMilli());
        OrderIdGenerator ids = new OrderIdGenerator(clock, 0);
        Set<Long> seen = new HashSet<>();
        OrderId last = null;
        for (int i = 0; i < 5_000; i++) { // five milliseconds' worth of ids in one frozen millisecond
            last = ids.next(0);
            assertThat(seen.add(last.value())).isTrue();
        }
        assertThat(last.createdAtEpochMillis()).isEqualTo(clock.millis() + 4);
    }

    @Test
    void clockGoingBackwardsNeverRepeatsAnId() {
        MutableClock clock =
                new MutableClock(Instant.parse("2026-11-06T12:00:00Z").toEpochMilli());
        OrderIdGenerator ids = new OrderIdGenerator(clock, 0);
        OrderId beforeStep = ids.next(5);

        clock.set(clock.millis() - 5); // NTP steps the clock back 5 ms
        OrderId afterStep = ids.next(5);

        assertThat(afterStep.value()).isGreaterThan(beforeStep.value());
        assertThat(afterStep.createdAtEpochMillis()).isEqualTo(beforeStep.createdAtEpochMillis());
    }

    private static final class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicLong millis;

        MutableClock(long millis) {
            this.millis = new java.util.concurrent.atomic.AtomicLong(millis);
        }

        void set(long value) {
            millis.set(value);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[] {null, "", "123", "0000000000000I", "ZZZZZZZZZZZZZ", "00000000000I0"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> OrderId.parse(bad));
        }
    }

    @Test
    void shardingIsStableAndSpreadsCustomers() {
        int[] perShard = new int[16];
        for (int i = 0; i < 160_000; i++) {
            perShard[
                    Shards.forUser(java.util
                            .UUID
                            .nameUUIDFromBytes(("user-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .toString())]++;
        }
        for (int count : perShard) {
            assertThat(count).isBetween(9_500, 10_500); // 10,000 expected per shard, within 5%
        }
        assertThat(Shards.forUser("5c08ee58-1f04-444b-8bc6-6af6f0f26493"))
                .isEqualTo(Shards.forUser("5c08ee58-1f04-444b-8bc6-6af6f0f26493"));
    }
}
