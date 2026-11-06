package dev.conveyor.catalog.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.catalog.domain.Product;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The cache logic alone, with an in-memory stand-in for Redis that can be made to fail. */
class TwoLevelProductCacheTest {

    private static final String SKU = "B0ABCDEF01";

    private final FakeRedis redis = new FakeRedis();
    private final SkuFilter filter = new SkuFilter();
    private final CacheMetrics metrics = new CacheMetrics(new SimpleMeterRegistry());
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final TwoLevelProductCache cache =
            new TwoLevelProductCache(redis, filter, properties(), metrics, new SimpleMeterRegistry(), executor);

    @AfterEach
    void stop() {
        executor.close();
    }

    @Test
    void coalescesConcurrentMissesIntoOneLoad() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        Function<String, Optional<Product>> slowLoader = sku -> {
            loads.incrementAndGet();
            await(release);
            return Optional.of(product(1));
        };

        List<Future<Optional<Product>>> callers = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                callers.add(pool.submit(() -> cache.get(SKU, slowLoader)));
            }
            Thread.sleep(100); // let every caller join the in-flight load
            release.countDown();
            for (Future<Optional<Product>> caller : callers) {
                assertThat(caller.get()).hasValue(product(1));
            }
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void servesFromTheDatabaseWhenRedisIsDown() {
        redis.failing = true;

        assertThat(cache.get(SKU, sku -> Optional.of(product(3)))).hasValue(product(3));
        assertThat(metrics.l2Error.count()).isEqualTo(1);
        assertThat(metrics.dbLoad.count()).isEqualTo(1);
    }

    @Test
    void cachesAbsenceSoRepeatedMissesStayOffTheDatabase() {
        AtomicInteger loads = new AtomicInteger();
        Function<String, Optional<Product>> loader = sku -> {
            loads.incrementAndGet();
            return Optional.empty();
        };

        assertThat(cache.get(SKU, loader)).isEmpty();
        assertThat(cache.get(SKU, loader)).isEmpty();

        assertThat(loads).hasValue(1);
        assertThat(redis.lookup(SKU)).isEqualTo(new RedisProductStore.Lookup.Absent());
    }

    @Test
    void bloomFilterRejectionNeverReachesRedisOrLoader() {
        SkuBloomFilter bloom = SkuBloomFilter.create(1_000, 0.001);
        bloom.add("B000000001");
        filter.install(bloom);

        assertThat(cache.get(SKU, sku -> {
                    throw new AssertionError("loader must not run");
                }))
                .isEmpty();
        assertThat(metrics.bloomReject.count()).isEqualTo(1);
        assertThat(redis.reads).hasValue(0);
    }

    @Test
    void redisEntryOlderThanAnAnnouncedVersionIsIgnored() {
        redis.putIfNewer(product(4), Duration.ofMinutes(1));
        cache.onWritten(SKU, 5);

        assertThat(cache.get(SKU, sku -> Optional.of(product(5)))).hasValue(product(5));
        assertThat(metrics.staleRejected.count()).isEqualTo(1);
    }

    @Test
    void writtenUpdatesLocalCacheAndRedisAndAnnouncesTheVersion() {
        cache.written(product(9));

        assertThat(cache.get(SKU, sku -> {
                    throw new AssertionError("L1 should already hold v9");
                }))
                .hasValue(product(9));
        assertThat(redis.lookup(SKU)).isEqualTo(new RedisProductStore.Lookup.Hit(product(9)));
        assertThat(redis.published).containsExactly(SKU + ":9");
    }

    private static Product product(long version) {
        return new Product(SKU, "Test", "Brand", "Books", 1999, "USD", 4.5, version, Instant.EPOCH);
    }

    private static CacheProperties properties() {
        return new CacheProperties(
                CacheProperties.Mode.TWO_LEVEL,
                1_000,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ofMinutes(10),
                Duration.ofSeconds(60),
                0.2,
                0.001);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Version-checked in-memory L2 with a failure switch. */
    private static final class FakeRedis extends RedisProductStore {

        private final java.util.Map<String, Product> products = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<String> absent = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final List<String> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile boolean failing;

        FakeRedis() {
            super(null, null);
        }

        Lookup lookup(String sku) {
            Product p = products.get(sku);
            if (p != null) {
                return new Lookup.Hit(p);
            }
            return absent.contains(sku) ? new Lookup.Absent() : new Lookup.Miss();
        }

        @Override
        public Lookup get(String sku) {
            reads.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("redis down");
            }
            return lookup(sku);
        }

        @Override
        public boolean putIfNewer(Product product, Duration ttl) {
            if (failing) {
                throw new IllegalStateException("redis down");
            }
            Product current = products.get(product.sku());
            if (current != null && current.version() >= product.version()) {
                return false;
            }
            products.put(product.sku(), product);
            absent.remove(product.sku());
            return true;
        }

        @Override
        public boolean putAbsent(String sku, Duration ttl) {
            if (products.containsKey(sku)) {
                return false;
            }
            return absent.add(sku);
        }

        @Override
        public void publishWritten(String sku, long version) {
            published.add(sku + ":" + version);
        }
    }
}
