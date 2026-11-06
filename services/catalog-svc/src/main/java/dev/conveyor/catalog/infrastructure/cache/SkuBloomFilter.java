package dev.conveyor.catalog.infrastructure.cache;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A Bloom filter over SKUs: "definitely not a product" vs "maybe a product". It stops cache
 * penetration, where requests for SKUs that do not exist miss every cache tier and land on
 * the database each time.
 *
 * <p>Sizing follows the standard formulas: m = -n ln p / (ln 2)^2 bits and k = (m / n) ln 2
 * hash functions. The k bit positions come from two 64-bit hashes combined as h1 + i * h2
 * (Kirsch and Mitzenmacher, 2006), which keeps the false-positive rate of k independent hashes
 * at the cost of two. Bits live in an {@link AtomicLongArray}, so {@link #add} is safe to call
 * while other threads query.
 */
public final class SkuBloomFilter {

    private final AtomicLongArray words;
    private final long bitCount;
    private final int hashCount;

    private SkuBloomFilter(long bitCount, int hashCount) {
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.words = new AtomicLongArray(Math.toIntExact((bitCount + 63) / 64));
    }

    public static SkuBloomFilter create(long expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0 || falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("need expectedInsertions > 0 and 0 < falsePositiveRate < 1");
        }
        long bits = optimalBits(expectedInsertions, falsePositiveRate);
        return new SkuBloomFilter(bits, optimalHashes(expectedInsertions, bits));
    }

    static long optimalBits(long n, double p) {
        return (long) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    static int optimalHashes(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    public void add(String sku) {
        long base = fnv1a64(sku);
        long h1 = mix64(base);
        long h2 = mix64(base ^ 0x9E37_79B9_7F4A_7C15L) | 1L;
        for (int i = 0; i < hashCount; i++) {
            long bit = Math.floorMod(h1 + i * h2, bitCount);
            int word = (int) (bit >>> 6);
            long mask = 1L << bit;
            words.accumulateAndGet(word, mask, (current, m) -> current | m);
        }
    }

    public boolean mightContain(String sku) {
        long base = fnv1a64(sku);
        long h1 = mix64(base);
        long h2 = mix64(base ^ 0x9E37_79B9_7F4A_7C15L) | 1L;
        for (int i = 0; i < hashCount; i++) {
            long bit = Math.floorMod(h1 + i * h2, bitCount);
            if ((words.get((int) (bit >>> 6)) & (1L << bit)) == 0) {
                return false;
            }
        }
        return true;
    }

    public long bitCount() {
        return bitCount;
    }

    public int hashCount() {
        return hashCount;
    }

    /** FNV-1a: cheap and well distributed in the high bits; mix64 fixes the low bits. */
    private static long fnv1a64(String s) {
        long hash = 0xcbf2_9ce4_8422_2325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x0000_0100_0000_01B3L;
        }
        return hash;
    }

    /** SplitMix64 finalizer: a bijective avalanche mix. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return z ^ (z >>> 31);
    }
}
