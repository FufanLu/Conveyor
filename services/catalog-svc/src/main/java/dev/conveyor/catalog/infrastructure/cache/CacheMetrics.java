package dev.conveyor.catalog.infrastructure.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * What the read path did, as counters every cache mode shares, so experiments compare like
 * with like: {@code catalog_cache_requests_total{outcome=...}}, plus latency per tier.
 */
@Component
public class CacheMetrics {

    final Counter l2Hit;
    final Counter l2Absent;
    final Counter dbLoad;
    final Counter bloomReject;
    final Counter staleRejected;
    final Counter l2Error;
    final Timer l2Latency;
    final Timer dbLatency;

    public CacheMetrics(MeterRegistry registry) {
        this.l2Hit = outcome(registry, "l2_hit");
        this.l2Absent = outcome(registry, "l2_absent");
        this.dbLoad = outcome(registry, "db_load");
        this.bloomReject = outcome(registry, "bloom_reject");
        this.staleRejected = outcome(registry, "stale_rejected");
        this.l2Error = outcome(registry, "l2_error");
        this.l2Latency = tier(registry, "l2");
        this.dbLatency = tier(registry, "db");
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("catalog.cache.requests")
                .description("Product read path outcomes below L1")
                .tag("outcome", outcome)
                .register(registry);
    }

    private static Timer tier(MeterRegistry registry, String tier) {
        return Timer.builder("catalog.cache.tier.latency")
                .description("Latency of a product lookup in one tier")
                .tag("tier", tier)
                .publishPercentileHistogram()
                .register(registry);
    }
}
