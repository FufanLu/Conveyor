package dev.conveyor.fulfillment.workflow;

import java.time.Instant;
import java.util.List;

/** Values the workflow passes around. They are serialized into Temporal's event history. */
public final class FulfillmentModel {

    private FulfillmentModel() {}

    public record FulfillmentRequest(
            String orderId, String userId, List<Item> items, String shipToRegion, long totalCents) {}

    public record Item(String sku, int quantity) {}

    public record Allocation(String sku, String fcId, int quantity) {}

    /** status HELD or REJECTED; shortfalls only for REJECTED. */
    public record Hold(String reservationId, String status, List<Allocation> allocations, List<String> shortfalls) {
        public boolean held() {
            return "HELD".equals(status);
        }
    }

    /** status AUTHORIZED, DECLINED or PENDING. */
    public record Payment(String authorizationId, String status, String reason) {}

    /** Sent by payment-mock's callback for a PENDING authorization. */
    public record PaymentUpdate(String authorizationId, String status) {}

    public record OrderUpdate(String orderId, String status, String reason, Instant promisedDelivery) {}

    public enum Outcome {
        CONFIRMED,
        REJECTED,
        PAYMENT_FAILED,
        CANCELLED
    }
}
