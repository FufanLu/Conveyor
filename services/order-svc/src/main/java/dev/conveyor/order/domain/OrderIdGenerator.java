package dev.conveyor.order.domain;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free Snowflake-style generator. State is one long, (millis << 10 | sequence), advanced
 * with compare-and-set, so ids from one generator are strictly increasing across threads.
 * If the 1,024 sequence numbers of a millisecond run out, the generator borrows the next
 * millisecond. If the wall clock steps backwards (NTP), it keeps counting from its last
 * millisecond instead of reusing ids.
 */
public final class OrderIdGenerator {

    private static final long MAX_SEQUENCE = (1L << OrderId.SEQUENCE_BITS) - 1;

    private final Clock clock;
    private final int worker;
    private final AtomicLong state = new AtomicLong();

    public OrderIdGenerator(Clock clock, int worker) {
        if (worker < 0 || worker >= (1 << OrderId.WORKER_BITS)) {
            throw new IllegalArgumentException("worker must be in [0, 255]");
        }
        this.clock = clock;
        this.worker = worker;
    }

    public OrderId next(int shard) {
        if (shard < 0 || shard >= OrderId.SHARDS) {
            throw new IllegalArgumentException("shard must be in [0, 15]");
        }
        while (true) {
            long now = clock.millis() - OrderId.EPOCH_MILLIS;
            long current = state.get();
            long lastMillis = current >>> OrderId.SEQUENCE_BITS;
            long lastSequence = current & MAX_SEQUENCE;
            long millis;
            long sequence;
            if (now > lastMillis) {
                millis = now;
                sequence = 0;
            } else if (lastSequence < MAX_SEQUENCE) {
                millis = lastMillis; // same millisecond, or the clock went backwards
                sequence = lastSequence + 1;
            } else {
                millis = lastMillis + 1; // sequence exhausted: borrow the next millisecond
                sequence = 0;
            }
            long next = (millis << OrderId.SEQUENCE_BITS) | sequence;
            if (state.compareAndSet(current, next)) {
                return OrderId.of(millis, shard, worker, (int) sequence);
            }
        }
    }
}
