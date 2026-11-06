package dev.conveyor.order.application;

import java.util.List;

/** What a customer asked for. Prices are not part of it: they come from the catalog. */
public record PlaceOrder(String userId, String idempotencyKey, List<Item> items, String shipToRegion) {

    public record Item(String sku, int quantity) {}
}
