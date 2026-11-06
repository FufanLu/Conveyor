package dev.conveyor.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.catalog.CatalogTestcontainers;
import dev.conveyor.catalog.HttpTestClient;
import dev.conveyor.catalog.application.ProductCatalog;
import dev.conveyor.catalog.application.ProductSearch;
import dev.conveyor.catalog.domain.Product;
import dev.conveyor.catalog.domain.ProductRepository;
import dev.conveyor.catalog.infrastructure.search.ProductDocument;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(CatalogTestcontainers.class)
class SearchApiTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ProductCatalog catalog;

    @Autowired
    private ProductRepository repository;

    private HttpTestClient http;

    @BeforeEach
    void waitForBackfill() {
        http = new HttpTestClient(environment);
        long rows = repository.count();
        eventually(Duration.ofSeconds(60), () -> {
            try {
                return catalog.search(new ProductSearch.Query(null, null, null, null, null, 0, 1))
                                .total()
                        == rows;
            } catch (RuntimeException notYet) {
                return false;
            }
        });
    }

    @Test
    void fullTextSearchWithFacets() throws Exception {
        HttpResponse<String> response = http.get("/v1/search?q=espresso%20kettle&size=5");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"hits\":[")
                .contains("\"facets\":{\"category\":[")
                .contains("\"brand\":[");
    }

    @Test
    void filtersNarrowTheResult() {
        ProductSearch.Result all = catalog.search(new ProductSearch.Query(null, null, null, null, null, 0, 1));
        ProductSearch.Result books = catalog.search(new ProductSearch.Query(null, "Books", null, null, null, 0, 100));
        ProductSearch.Result cheapBooks =
                catalog.search(new ProductSearch.Query(null, "Books", null, null, 1_000L, 0, 100));

        assertThat(books.total()).isPositive().isLessThan(all.total());
        assertThat(books.hits()).allSatisfy(h -> assertThat(h.category()).isEqualTo("Books"));
        assertThat(cheapBooks.total()).isLessThan(books.total());
        assertThat(cheapBooks.hits()).allSatisfy(h -> assertThat(h.priceCents()).isLessThanOrEqualTo(1_000));
        // The category facet over all products adds up to the total.
        assertThat(all.facets().get("category").stream()
                        .mapToLong(ProductSearch.Facet::count)
                        .sum())
                .isEqualTo(all.total());
    }

    @Test
    void priceChangeReachesTheIndexAndOlderVersionsCannotOverwriteIt() {
        String sku = repository.allSkus().get(1800);
        Product before = repository.findBySku(sku).orElseThrow();

        Product after = ((dev.conveyor.catalog.application.PriceUpdate.Updated)
                        catalog.updatePrice(sku, before.version(), 777))
                .product();
        eventually(Duration.ofSeconds(10), () -> priceInIndex(sku) == 777);

        // A late, out-of-order index request for the old version is rejected by OpenSearch.
        indexDirectly(before);
        sleep(Duration.ofSeconds(1));
        assertThat(priceInIndex(sku)).isEqualTo(777);
        assertThat(after.version()).isEqualTo(before.version() + 1);
    }

    @Test
    void pagingLimitsAreEnforced() throws Exception {
        assertThat(http.get("/v1/search?size=101").statusCode()).isEqualTo(400);
        assertThat(http.get("/v1/search?page=200&size=100").statusCode()).isEqualTo(400);
    }

    @Autowired
    private ProductSearch search;

    private void indexDirectly(Product stale) {
        search.index(new Product(
                stale.sku(),
                stale.title(),
                stale.brand(),
                stale.category(),
                stale.priceCents(),
                stale.currency(),
                stale.rating(),
                stale.version(),
                Instant.now()));
    }

    @Autowired
    private OpenSearchClient openSearch;

    private long priceInIndex(String sku) {
        try {
            var doc = openSearch.get(g -> g.index("products").id(sku), ProductDocument.class);
            return doc.found() && doc.source() != null ? doc.source().priceCents() : -1;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void eventually(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            sleep(Duration.ofMillis(200));
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
