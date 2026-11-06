package dev.conveyor.catalog.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LongSummaryStatistics;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TtlTest {

    @Test
    void jitterStaysInBoundsAndSpreadsExpiries() {
        LongSummaryStatistics stats = IntStream.range(0, 100_000)
                .mapToLong(i -> Ttl.jittered(Duration.ofMinutes(10), 0.2).toMillis())
                .summaryStatistics();

        assertThat(stats.getMin()).isGreaterThanOrEqualTo(480_000); // 8 min
        assertThat(stats.getMax()).isLessThanOrEqualTo(720_000); // 12 min
        assertThat(stats.getMax() - stats.getMin()).isGreaterThan(230_000); // actually spread out
        assertThat(stats.getAverage()).isBetween(595_000.0, 605_000.0);
    }
}
