package dev.conveyor.catalog.application;

import dev.conveyor.catalog.domain.Product;
import java.util.List;
import java.util.Map;

/** Full-text search and facets over the catalog: a read model kept next to the system of record. */
public interface ProductSearch {

    Result search(Query query);

    /** Index (or re-index) one product. Asynchronous; an older version never overwrites a newer one. */
    void index(Product product);

    record Query(
            String text, String category, String brand, Long minPriceCents, Long maxPriceCents, int page, int size) {}

    record Result(long total, long tookMillis, List<Hit> hits, Map<String, List<Facet>> facets) {}

    record Hit(String sku, String title, String brand, String category, long priceCents, double rating, double score) {}

    record Facet(String value, long count) {}
}
