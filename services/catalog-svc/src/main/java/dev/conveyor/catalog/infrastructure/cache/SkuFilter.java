package dev.conveyor.catalog.infrastructure.cache;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Holds the current SKU Bloom filter. Until the first filter is installed it fails open
 * (everything "might exist"), so a service that is still building its filter answers
 * correctly, just without penetration protection.
 */
@Component
public class SkuFilter {

    private final AtomicReference<SkuBloomFilter> current = new AtomicReference<>();

    public boolean mightContain(String sku) {
        SkuBloomFilter filter = current.get();
        return filter == null || filter.mightContain(sku);
    }

    void install(SkuBloomFilter filter) {
        current.set(filter);
    }

    boolean isReady() {
        return current.get() != null;
    }
}
