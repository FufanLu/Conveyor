package dev.conveyor.catalog.api;

import dev.conveyor.catalog.application.ProductCatalog;
import dev.conveyor.catalog.application.ProductSearch;
import dev.conveyor.catalog.application.SearchUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/search")
class SearchController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DEPTH = 10_000; // deep paging past this belongs to search_after

    private final ProductCatalog catalog;

    SearchController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    ResponseEntity<ProductSearch.Result> search(
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String category,
            @RequestParam(required = false) @Nullable String brand,
            @RequestParam(required = false) @Nullable Long minPriceCents,
            @RequestParam(required = false) @Nullable Long maxPriceCents,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) (page + 1) * size > MAX_DEPTH) {
            throw Problems.of(
                    HttpStatus.BAD_REQUEST,
                    "page must be >= 0, size between 1 and " + MAX_PAGE_SIZE + ", and page*size at most " + MAX_DEPTH
                            + ".");
        }
        try {
            return ResponseEntity.ok(catalog.search(
                    new ProductSearch.Query(q, category, brand, minPriceCents, maxPriceCents, page, size)));
        } catch (SearchUnavailableException e) {
            var problem = Problems.of(
                    HttpStatus.SERVICE_UNAVAILABLE, "Search is temporarily unavailable. Product pages still work.");
            problem.getHeaders().set("Retry-After", "2");
            throw problem;
        }
    }
}
