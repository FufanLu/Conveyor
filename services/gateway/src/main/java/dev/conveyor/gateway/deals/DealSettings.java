package dev.conveyor.gateway.deals;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deals sold through a waiting room. order-svc reads the same {@code conveyor.deals} keys (id,
 * sku, secret) to refuse the SKU without an admission.
 *
 * @param secret HMAC key for tickets and admissions, shared with order-svc
 * @param items the deals
 */
@ConfigurationProperties("conveyor.deals")
public record DealSettings(
        @DefaultValue("dev-only-deal-secret-change-me") String secret,
        @DefaultValue List<Deal> items) {

    /**
     * One deal and how its line admits buyers.
     *
     * @param id the deal's id in URLs
     * @param sku the SKU on sale
     * @param units stock on offer
     * @param overbook places admitted per unit: some admitted customers never order, so the line
     *     admits a little more than the stock; the rest are told it sold out
     * @param initialRate admissions per second when the line opens
     * @param minRate floor of the adaptive rate
     * @param maxRate ceiling of the adaptive rate
     * @param targetLatency mean latency of deal orders above which the rate backs off
     * @param admissionTtl how long an admitted customer has to order
     */
    public record Deal(
            String id,
            String sku,
            int units,
            @DefaultValue("1.1") double overbook,
            @DefaultValue("20") double initialRate,
            @DefaultValue("2") double minRate,
            @DefaultValue("500") double maxRate,
            @DefaultValue("500ms") Duration targetLatency,
            @DefaultValue("10m") Duration admissionTtl) {

        long places() {
            return (long) Math.ceil(units * overbook);
        }
    }
}
