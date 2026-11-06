package dev.conveyor.catalog.infrastructure.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import dev.conveyor.catalog.application.ProductCache;
import dev.conveyor.catalog.domain.Product;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 (Caffeine, per instance) over L2 (Redis, shared) over the database.
 *
 * <ul>
 *   <li><b>Stampede (cache breakdown).</b> L1 is an {@link AsyncCache}: concurrent requests for
 *       the same missing key share one in-flight future, so one instance sends at most one
 *       load per key to Redis and the database, however many requests arrive at once.
 *   <li><b>Penetration.</b> SKUs the Bloom filter has never seen are answered "absent" without
 *       touching Redis or the database. SKUs that pass the filter but do not exist (false
 *       positives, deleted products) get a short negative entry in both tiers.
 *   <li><b>Avalanche.</b> Every TTL is jittered, so entries written together expire apart.
 *   <li><b>Hot keys.</b> Caffeine's W-TinyLFU admission keeps frequently read SKUs in L1, so a
 *       hot SKU is served from process memory rather than from one Redis shard.
 *   <li><b>Consistency.</b> Writers store the new version in L2 through a version
 *       compare-and-set and publish it; every instance raises a per-SKU version floor and drops
 *       older L1 entries. A load that returns something below the floor is retried from the
 *       database, so an instance never re-caches a version older than one it has been told
 *       about. If a message is missed, staleness is bounded by the L1 TTL.
 *   <li><b>Degradation.</b> If Redis fails, reads fall through to the database.
 * </ul>
 */
public final class TwoLevelProductCache implements ProductCache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelProductCache.class);

    private final AsyncCache<String, Optional<Product>> l1;
    private final Cache<String, Long> versionFloors;
    private final RedisProductStore l2;
    private final SkuFilter skus;
    private final CacheProperties properties;
    private final CacheMetrics metrics;

    public TwoLevelProductCache(
            RedisProductStore l2,
            SkuFilter skus,
            CacheProperties properties,
            CacheMetrics metrics,
            MeterRegistry registry,
            Executor loadExecutor) {
        this.l2 = l2;
        this.skus = skus;
        this.properties = properties;
        this.metrics = metrics;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(properties.l1MaximumSize())
                .expireAfter(new JitteredExpiry(properties))
                .executor(loadExecutor)
                .recordStats()
                .buildAsync();
        // Must outlive any L1 entry it protects against, plus loads in flight.
        this.versionFloors = Caffeine.newBuilder()
                .maximumSize(properties.l1MaximumSize())
                .expireAfterWrite(properties.l1Ttl().multipliedBy(3))
                .build();
        CaffeineCacheMetrics.monitor(registry, l1, "catalog.l1");
    }

    @Override
    public Optional<Product> get(String sku, Function<String, Optional<Product>> loader) {
        CompletableFuture<Optional<Product>> future =
                l1.get(sku, (key, executor) -> CompletableFuture.supplyAsync(() -> load(key, loader), executor));
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Override
    public void written(Product product) {
        raiseFloor(product.sku(), product.version());
        l1.put(product.sku(), CompletableFuture.completedFuture(Optional.of(product)));
        try {
            l2.putIfNewer(product, Ttl.jittered(properties.l2Ttl(), properties.ttlJitter()));
            l2.publishWritten(product.sku(), product.version());
        } catch (RuntimeException e) {
            metrics.l2Error.increment();
            log.warn(
                    "could not propagate {} v{} to Redis; peers converge within the L1 TTL: {}",
                    product.sku(),
                    product.version(),
                    e.toString());
        }
    }

    /** A peer (or this instance) wrote {@code sku} at {@code version}. */
    void onWritten(String sku, long version) {
        raiseFloor(sku, version);
        CompletableFuture<Optional<Product>> cached = l1.getIfPresent(sku);
        if (cached == null) {
            return;
        }
        boolean upToDate = cached.isDone()
                && !cached.isCompletedExceptionally()
                && cached.join().map(p -> p.version() >= version).orElse(false);
        if (!upToDate) {
            l1.synchronous().invalidate(sku);
        }
    }

    private Optional<Product> load(String sku, Function<String, Optional<Product>> loader) {
        if (!skus.mightContain(sku)) {
            metrics.bloomReject.increment();
            return Optional.empty();
        }
        Optional<Product> loaded = loadBelowL1(sku, loader);
        // A write may have been announced while we were loading. Never cache an older version.
        if (loaded.isPresent() && loaded.get().version() < floor(sku)) {
            metrics.staleRejected.increment();
            loaded = fromDatabase(sku, loader, true);
        }
        return loaded;
    }

    private Optional<Product> loadBelowL1(String sku, Function<String, Optional<Product>> loader) {
        RedisProductStore.Lookup lookup;
        try {
            lookup = metrics.l2Latency.record(() -> l2.get(sku));
        } catch (RuntimeException e) {
            metrics.l2Error.increment();
            log.warn("Redis read failed for {}, falling back to the database: {}", sku, e.toString());
            return fromDatabase(sku, loader, false);
        }
        return switch (lookup) {
            case RedisProductStore.Lookup.Hit hit
            when hit.product().version() >= floor(sku) -> {
                metrics.l2Hit.increment();
                yield Optional.of(hit.product());
            }
            case RedisProductStore.Lookup.Hit stale -> {
                metrics.staleRejected.increment();
                yield fromDatabase(sku, loader, true);
            }
            case RedisProductStore.Lookup.Absent absent -> {
                metrics.l2Absent.increment();
                yield Optional.empty();
            }
            case RedisProductStore.Lookup.Miss miss -> fromDatabase(sku, loader, true);
        };
    }

    private Optional<Product> fromDatabase(String sku, Function<String, Optional<Product>> loader, boolean fillL2) {
        metrics.dbLoad.increment();
        Optional<Product> product = metrics.dbLatency.record(() -> loader.apply(sku));
        if (fillL2 && product != null) {
            try {
                if (product.isPresent()) {
                    l2.putIfNewer(product.get(), Ttl.jittered(properties.l2Ttl(), properties.ttlJitter()));
                } else {
                    l2.putAbsent(sku, Ttl.jittered(properties.l2NegativeTtl(), properties.ttlJitter()));
                }
            } catch (RuntimeException e) {
                metrics.l2Error.increment();
                log.warn("could not fill Redis for {}: {}", sku, e.toString());
            }
        }
        return product == null ? Optional.empty() : product;
    }

    private long floor(String sku) {
        Long floor = versionFloors.getIfPresent(sku);
        return floor == null ? 0 : floor;
    }

    private void raiseFloor(String sku, long version) {
        versionFloors.asMap().merge(sku, version, Math::max);
    }

    /** Present products live {@code l1Ttl}, absent ones {@code l1NegativeTtl}; both jittered. */
    private record JitteredExpiry(CacheProperties properties) implements Expiry<String, Optional<Product>> {

        @Override
        public long expireAfterCreate(String key, Optional<Product> value, long currentTime) {
            Duration base = value.isPresent() ? properties.l1Ttl() : properties.l1NegativeTtl();
            return Ttl.jittered(base, properties.ttlJitter()).toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Optional<Product> value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(String key, Optional<Product> value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
