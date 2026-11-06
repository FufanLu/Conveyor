package dev.conveyor.catalog.infrastructure.search;

import dev.conveyor.catalog.domain.Product;

/** What the search index stores per product. */
public record ProductDocument(
        String sku, String title, String brand, String category, long priceCents, double rating, long version) {

    static ProductDocument of(Product p) {
        return new ProductDocument(
                p.sku(), p.title(), p.brand(), p.category(), p.priceCents(), p.rating(), p.version());
    }
}
