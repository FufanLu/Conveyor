package dev.conveyor.order.infrastructure.projection;

import dev.conveyor.order.application.OrderSearch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/** Operations queries ("PLACED orders containing SKU x since 09:00") against the read model. */
@Component
class OpenSearchOrderSearch implements OrderSearch, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchOrderSearch.class);

    private final OpenSearchClient client;
    private final OrderSearchProperties properties;
    private final KafkaListenerEndpointRegistry listeners;

    OpenSearchOrderSearch(
            OpenSearchClient client, OrderSearchProperties properties, KafkaListenerEndpointRegistry listeners) {
        this.client = client;
        this.properties = properties;
        this.listeners = listeners;
    }

    @Override
    public Result search(Query query) {
        try {
            SearchResponse<OrderDocument> response = client.search(
                    s -> s.index(properties.alias())
                            .size(query.size())
                            .trackTotalHits(t -> t.enabled(true))
                            .sort(o -> o.field(f -> f.field("placedAt").order(SortOrder.Desc)))
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.matchAll(all -> all));
                                if (query.status() != null) {
                                    b.filter(f -> f.term(t -> t.field("status").value(FieldValue.of(query.status()))));
                                }
                                if (query.sku() != null) {
                                    b.filter(f -> f.term(t -> t.field("skus").value(FieldValue.of(query.sku()))));
                                }
                                if (query.placedAfter() != null) {
                                    b.filter(f -> f.range(r -> r.field("placedAt")
                                            .gte(JsonData.of(query.placedAfter().toString()))));
                                }
                                return b;
                            })),
                    OrderDocument.class);
            List<Summary> orders = response.hits().hits().stream()
                    .map(h -> h.source())
                    .filter(java.util.Objects::nonNull)
                    .map(d -> new Summary(
                            d.orderId(),
                            d.userId(),
                            d.status(),
                            d.totalCents(),
                            d.lines(),
                            Instant.parse(d.placedAt()),
                            d.version()))
                    .toList();
            long total = response.hits().total() == null
                    ? orders.size()
                    : response.hits().total().value();
            return new Result(total, orders);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates the index and alias, then starts the projection. If the projection wrote first,
     * OpenSearch would auto-create the index with dynamic mappings (SKUs as analysed text) and
     * no alias.
     */
    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("order-search-bootstrap").start(() -> {
            for (int attempt = 1; attempt <= 30; attempt++) {
                try {
                    if (!client.indices()
                            .exists(e -> e.index(properties.index()))
                            .value()) {
                        client.indices()
                                .create(c -> c.index(properties.index())
                                        .settings(st -> st.numberOfShards(1).numberOfReplicas(0))
                                        .mappings(m -> m.properties("orderId", p -> p.keyword(k -> k))
                                                .properties("userId", p -> p.keyword(k -> k))
                                                .properties("status", p -> p.keyword(k -> k))
                                                .properties("totalCents", p -> p.long_(l -> l))
                                                .properties("currency", p -> p.keyword(k -> k))
                                                .properties("shipToRegion", p -> p.keyword(k -> k))
                                                .properties("skus", p -> p.keyword(k -> k))
                                                .properties("lines", p -> p.integer(n -> n))
                                                .properties("placedAt", p -> p.date(d -> d))
                                                .properties("version", p -> p.long_(l -> l)))
                                        .aliases(properties.alias(), a -> a));
                        log.info(
                                "created order search index {} behind alias {}",
                                properties.index(),
                                properties.alias());
                    }
                    var projection = listeners.getListenerContainer(OrderSearchProjection.LISTENER_ID);
                    if (projection != null) {
                        projection.start();
                    }
                    return;
                } catch (IOException | RuntimeException e) {
                    log.warn("order search bootstrap attempt {} failed: {}", attempt, e.toString());
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }
}
