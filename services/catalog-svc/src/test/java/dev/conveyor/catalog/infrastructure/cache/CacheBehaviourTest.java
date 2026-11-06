package dev.conveyor.catalog.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.catalog.CatalogTestcontainers;
import dev.conveyor.catalog.application.ProductCache;
import dev.conveyor.catalog.application.ProductCatalog;
import dev.conveyor.catalog.domain.Product;
import dev.conveyor.catalog.domain.ProductRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The two-level cache against real Redis and Postgres. Each test uses SKUs no other test reads. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(CatalogTestcontainers.class)
class CacheBehaviourTest {

    @Autowired
    private ProductCatalog catalog;

    @Autowired
    private ProductCache cache;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private RedisProductStore l2;

    @Autowired
    private SkuFilter skuFilter;

    @Autowired
    private CacheMetrics metrics;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void fiveHundredConcurrentReadsOfAColdSkuCauseOneDatabaseLoad() throws Exception {
        String sku = repository.allSkus().get(1500);
        redis.delete(RedisProductStore.KEY_PREFIX + sku);
        double loadsBefore = metrics.dbLoad.count();

        List<Optional<Product>> results = concurrently(500, () -> catalog.find(sku));

        assertThat(results)
                .allSatisfy(r -> assertThat(r)
                        .hasValueSatisfying(p -> assertThat(p.sku()).isEqualTo(sku)));
        assertThat(metrics.dbLoad.count() - loadsBefore).isEqualTo(1);
        assertThat(redis.hasKey(RedisProductStore.KEY_PREFIX + sku)).isTrue();
    }

    @Test
    void unknownSkusAreRejectedByTheBloomFilterWithoutTouchingRedisOrTheDatabase() {
        double loadsBefore = metrics.dbLoad.count();
        double rejectsBefore = metrics.bloomReject.count();
        int probes = 10_000;
        int passedFilter = 0;
        for (int i = 0; i < probes; i++) {
            String unknown = String.format("B0%08X", 0xF000_0000L + i); // seed SKUs never take this form
            if (skuFilter.mightContain(unknown)) {
                passedFilter++;
            }
            assertThat(catalog.find(unknown)).isEmpty();
        }

        // Only Bloom false positives reach the database (and then get a negative cache entry).
        double dbLoads = metrics.dbLoad.count() - loadsBefore;
        assertThat(dbLoads).isEqualTo(passedFilter);
        assertThat(metrics.bloomReject.count() - rejectsBefore).isEqualTo(probes - passedFilter);
        assertThat(passedFilter).isLessThan(probes / 100);
    }

    @Test
    void aSlowReaderCannotPutAnOlderVersionBackIntoRedis() {
        String sku = repository.allSkus().get(1600);
        Product v1 = repository.findBySku(sku).orElseThrow();
        Product v2 = withVersion(v1, v1.version() + 1);
        redis.delete(RedisProductStore.KEY_PREFIX + sku);

        assertThat(l2.putIfNewer(v2, Duration.ofMinutes(1))).isTrue(); // the writer
        assertThat(l2.putIfNewer(v1, Duration.ofMinutes(1))).isFalse(); // the slow reader, too late
        assertThat(l2.putAbsent(sku, Duration.ofMinutes(1))).isFalse(); // a negative entry cannot win either

        assertThat(l2.get(sku)).isEqualTo(new RedisProductStore.Lookup.Hit(v2));
    }

    @Test
    void anAnnouncedNewerVersionForcesAReloadPastAStaleRedisEntry() {
        String sku = repository.allSkus().get(1700);
        Product v1 = catalog.find(sku).orElseThrow(); // now in L1 and Redis at v1
        // Another instance updates the database and announces v2, but its Redis write is lost.
        Product v2 =
                repository.updatePrice(sku, v1.version(), v1.priceCents() + 100).orElseThrow();
        double staleBefore = metrics.staleRejected.count();

        ((TwoLevelProductCache) cache).onWritten(sku, v2.version());

        assertThat(catalog.find(sku)).hasValue(v2);
        assertThat(metrics.staleRejected.count() - staleBefore).isEqualTo(1);
        assertThat(l2.get(sku)).isEqualTo(new RedisProductStore.Lookup.Hit(v2));
    }

    private static Product withVersion(Product p, long version) {
        return new Product(
                p.sku(),
                p.title(),
                p.brand(),
                p.category(),
                p.priceCents(),
                p.currency(),
                p.rating(),
                version,
                Instant.now());
    }

    private static <T> List<T> concurrently(int n, java.util.concurrent.Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
