package dev.conveyor.catalog.application;

import dev.conveyor.catalog.domain.Product;
import dev.conveyor.catalog.domain.ProductRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Use cases of the catalog. Knows the ports, not the technologies behind them. */
@Service
public class ProductCatalog {

    private final ProductRepository repository;
    private final ProductCache cache;
    private final ProductSearch search;

    public ProductCatalog(ProductRepository repository, ProductCache cache, ProductSearch search) {
        this.repository = repository;
        this.cache = cache;
        this.search = search;
    }

    public Optional<Product> find(String sku) {
        return cache.get(sku, repository::findBySku);
    }

    /**
     * Conditional update: the database row is the arbiter (UPDATE ... WHERE version = ?). On
     * success the cache is updated with the new version (not just invalidated, so a hot SKU
     * does not fall back to the database right after a price change) and the search index
     * follows asynchronously.
     */
    public PriceUpdate updatePrice(String sku, long expectedVersion, long newPriceCents) {
        Optional<Product> updated = repository.updatePrice(sku, expectedVersion, newPriceCents);
        if (updated.isPresent()) {
            cache.written(updated.get());
            search.index(updated.get());
            return new PriceUpdate.Updated(updated.get());
        }
        return repository
                .findBySku(sku)
                .<PriceUpdate>map(PriceUpdate.VersionMismatch::new)
                .orElseGet(PriceUpdate.NotFound::new);
    }

    public ProductSearch.Result search(ProductSearch.Query query) {
        return search.search(query);
    }
}
