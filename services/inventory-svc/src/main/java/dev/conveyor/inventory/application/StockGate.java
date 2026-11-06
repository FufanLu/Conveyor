package dev.conveyor.inventory.application;

import dev.conveyor.inventory.domain.Allocation;
import dev.conveyor.inventory.domain.FulfillmentCenter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The contended part of inventory: available units per (SKU, FC), claimed and returned
 * atomically. A claim for one SKU line is all-or-nothing across the given FCs.
 */
public interface StockGate {

    /**
     * Claims {@code quantity} units of {@code sku} from the FCs in the given order, taking as
     * much as possible from each before moving on. Returns the allocations, or empty if the FCs
     * together do not have {@code quantity} units (and nothing is claimed).
     */
    Optional<List<Allocation>> claim(String sku, int quantity, List<FulfillmentCenter> inOrder);

    /** Gives claimed units back. */
    void release(List<Allocation> allocations);

    /** Available units per FC id. */
    Map<String, Long> available(String sku, List<FulfillmentCenter> fcs);
}
