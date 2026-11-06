package dev.conveyor.catalog.api;

import dev.conveyor.catalog.domain.Product;
import java.time.Instant;

/** Public representation. {@code version} is also sent as the ETag. */
public record ProductResponse(
        String sku,
        String title,
        String brand,
        String category,
        Price price,
        double rating,
        long version,
        Instant updatedAt) {

    public record Price(long amountCents, String currency) {}

    static ProductResponse of(Product p) {
        return new ProductResponse(
                p.sku(),
                p.title(),
                p.brand(),
                p.category(),
                new Price(p.priceCents(), p.currency()),
                p.rating(),
                p.version(),
                p.updatedAt());
    }
}
