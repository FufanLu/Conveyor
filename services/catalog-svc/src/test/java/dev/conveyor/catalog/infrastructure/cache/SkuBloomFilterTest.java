package dev.conveyor.catalog.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SkuBloomFilterTest {

    @Test
    void sizingFollowsTheStandardFormulas() {
        // n = 125,000 (100k SKUs + 25% headroom), p = 0.1%  ->  m ~ 1.8 M bits, k = 10
        assertThat(SkuBloomFilter.optimalBits(125_000, 0.001)).isEqualTo(1_797_199);
        assertThat(SkuBloomFilter.optimalHashes(125_000, 1_797_199)).isEqualTo(10);
    }

    @Test
    void neverForgetsAnInsertedSku() {
        SkuBloomFilter bloom = SkuBloomFilter.create(100_000, 0.001);
        for (int i = 0; i < 100_000; i++) {
            bloom.add(sku(i));
        }
        for (int i = 0; i < 100_000; i++) {
            assertThat(bloom.mightContain(sku(i))).isTrue();
        }
    }

    @Test
    void falsePositiveRateMatchesTheTargetAtCapacity() {
        SkuBloomFilter bloom = SkuBloomFilter.create(100_000, 0.001);
        for (int i = 0; i < 100_000; i++) {
            bloom.add(sku(i));
        }
        int falsePositives = 0;
        int probes = 1_000_000;
        for (int i = 0; i < probes; i++) {
            if (bloom.mightContain(sku(100_000_000 + i))) {
                falsePositives++;
            }
        }
        double rate = (double) falsePositives / probes;
        // Expected ~0.1%; allow sampling noise but catch a broken hash (which shows up as 10x or worse).
        assertThat(rate).isBetween(0.0005, 0.002);
    }

    @Test
    void rejectsNonsenseParameters() {
        assertThatIllegalArgumentException().isThrownBy(() -> SkuBloomFilter.create(0, 0.01));
        assertThatIllegalArgumentException().isThrownBy(() -> SkuBloomFilter.create(10, 1.0));
    }

    private static String sku(long i) {
        return String.format("B0%08X", (i * 2654435761L) & 0xFFFF_FFFFL);
    }
}
