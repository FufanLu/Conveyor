package dev.conveyor.fulfillment.activities;

import com.google.protobuf.util.Durations;
import dev.conveyor.fulfillment.config.FulfillmentProperties;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Allocation;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.FulfillmentRequest;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Hold;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.OrderUpdate;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Payment;
import dev.conveyor.proto.inventory.v1.CommitRequest;
import dev.conveyor.proto.inventory.v1.InventoryServiceGrpc.InventoryServiceBlockingStub;
import dev.conveyor.proto.inventory.v1.Line;
import dev.conveyor.proto.inventory.v1.ReleaseReason;
import dev.conveyor.proto.inventory.v1.ReleaseRequest;
import dev.conveyor.proto.inventory.v1.ReservationStatus;
import dev.conveyor.proto.inventory.v1.ReserveRequest;
import dev.conveyor.proto.inventory.v1.ReserveResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Talks to inventory-svc (gRPC), payment-mock and order-svc (HTTP). Transient failures are
 * thrown as-is so Temporal retries them; answers that will not change on retry (bad request,
 * conflict) become non-retryable failures so the saga compensates at once.
 */
public class FulfillmentActivitiesImpl implements FulfillmentActivities {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentActivitiesImpl.class);

    private final InventoryServiceBlockingStub inventory;
    private final RestClient payments;
    private final RestClient orders;
    private final FulfillmentProperties properties;

    public FulfillmentActivitiesImpl(
            InventoryServiceBlockingStub inventory,
            RestClient payments,
            RestClient orders,
            FulfillmentProperties properties) {
        this.inventory = inventory;
        this.payments = payments;
        this.orders = orders;
        this.properties = properties;
    }

    private InventoryServiceBlockingStub inventory() {
        return inventory.withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    @Override
    public Hold reserve(FulfillmentRequest request) {
        ReserveRequest.Builder rpc = ReserveRequest.newBuilder()
                .setIdempotencyKey(request.orderId() + ":reserve")
                .setOrderId(request.orderId())
                .setShipToRegion(request.shipToRegion())
                .setHoldTtl(Durations.fromMillis(properties.holdTtl().toMillis()));
        request.items()
                .forEach(i -> rpc.addLines(Line.newBuilder().setSku(i.sku()).setQuantity(i.quantity())));
        ReserveResponse response = call(() -> inventory().reserve(rpc.build()));
        List<Allocation> allocations = response.getAllocationsList().stream()
                .map(a -> new Allocation(a.getSku(), a.getFcId(), a.getQuantity()))
                .toList();
        List<String> shortfalls = response.getShortfallsList().stream()
                .map(l -> l.getSku() + "x" + l.getQuantity())
                .toList();
        String status = response.getStatus() == ReservationStatus.RESERVATION_STATUS_HELD ? "HELD" : "REJECTED";
        return new Hold(response.getReservationId(), status, allocations, shortfalls);
    }

    @Override
    public String commit(String reservationId) {
        ReservationStatus status = call(() -> inventory()
                        .commit(CommitRequest.newBuilder()
                                .setIdempotencyKey(reservationId + ":commit")
                                .setReservationId(reservationId)
                                .build()))
                .getStatus();
        return status.name().substring("RESERVATION_STATUS_".length());
    }

    @Override
    public void release(String reservationId) {
        ReservationStatus status = call(() -> inventory()
                        .release(ReleaseRequest.newBuilder()
                                .setIdempotencyKey(reservationId + ":release")
                                .setReservationId(reservationId)
                                .setReason(ReleaseReason.RELEASE_REASON_ORDER_CANCELLED)
                                .build()))
                .getStatus();
        log.info("released {}: {}", reservationId, status);
    }

    private static <T> T call(java.util.function.Supplier<T> rpc) {
        try {
            return rpc.get();
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INVALID_ARGUMENT
                    || code == Status.Code.NOT_FOUND
                    || code == Status.Code.FAILED_PRECONDITION) {
                throw ApplicationFailure.newNonRetryableFailure(e.getStatus().toString(), "Inventory" + code);
            }
            throw e; // UNAVAILABLE, RESOURCE_EXHAUSTED, DEADLINE_EXCEEDED: Temporal retries with backoff
        }
    }

    record AuthorizeBody(String idempotencyKey, String orderId, long amountCents, String callbackUrl) {}

    record AuthorizationBody(String authorizationId, String status, String reason) {}

    @Override
    public Payment authorize(String orderId, long amountCents) {
        AuthorizationBody a = payments.post()
                .uri("/v1/authorizations")
                .body(new AuthorizeBody(
                        orderId + ":authorize",
                        orderId,
                        amountCents,
                        properties.callbackBaseUrl() + "/v1/payment-callbacks"))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    throw ApplicationFailure.newNonRetryableFailure(
                            "payment answered " + res.getStatusCode(), "PaymentRejectedRequest");
                })
                .body(AuthorizationBody.class);
        if (a == null) {
            throw new IllegalStateException("empty payment answer");
        }
        return new Payment(a.authorizationId(), a.status(), a.reason());
    }

    @Override
    public void voidPayment(String authorizationId) {
        payments.post()
                .uri("/v1/authorizations/{id}/void", authorizationId)
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> log.warn("no authorization {} to void", authorizationId))
                .toBodilessEntity();
    }

    @Override
    public void updateOrder(OrderUpdate update) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", update.status());
        body.put("reason", update.reason());
        Instant promised = update.promisedDelivery();
        body.put("promisedDelivery", promised == null ? null : promised.toString());
        orders.post()
                .uri("/internal/v1/orders/{id}/status", update.orderId())
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    // 409: someone already finished this order differently. 404/400: will not heal.
                    throw ApplicationFailure.newNonRetryableFailure(
                            "order-svc answered " + res.getStatusCode() + " for " + update, "OrderUpdateRefused");
                })
                .toBodilessEntity();
        log.info("order {} -> {}", update.orderId(), update.status());
    }
}
