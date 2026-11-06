package dev.conveyor.order.api;

import dev.conveyor.order.application.OrderSearch;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-customer queries for operators. They never touch the sharded write path: they read
 * the OpenSearch projection, which lags the database by the CDC pipeline (seconds).
 */
@RestController
@RequestMapping("/v1/ops/orders")
class OpsOrderController {

    private final OrderSearch search;

    OpsOrderController(OrderSearch search) {
        this.search = search;
    }

    @GetMapping
    OrderSearch.Result find(
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String sku,
            @RequestParam(required = false) @Nullable String placedAfter,
            @RequestParam(defaultValue = "20") int size) {
        if (size < 1 || size > 100) {
            throw Problems.of(HttpStatus.BAD_REQUEST, "size is between 1 and 100.");
        }
        Instant after;
        try {
            after = placedAfter == null ? null : Instant.parse(placedAfter);
        } catch (DateTimeParseException e) {
            throw Problems.of(HttpStatus.BAD_REQUEST, "placedAfter is an ISO-8601 instant, e.g. 2026-11-06T09:00:00Z.");
        }
        return search.search(new OrderSearch.Query(status, sku, after, size));
    }
}
