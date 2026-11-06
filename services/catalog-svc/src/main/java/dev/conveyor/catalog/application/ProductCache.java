package dev.conveyor.catalog.application;

import dev.conveyor.catalog.domain.Product;
import java.util.Optional;
import java.util.function.Function;

/** Read-through cache in front of the repository. Implementations decide the tiers. */
public interface ProductCache {

    /** Returns the product, calling {@code loader} only on a full miss. Empty means "no such SKU". */
    Optional<Product> get(String sku, Function<String, Optional<Product>> loader);

    /** Called after a successful write with the new state, so readers stop seeing the old one. */
    void written(Product product);
}
