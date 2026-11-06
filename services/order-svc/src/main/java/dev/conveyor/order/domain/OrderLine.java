package dev.conveyor.order.domain;

/** One line of an order, with the unit price captured from the catalog at placement time. */
public record OrderLine(String sku, int quantity, long unitPriceCents) {

    public OrderLine {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
        if (unitPriceCents < 0) {
            throw new IllegalArgumentException("unitPriceCents must be >= 0");
        }
    }

    public long totalCents() {
        return Math.multiplyExact(unitPriceCents, (long) quantity);
    }
}
