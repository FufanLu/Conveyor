package dev.conveyor.tracking.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.tracking.config.TrackingProperties;
import dev.conveyor.tracking.domain.TrackingRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class PositionCoalescerTest {

    /** Records the writes instead of calling DynamoDB. */
    static final class RecordingStore extends ShipmentStore {
        final List<Long> written = new CopyOnWriteArrayList<>();

        RecordingStore() {
            super(null, null);
        }

        @Override
        public CompletableFuture<Boolean> movePosition(TrackingRecord r) {
            written.add(r.seq());
            return CompletableFuture.completedFuture(true);
        }
    }

    private static TrackingProperties every(Duration interval) {
        return new TrackingProperties(
                URI.create("http://x"),
                "us-east-1",
                "e",
                "s",
                Duration.ofDays(1),
                Duration.ofMillis(500),
                16,
                interval);
    }

    private static TrackingRecord at(long seq) {
        return new TrackingRecord("S1", seq, "c", Instant.EPOCH, 1.0, 2.0, 1f, null, null);
    }

    @Test
    void insideTheIntervalOnlyTheNewestWaitsAndIsFlushedWhenTheIntervalIsUp() throws Exception {
        RecordingStore store = new RecordingStore();
        var coalescer = new PositionCoalescer(store, every(Duration.ofMillis(300)), new SimpleMeterRegistry());

        coalescer.offer(List.of(at(1))); // first sight: written at once
        coalescer.offer(List.of(at(2))); // parked
        coalescer.offer(List.of(at(4))); // replaces 2
        coalescer.offer(List.of(at(3))); // older than the parked 4: ignored
        coalescer.flush(); // interval not up yet
        assertThat(store.written).containsExactly(1L);
        assertThat(coalescer.writesPending()).isEqualTo(1);

        Thread.sleep(350);
        coalescer.flush();
        assertThat(store.written).containsExactly(1L, 4L);
        assertThat(coalescer.writesPending()).isZero();
    }

    @Test
    void aZeroIntervalWritesEveryBatch() {
        RecordingStore store = new RecordingStore();
        var coalescer = new PositionCoalescer(store, every(Duration.ZERO), new SimpleMeterRegistry());

        coalescer.offer(List.of(at(1)));
        coalescer.offer(List.of(at(2)));

        assertThat(store.written).containsExactly(1L, 2L);
    }
}
