package dev.conveyor.catalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** System of record for products (Postgres). */
public interface ProductRepository {

    Optional<Product> findBySku(String sku);

    /**
     * Compare-and-set price change: succeeds only if the stored version is still
     * {@code expectedVersion}. Returns the new state, or empty if the product is missing or the
     * version moved on.
     */
    Optional<Product> updatePrice(String sku, long expectedVersion, long newPriceCents);

    List<String> allSkus();

    /** Streams every product in SKU order, in pages, without holding them all in memory. */
    void forEachProduct(int pageSize, Consumer<List<Product>> page);

    long count();
}
