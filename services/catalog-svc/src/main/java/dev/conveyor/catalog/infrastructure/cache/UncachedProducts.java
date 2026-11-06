package dev.conveyor.catalog.infrastructure.cache;

import dev.conveyor.catalog.application.ProductCache;
import dev.conveyor.catalog.domain.Product;
import java.util.Optional;
import java.util.function.Function;

/** No cache at all (mode NONE): the baseline every experiment starts from. */
final class UncachedProducts implements ProductCache {

    private final CacheMetrics metrics;

    UncachedProducts(CacheMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Optional<Product> get(String sku, Function<String, Optional<Product>> loader) {
        metrics.dbLoad.increment();
        Optional<Product> product = metrics.dbLatency.record(() -> loader.apply(sku));
        return product == null ? Optional.empty() : product;
    }

    @Override
    public void written(Product product) {}
}
