package dev.conveyor.order.application;

import java.util.Collection;
import java.util.Map;

/** Current catalog prices. Implemented by an HTTP client to catalog-svc. */
public interface Pricing {

    /** Price per SKU. Throws UnknownSkusException or PricingUnavailableException. */
    Map<String, Quote> quote(Collection<String> skus);

    record Quote(String sku, long unitPriceCents, String currency) {}
}
