package dev.conveyor.inventory.domain;

import java.util.Comparator;
import java.util.List;

public record FulfillmentCenter(String id, String region, int fallbackRank) {

    /**
     * The order in which FCs are asked for stock for a customer in {@code region}: FCs in the
     * region first, then everything else by fallback rank. Shipping from the region is cheaper
     * and faster; shipping from elsewhere beats not selling.
     */
    public static List<FulfillmentCenter> preferenceFor(String region, List<FulfillmentCenter> all) {
        return all.stream()
                .sorted(Comparator.comparing(
                                (FulfillmentCenter fc) -> !fc.region().equals(region))
                        .thenComparingInt(FulfillmentCenter::fallbackRank))
                .toList();
    }
}
