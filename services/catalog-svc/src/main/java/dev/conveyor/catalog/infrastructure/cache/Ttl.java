package dev.conveyor.catalog.infrastructure.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TTL jitter against cache avalanches: keys written together (a bulk warm-up, a deploy, a
 * popular category page) must not all expire in the same second and hit the database at once.
 */
final class Ttl {

    private Ttl() {}

    /** {@code base} scaled by a uniform factor in [1 - jitter, 1 + jitter]. */
    static Duration jittered(Duration base, double jitter) {
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitter;
        return Duration.ofMillis(Math.max(1, Math.round(base.toMillis() * factor)));
    }
}
