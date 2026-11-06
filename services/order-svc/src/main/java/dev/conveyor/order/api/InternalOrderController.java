package dev.conveyor.order.api;

import dev.conveyor.order.api.OrderRequests.OrderResponse;
import dev.conveyor.order.api.OrderRequests.StatusChangeRequest;
import dev.conveyor.order.application.InvalidOrderException;
import dev.conveyor.order.application.OrderService;
import dev.conveyor.order.application.OrderStore;
import dev.conveyor.order.domain.OrderId;
import dev.conveyor.order.domain.OrderStatus;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the fulfillment saga. Idempotent: the saga retries activities, and repeating a
 * transition answers 200 with the order unchanged. Moving a finished order to a different
 * terminal status is 409 with the current status.
 */
@RestController
class InternalOrderController {

    private final OrderService orders;

    InternalOrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/internal/v1/orders/{orderId}/status")
    OrderResponse changeStatus(@PathVariable String orderId, @RequestBody StatusChangeRequest request) {
        OrderId id;
        OrderStatus to;
        try {
            id = OrderId.parse(orderId);
            to = OrderStatus.valueOf(request.status());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw Problems.of(
                    HttpStatus.BAD_REQUEST,
                    "orderId is an order id and status one of " + java.util.Arrays.toString(OrderStatus.values()));
        }
        try {
            return switch (orders.changeStatus(id, to, request.reason(), request.promisedDelivery())) {
                case OrderStore.StatusChange.Changed(var order) -> OrderResponse.of(order);
                case OrderStore.StatusChange.AlreadyThere(var order) -> OrderResponse.of(order);
                case OrderStore.StatusChange.Conflict(var order) ->
                    throw Problems.of(
                            HttpStatus.CONFLICT,
                            "Order " + orderId + " is already " + order.status() + ".",
                            Map.of("currentStatus", order.status().name()));
                case OrderStore.StatusChange.NotFound() ->
                    throw Problems.of(HttpStatus.NOT_FOUND, "No order " + orderId + ".");
            };
        } catch (InvalidOrderException e) {
            throw Problems.of(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
