package dev.conveyor.catalog.infrastructure.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cache tuning. {@code mode} exists for experiments: TWO_LEVEL is the real design; NAIVE is
 * textbook cache-aside on Redis alone; NONE goes straight to the database.
 */
@ConfigurationProperties("conveyor.catalog.cache")
public record CacheProperties(
        @DefaultValue("TWO_LEVEL") Mode mode,
        @DefaultValue("50000") long l1MaximumSize,
        @DefaultValue("10s") Duration l1Ttl,
        @DefaultValue("2s") Duration l1NegativeTtl,
        @DefaultValue("10m") Duration l2Ttl,
        @DefaultValue("60s") Duration l2NegativeTtl,
        @DefaultValue("0.2") double ttlJitter,
        @DefaultValue("0.001") double bloomFalsePositiveRate) {

    public enum Mode {
        TWO_LEVEL,
        NAIVE,
        NONE
    }
}
