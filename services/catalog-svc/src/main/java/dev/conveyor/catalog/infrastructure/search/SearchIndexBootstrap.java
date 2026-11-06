package dev.conveyor.catalog.infrastructure.search;

import dev.conveyor.catalog.domain.ProductRepository;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the index and alias if missing, then backfills from Postgres when the index has
 * fewer documents than the table. Runs in the background: the service serves product reads
 * immediately, and search becomes complete when the backfill finishes.
 */
@Component
class SearchIndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    private final OpenSearchClient client;
    private final OpenSearchProductSearch search;
    private final ProductRepository repository;
    private final SearchProperties properties;

    SearchIndexBootstrap(
            OpenSearchClient client,
            OpenSearchProductSearch search,
            ProductRepository repository,
            SearchProperties properties) {
        this.client = client;
        this.search = search;
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("search-bootstrap").start(this::bootstrap);
    }

    void bootstrap() {
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                ensureIndex();
                if (properties.reindexOnStartup()) {
                    backfill();
                }
                return;
            } catch (IOException | RuntimeException e) {
                log.warn("search bootstrap attempt {} failed: {}", attempt, e.toString());
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("search bootstrap gave up; search requests will answer 503");
    }

    void ensureIndex() throws IOException {
        if (client.indices().exists(e -> e.index(properties.index())).value()) {
            return;
        }
        client.indices()
                .create(c -> c.index(properties.index())
                        .settings(s -> s.numberOfShards(1).numberOfReplicas(0))
                        .mappings(m -> m.properties("sku", p -> p.keyword(k -> k))
                                .properties(
                                        "title",
                                        p -> p.text(t -> t.fields("raw", f -> f.keyword(k -> k.ignoreAbove(256)))))
                                .properties("brand", p -> p.keyword(k -> k))
                                .properties("category", p -> p.keyword(k -> k))
                                .properties("priceCents", p -> p.long_(l -> l))
                                .properties("rating", p -> p.float_(f -> f))
                                .properties("version", p -> p.long_(l -> l)))
                        .aliases(properties.alias(), a -> a));
        log.info("created search index {} behind alias {}", properties.index(), properties.alias());
    }

    void backfill() throws IOException {
        long indexed = client.count(c -> c.index(properties.alias())).count();
        long rows = repository.count();
        if (indexed >= rows) {
            log.info("search index is complete: {} documents for {} products", indexed, rows);
            return;
        }
        long start = System.nanoTime();
        AtomicLong written = new AtomicLong();
        repository.forEachProduct(properties.bulkSize(), page -> {
            try {
                written.addAndGet(search.bulkIndex(page));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        log.info(
                "search backfill done: {} documents written ({} already present) in {} ms",
                written.get(),
                indexed,
                (System.nanoTime() - start) / 1_000_000);
    }
}
