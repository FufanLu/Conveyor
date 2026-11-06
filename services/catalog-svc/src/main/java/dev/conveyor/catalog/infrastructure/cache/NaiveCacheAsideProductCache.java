package dev.conveyor.catalog.infrastructure.cache;

import dev.conveyor.catalog.application.ProductCache;
import dev.conveyor.catalog.domain.Product;
import java.util.Optional;
import java.util.function.Function;

/**
 * Textbook cache-aside on Redis alone: no L1, no request coalescing, no Bloom filter. Exists
 * only so experiments can show what the two-level cache adds (mode NAIVE).
 */
final class NaiveCacheAsideProductCache implements ProductCache {

    private final RedisProductStore l2;
    private final CacheProperties properties;
    private final CacheMetrics metrics;

    NaiveCacheAsideProductCache(RedisProductStore l2, CacheProperties properties, CacheMetrics metrics) {
        this.l2 = l2;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public Optional<Product> get(String sku, Function<String, Optional<Product>> loader) {
        return switch (metrics.l2Latency.record(() -> l2.get(sku))) {
            case RedisProductStore.Lookup.Hit hit -> {
                metrics.l2Hit.increment();
                yield Optional.of(hit.product());
            }
            case RedisProductStore.Lookup.Absent absent -> {
                metrics.l2Absent.increment();
                yield Optional.empty();
            }
            case RedisProductStore.Lookup.Miss miss -> loadAndFill(sku, loader);
        };
    }

    private Optional<Product> loadAndFill(String sku, Function<String, Optional<Product>> loader) {
        metrics.dbLoad.increment();
        Optional<Product> product = metrics.dbLatency.record(() -> loader.apply(sku));
        if (product != null && product.isPresent()) {
            l2.putIfNewer(product.get(), Ttl.jittered(properties.l2Ttl(), properties.ttlJitter()));
        } else {
            l2.putAbsent(sku, Ttl.jittered(properties.l2NegativeTtl(), properties.ttlJitter()));
        }
        return product == null ? Optional.empty() : product;
    }

    @Override
    public void written(Product product) {
        l2.putIfNewer(product, Ttl.jittered(properties.l2Ttl(), properties.ttlJitter()));
    }
}
