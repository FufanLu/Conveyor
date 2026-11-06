package dev.conveyor.catalog.infrastructure.cache;

import dev.conveyor.catalog.domain.ProductRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Builds the SKU Bloom filter from the database before the service reports ready. Sized with
 * 25% headroom over today's SKU count so new products (M2+) do not push the false-positive
 * rate up straight away.
 */
@Component
class SkuFilterLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkuFilterLoader.class);

    private final ProductRepository repository;
    private final SkuFilter filter;
    private final CacheProperties properties;

    SkuFilterLoader(ProductRepository repository, SkuFilter filter, CacheProperties properties) {
        this.repository = repository;
        this.filter = filter;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.nanoTime();
        List<String> skus = repository.allSkus();
        SkuBloomFilter bloom =
                SkuBloomFilter.create(Math.max(1_000, skus.size() * 5L / 4), properties.bloomFalsePositiveRate());
        skus.forEach(bloom::add);
        filter.install(bloom);
        log.info(
                "SKU Bloom filter ready: {} SKUs, {} bits ({} KiB), {} hashes, target false-positive rate {}, built in {} ms",
                skus.size(),
                bloom.bitCount(),
                bloom.bitCount() / 8 / 1024,
                bloom.hashCount(),
                properties.bloomFalsePositiveRate(),
                (System.nanoTime() - start) / 1_000_000);
    }
}
