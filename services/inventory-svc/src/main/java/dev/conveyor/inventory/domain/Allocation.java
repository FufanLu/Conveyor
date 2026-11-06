package dev.conveyor.inventory.domain;

/** Units of one SKU held in one FC. */
public record Allocation(String sku, String fcId, int quantity) {

    public Allocation {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
    }
}
