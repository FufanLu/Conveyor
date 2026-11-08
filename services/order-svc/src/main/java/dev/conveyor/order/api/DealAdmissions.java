package dev.conveyor.order.api;

import dev.conveyor.order.application.PlaceOrder;
import dev.conveyor.platform.deals.DealTokens;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A deal's SKU is sold only through its waiting room: an order that contains it must carry an
 * admission (header {@value #HEADER}) the gateway issued to this customer for this deal, and may
 * take one unit. Everything else passes untouched. Without configured deals this is a no-op.
 *
 * <p>The gateway already checks the signature before the request takes a slot in the deal lane;
 * the check here is the one that binds the admission to the customer and the SKU, so a token
 * cannot be lent to someone else or spent on another deal.
 */
@Component
@EnableConfigurationProperties(DealAdmissions.Settings.class)
class DealAdmissions {

    static final String HEADER = "Conveyor-Admission";

    @ConfigurationProperties("conveyor.deals")
    record Settings(
            @DefaultValue("dev-only-deal-secret-change-me") String secret,
            @DefaultValue List<Item> items) {
        record Item(String id, String sku) {}
    }

    private final Map<String, String> dealBySku;
    private final DealTokens tokens;
    private final Clock clock;

    DealAdmissions(Settings settings, Clock clock) {
        this.dealBySku = settings.items().stream().collect(Collectors.toMap(Settings.Item::sku, Settings.Item::id));
        this.tokens = new DealTokens(settings.secret());
        this.clock = clock;
    }

    void check(String subject, List<PlaceOrder.Item> items, @Nullable String admission) {
        for (PlaceOrder.Item item : items) {
            String deal = item.sku() == null ? null : dealBySku.get(item.sku());
            if (deal == null) {
                continue;
            }
            boolean admitted = admission != null
                    && tokens.readAdmission(admission, clock.instant())
                            .filter(a -> a.dealId().equals(deal) && a.subject().equals(subject))
                            .isPresent();
            if (!admitted) {
                throw Problems.of(
                        HttpStatus.FORBIDDEN,
                        "This item is in a deal and sold through its waiting room. Join the line first.",
                        Map.of("dealId", deal, "queue", "/api/v1/deals/" + deal + "/queue"));
            }
            if (item.quantity() > 1) {
                throw Problems.of(
                        HttpStatus.BAD_REQUEST, "Deal items are limited to one per order.", Map.of("dealId", deal));
            }
        }
    }
}
