package dev.conveyor.inventory.api;

import dev.conveyor.inventory.application.FulfillmentCenters;
import dev.conveyor.inventory.application.ReservationService;
import dev.conveyor.inventory.application.ReservationStore;
import dev.conveyor.inventory.domain.FulfillmentCenter;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Internal, read-only view of one SKU's stock per FC (no public route at the gateway). */
@RestController
class InventoryController {

    private final ReservationService reservations;
    private final ReservationStore store;
    private final FulfillmentCenters fcs;

    InventoryController(ReservationService reservations, ReservationStore store, FulfillmentCenters fcs) {
        this.reservations = reservations;
        this.store = store;
        this.fcs = fcs;
    }

    record FcStock(String fcId, String region, int onHand, long available) {}

    record SkuStock(String sku, long available, List<FcStock> fcs) {}

    @GetMapping("/v1/inventory/{sku}")
    SkuStock get(@PathVariable String sku) {
        if (!sku.matches("B0[0-9A-F]{8}")) {
            throw new ErrorResponseException(
                    HttpStatus.BAD_REQUEST,
                    ProblemDetail.forStatusAndDetail(
                            HttpStatus.BAD_REQUEST, "SKU must be B0 followed by 8 hex digits."),
                    null);
        }
        Map<String, Long> available = reservations.availabilityByFc(sku);
        Map<String, Integer> onHand = store.onHand(sku);
        List<FcStock> perFc = fcs.all().stream()
                .map((FulfillmentCenter fc) -> new FcStock(
                        fc.id(), fc.region(), onHand.getOrDefault(fc.id(), 0), available.getOrDefault(fc.id(), 0L)))
                .toList();
        return new SkuStock(sku, perFc.stream().mapToLong(FcStock::available).sum(), perFc);
    }
}
