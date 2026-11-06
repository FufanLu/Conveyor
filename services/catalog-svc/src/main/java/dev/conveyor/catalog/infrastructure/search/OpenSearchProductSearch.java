package dev.conveyor.catalog.infrastructure.search;

import dev.conveyor.catalog.application.ProductSearch;
import dev.conveyor.catalog.application.SearchUnavailableException;
import dev.conveyor.catalog.domain.Product;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenSearch-backed search. Every index operation uses external versioning with the product
 * version, so OpenSearch itself rejects an update that arrives after a newer one; async
 * indexing and retries therefore cannot move the index backwards.
 */
@Component
public class OpenSearchProductSearch implements ProductSearch, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchProductSearch.class);
    private static final int VERSION_CONFLICT = 409;

    private final OpenSearchClient client;
    private final SearchProperties properties;
    private final ExecutorService indexer = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("search-index-", 0).factory());

    public OpenSearchProductSearch(OpenSearchClient client, SearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Result search(Query query) {
        try {
            SearchResponse<ProductDocument> response = client.search(
                    s -> s.index(properties.alias())
                            .query(toQuery(query))
                            .from(query.page() * query.size())
                            .size(query.size())
                            .trackTotalHits(t -> t.enabled(true))
                            .aggregations(
                                    "category",
                                    a -> a.terms(t -> t.field("category").size(10)))
                            .aggregations(
                                    "brand", a -> a.terms(t -> t.field("brand").size(12))),
                    ProductDocument.class);
            List<ProductSearch.Hit> hits = new ArrayList<>();
            for (org.opensearch.client.opensearch.core.search.Hit<ProductDocument> hit :
                    response.hits().hits()) {
                ProductDocument d = hit.source();
                if (d != null) {
                    hits.add(new ProductSearch.Hit(
                            d.sku(),
                            d.title(),
                            d.brand(),
                            d.category(),
                            d.priceCents(),
                            d.rating(),
                            hit.score() == null ? 0 : hit.score()));
                }
            }
            Map<String, List<Facet>> facets = new LinkedHashMap<>();
            response.aggregations().forEach((name, aggregate) -> facets.put(name, facets(aggregate)));
            long total = response.hits().total() == null
                    ? hits.size()
                    : response.hits().total().value();
            return new Result(total, response.took(), hits, facets);
        } catch (IOException | OpenSearchException e) {
            throw new SearchUnavailableException("search is unavailable", e);
        }
    }

    @Override
    public void index(Product product) {
        indexer.execute(() -> indexWithRetry(product));
    }

    /** Bulk-indexes one page of products. Returns how many documents were (re)written. */
    int bulkIndex(List<Product> products) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Product p : products) {
            bulk.operations(op -> op.index(i -> i.index(properties.index())
                    .id(p.sku())
                    .document(ProductDocument.of(p))
                    .version(p.version())
                    .versionType(VersionType.External)));
        }
        BulkResponse response = client.bulk(bulk.build());
        int written = 0;
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                written++;
            } else if (item.status() != VERSION_CONFLICT) {
                log.warn("bulk index failed for {}: {}", item.id(), item.error().reason());
            }
        }
        return written;
    }

    private void indexWithRetry(Product product) {
        Duration backoff = Duration.ofMillis(200);
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                client.index(i -> i.index(properties.index())
                        .id(product.sku())
                        .document(ProductDocument.of(product))
                        .version(product.version())
                        .versionType(VersionType.External));
                return;
            } catch (OpenSearchException e) {
                if (e.status() == VERSION_CONFLICT) {
                    return; // a newer version is already indexed
                }
                log.warn("index {} v{} attempt {} failed: {}", product.sku(), product.version(), attempt, e.toString());
            } catch (IOException e) {
                log.warn("index {} v{} attempt {} failed: {}", product.sku(), product.version(), attempt, e.toString());
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = backoff.multipliedBy(4);
        }
        log.error("giving up indexing {} v{}; the startup reindex repairs it", product.sku(), product.version());
    }

    private static org.opensearch.client.opensearch._types.query_dsl.Query toQuery(Query query) {
        return org.opensearch.client.opensearch._types.query_dsl.Query.of(q -> q.bool(b -> {
            if (query.text() == null || query.text().isBlank()) {
                b.must(m -> m.matchAll(all -> all));
            } else {
                b.must(m -> m.multiMatch(mm -> mm.query(query.text()).fields("title^3", "brand^2", "category")));
            }
            if (query.category() != null) {
                b.filter(f -> f.term(t -> t.field("category").value(FieldValue.of(query.category()))));
            }
            if (query.brand() != null) {
                b.filter(f -> f.term(t -> t.field("brand").value(FieldValue.of(query.brand()))));
            }
            if (query.minPriceCents() != null || query.maxPriceCents() != null) {
                b.filter(f -> f.range(r -> {
                    r.field("priceCents");
                    if (query.minPriceCents() != null) {
                        r.gte(org.opensearch.client.json.JsonData.of(query.minPriceCents()));
                    }
                    if (query.maxPriceCents() != null) {
                        r.lte(org.opensearch.client.json.JsonData.of(query.maxPriceCents()));
                    }
                    return r;
                }));
            }
            return b;
        }));
    }

    private static List<Facet> facets(Aggregate aggregate) {
        List<Facet> facets = new ArrayList<>();
        if (aggregate.isSterms()) {
            for (StringTermsBucket bucket : aggregate.sterms().buckets().array()) {
                facets.add(new Facet(bucket.key(), bucket.docCount()));
            }
        }
        return facets;
    }

    @Override
    public void close() {
        indexer.close();
    }
}
