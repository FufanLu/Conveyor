package dev.conveyor.order.application;

import dev.conveyor.order.domain.Order;
import dev.conveyor.order.domain.OrderId;
import dev.conveyor.order.domain.OrderIdGenerator;
import dev.conveyor.order.domain.OrderLine;
import dev.conveyor.order.domain.Shards;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY = 10;
    private static final Pattern SKU = Pattern.compile("B0[0-9A-F]{8}");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}-[a-z]+");

    private final OrderStore store;
    private final Pricing pricing;
    private final OrderIdGenerator ids;
    private final Clock clock;

    public OrderService(OrderStore store, Pricing pricing, OrderIdGenerator ids, Clock clock) {
        this.store = store;
        this.pricing = pricing;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Validates, prices against the catalog, and stores the order idempotently. The request
     * hash covers exactly what the customer controls (items and region, in canonical order),
     * so a retry with the same body replays and a different body under the same key is refused.
     */
    public Placement place(PlaceOrder command) {
        validate(command);
        List<PlaceOrder.Item> items = new ArrayList<>(command.items());
        items.sort(Comparator.comparing(PlaceOrder.Item::sku));
        Map<String, Pricing.Quote> quotes =
                pricing.quote(items.stream().map(PlaceOrder.Item::sku).toList());

        List<OrderLine> lines = items.stream()
                .map(i ->
                        new OrderLine(i.sku(), i.quantity(), quotes.get(i.sku()).unitPriceCents()))
                .toList();
        OrderId id = ids.next(Shards.forUser(command.userId()));
        Order order = Order.place(id, command.userId(), lines, "USD", command.shipToRegion(), clock.instant());
        return store.placeIdempotently(order, command.idempotencyKey(), requestHash(items, command.shipToRegion()));
    }

    public OrderStore.StatusChange changeStatus(
            OrderId id, dev.conveyor.order.domain.OrderStatus to, String reason, java.time.Instant promised) {
        if (!to.terminal()) {
            throw new InvalidOrderException("status must be one of CONFIRMED, REJECTED, PAYMENT_FAILED, CANCELLED");
        }
        return store.changeStatus(id, to, reason, promised);
    }

    public Optional<Order> find(OrderId id) {
        return store.find(id);
    }

    public List<Order> listForUser(String userId, Optional<OrderId> before, int limit) {
        return store.listForUser(userId, before, limit);
    }

    private static void validate(PlaceOrder command) {
        if (command.items() == null
                || command.items().isEmpty()
                || command.items().size() > MAX_LINES) {
            throw new InvalidOrderException("An order has between 1 and " + MAX_LINES + " lines.");
        }
        Set<String> seen = new HashSet<>();
        for (PlaceOrder.Item item : command.items()) {
            if (item.sku() == null || !SKU.matcher(item.sku()).matches()) {
                throw new InvalidOrderException("Every line needs a SKU: B0 followed by 8 upper-case hex digits.");
            }
            if (item.quantity() < 1 || item.quantity() > MAX_QUANTITY) {
                throw new InvalidOrderException("Quantity per line is between 1 and " + MAX_QUANTITY + ".");
            }
            if (!seen.add(item.sku())) {
                throw new InvalidOrderException("Each SKU may appear once; combine the quantities.");
            }
        }
        if (command.shipToRegion() == null
                || !REGION.matcher(command.shipToRegion()).matches()) {
            throw new InvalidOrderException("shipToRegion looks like us-east.");
        }
    }

    static byte[] requestHash(List<PlaceOrder.Item> sortedItems, String region) {
        StringBuilder canonical = new StringBuilder(region);
        for (PlaceOrder.Item item : sortedItems) {
            canonical.append('|').append(item.sku()).append('x').append(item.quantity());
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
