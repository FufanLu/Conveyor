package dev.conveyor.gateway.overload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdaptiveLimitTest {

    private static final long MS = 1_000_000;

    @Test
    void steadyLatencyUnderFullUseLetsTheLimitGrow() {
        AdaptiveLimit limit = new AdaptiveLimit(20, 8, 1000, 1.5);
        for (int i = 0; i < 2_000; i++) {
            limit.sample(10 * MS, limit.limit()); // every slot busy, latency flat
        }
        assertThat(limit.limit()).isGreaterThan(100);
    }

    @Test
    void anIdleClientDoesNotInflateTheLimit() {
        AdaptiveLimit limit = new AdaptiveLimit(64, 8, 1000, 1.5);
        for (int i = 0; i < 2_000; i++) {
            limit.sample(10 * MS, 3); // 3 in flight out of 64: no evidence more would be fine
        }
        assertThat(limit.limit()).isEqualTo(64);
    }

    @Test
    void queueingDownstreamShrinksTheLimitBeforeAnythingTimesOut() {
        AdaptiveLimit limit = new AdaptiveLimit(200, 8, 1000, 1.5);
        for (int i = 0; i < 2_000; i++) {
            limit.sample(10 * MS, 200); // baseline: 10 ms
        }
        int before = limit.limit();
        for (int i = 0; i < 300; i++) {
            limit.sample(60 * MS, limit.limit()); // upstream queueing: 6x slower
        }
        assertThat(limit.limit()).isLessThan(before / 3);
    }

    @Test
    void theLimitRecoversWhenLatencyComesBack() {
        AdaptiveLimit limit = new AdaptiveLimit(200, 8, 1000, 1.5);
        for (int i = 0; i < 2_000; i++) {
            limit.sample(10 * MS, 200);
        }
        for (int i = 0; i < 300; i++) {
            limit.sample(60 * MS, limit.limit());
        }
        int low = limit.limit();
        for (int i = 0; i < 3_000; i++) {
            limit.sample(10 * MS, limit.limit());
        }
        assertThat(limit.limit()).isGreaterThan(low * 3);
    }
}
