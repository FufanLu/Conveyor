package dev.conveyor.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A sellable product. {@code version} increases by one on every change and is the basis of
 * every concurrency decision downstream: HTTP ETags, cache compare-and-set, and external
 * versioning in the search index.
 */
public record Product(
        String sku,
        String title,
        String brand,
        String category,
        long priceCents,
        String currency,
        double rating,
        long version,
        Instant updatedAt) {

    /** "B0" followed by eight upper-case hex digits, e.g. B0A1B2C3D4. */
    public static final Pattern SKU_FORMAT = Pattern.compile("B0[0-9A-F]{8}");

    public Product {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(brand, "brand");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (priceCents < 0) {
            throw new IllegalArgumentException("priceCents must be >= 0");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    public static boolean isValidSku(String candidate) {
        return candidate != null && SKU_FORMAT.matcher(candidate).matches();
    }
}
