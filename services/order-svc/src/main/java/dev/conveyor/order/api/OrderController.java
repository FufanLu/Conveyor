package dev.conveyor.order.api;

import dev.conveyor.order.api.OrderRequests.OrderPage;
import dev.conveyor.order.api.OrderRequests.OrderResponse;
import dev.conveyor.order.api.OrderRequests.PlaceOrderRequest;
import dev.conveyor.order.application.InvalidOrderException;
import dev.conveyor.order.application.OrderService;
import dev.conveyor.order.application.PlaceOrder;
import dev.conveyor.order.application.Placement;
import dev.conveyor.order.application.PricingUnavailableException;
import dev.conveyor.order.application.UnknownSkusException;
import dev.conveyor.order.domain.Order;
import dev.conveyor.order.domain.OrderId;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Placing and reading orders. POST requires an {@code Idempotency-Key} (as in the IETF
 * httpapi draft): a client that times out can retry with the same key and gets the original
 * order back, flagged {@code Idempotent-Replayed: true}, instead of buying twice.
 */
@RestController
@RequestMapping("/v1/orders")
class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final int MAX_PAGE = 50;

    private final OrderService orders;
    private final DealAdmissions deals;

    OrderController(OrderService orders, DealAdmissions deals) {
        this.orders = orders;
        this.deals = deals;
    }

    @PostMapping
    ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestHeader(value = DealAdmissions.HEADER, required = false) @Nullable String admission,
            @RequestBody PlaceOrderRequest request) {
        if (key == null) {
            throw Problems.of(HttpStatus.BAD_REQUEST, "Send an Idempotency-Key header (8-64 of A-Z a-z 0-9 _ -).");
        }
        if (!KEY.matcher(key).matches()) {
            throw Problems.of(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 8-64 of A-Z a-z 0-9 _ -.");
        }
        List<PlaceOrder.Item> items = request.items() == null
                ? List.of()
                : request.items().stream()
                        .map(i -> new PlaceOrder.Item(i.sku(), i.quantity()))
                        .toList();
        deals.check(jwt.getSubject(), items, admission);
        Placement placement;
        try {
            placement = orders.place(new PlaceOrder(jwt.getSubject(), key, items, request.shipToRegion()));
        } catch (InvalidOrderException e) {
            throw Problems.of(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (UnknownSkusException e) {
            throw Problems.of(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Some SKUs are not in the catalog.",
                    Map.of("unknownSkus", e.skus()));
        } catch (PricingUnavailableException e) {
            ErrorResponseException unavailable = Problems.of(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Prices cannot be confirmed right now. Retry with the same Idempotency-Key.");
            unavailable.getHeaders().set("Retry-After", "2");
            throw unavailable;
        }
        return switch (placement) {
            case Placement.Placed(Order order) -> created(order).body(OrderResponse.of(order));
            case Placement.Replayed(Order order) ->
                created(order).header("Idempotent-Replayed", "true").body(OrderResponse.of(order));
            case Placement.KeyReused() ->
                throw Problems.of(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "This Idempotency-Key was already used for a different request. Use a new key for a new order.");
        };
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        OrderId id = parse(orderId);
        // Someone else's order is reported exactly like a missing one: ids are not secrets,
        // but whether an id exists should not be learnable by guessing.
        return orders.find(id)
                .filter(o -> o.userId().equals(jwt.getSubject()))
                .map(OrderResponse::of)
                .orElseThrow(() -> Problems.of(HttpStatus.NOT_FOUND, "No order " + orderId + "."));
    }

    @GetMapping
    OrderPage mine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Nullable String before,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw Problems.of(HttpStatus.BAD_REQUEST, "limit is between 1 and " + MAX_PAGE + ".");
        }
        List<Order> page =
                orders.listForUser(jwt.getSubject(), Optional.ofNullable(before).map(OrderController::parse), limit);
        String next = page.size() == limit ? page.getLast().id().toString() : null;
        return new OrderPage(page.stream().map(OrderResponse::of).toList(), next);
    }

    private static ResponseEntity.BodyBuilder created(Order order) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(order.id().toString())
                .toUri();
        return ResponseEntity.created(location);
    }

    private static OrderId parse(String text) {
        try {
            return OrderId.parse(text);
        } catch (IllegalArgumentException e) {
            throw Problems.of(HttpStatus.BAD_REQUEST, "Order ids are 13 characters of Crockford base32.");
        }
    }
}
