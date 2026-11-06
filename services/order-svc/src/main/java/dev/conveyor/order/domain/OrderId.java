package dev.conveyor.order.domain;

/**
 * A 64-bit, time-ordered order id that carries the shard of the customer who placed it.
 *
 * <pre>
 *  63      62 .................. 22   21 .. 18   17 ..... 10   9 ...... 0
 *  [0] [ 41 bits ms since 2026-01-01 ] [shard 4] [ worker 8 ] [ seq 10 ]
 * </pre>
 *
 * Orders are sharded by customer, so "my orders" is a single-shard query. Embedding the
 * customer's shard in the order id ("shard gene") makes a lookup by order id single-shard too,
 * without a global id-to-shard index. Ids sort by creation time, which keeps B-tree inserts at
 * the right edge of the index and makes keyset pagination by id the same as by time.
 *
 * <p>Externally the id is 13 characters of Crockford base32 (no I, L, O, U), which preserves
 * the numeric sort order as a string sort order.
 */
public record OrderId(long value) implements Comparable<OrderId> {

    public static final long EPOCH_MILLIS = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
    public static final int SHARD_BITS = 4;
    public static final int WORKER_BITS = 8;
    public static final int SEQUENCE_BITS = 10;
    public static final int SHARDS = 1 << SHARD_BITS;

    static final int SEQUENCE_SHIFT = 0;
    static final int WORKER_SHIFT = SEQUENCE_BITS;
    static final int SHARD_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    static final int TIME_SHIFT = SEQUENCE_BITS + WORKER_BITS + SHARD_BITS;

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 13; // ceil(64 / 5)

    public OrderId {
        if (value <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
    }

    public static OrderId of(long millisSinceEpoch, int shard, int worker, int sequence) {
        return new OrderId((millisSinceEpoch << TIME_SHIFT)
                | ((long) shard << SHARD_SHIFT)
                | ((long) worker << WORKER_SHIFT)
                | ((long) sequence << SEQUENCE_SHIFT));
    }

    public int shard() {
        return (int) ((value >>> SHARD_SHIFT) & (SHARDS - 1));
    }

    public int worker() {
        return (int) ((value >>> WORKER_SHIFT) & ((1 << WORKER_BITS) - 1));
    }

    public int sequence() {
        return (int) (value & ((1 << SEQUENCE_BITS) - 1));
    }

    public long createdAtEpochMillis() {
        return (value >>> TIME_SHIFT) + EPOCH_MILLIS;
    }

    /** 13-character Crockford base32. */
    @Override
    public String toString() {
        char[] out = new char[LENGTH];
        long v = value;
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (v & 31)];
            v >>>= 5;
        }
        return new String(out);
    }

    /** Parses the external form; throws IllegalArgumentException for anything else. */
    public static OrderId parse(String text) {
        if (text == null || text.length() != LENGTH) {
            throw new IllegalArgumentException("order id must be 13 characters");
        }
        long v = 0;
        for (int i = 0; i < LENGTH; i++) {
            int digit = digit(text.charAt(i));
            if (digit < 0 || (i == 0 && digit > 15)) { // 13 * 5 = 65 bits; the top char holds 4
                throw new IllegalArgumentException("not an order id: " + text);
            }
            v = (v << 5) | digit;
        }
        return new OrderId(v);
    }

    private static int digit(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int compareTo(OrderId other) {
        return Long.compare(value, other.value);
    }
}
