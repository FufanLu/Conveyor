package dev.conveyor.fulfillment.workflow;

import dev.conveyor.fulfillment.workflow.FulfillmentModel.Allocation;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * A deliberately simple promise model: if every unit ships from an FC in the customer's region,
 * delivery by 21:00 UTC two days out, otherwise four. FC ids carry their region ("west-2" is in
 * "us-west"). Pure and deterministic, so the workflow can compute it without an activity.
 */
public final class DeliveryPromise {

    private DeliveryPromise() {}

    public static Instant estimate(List<Allocation> allocations, String shipToRegion, Instant now) {
        boolean local = !allocations.isEmpty()
                && allocations.stream().allMatch(a -> regionOf(a.fcId()).equals(shipToRegion));
        ZonedDateTime day = now.atZone(ZoneOffset.UTC).plus(Duration.ofDays(local ? 2 : 4));
        return day.withHour(21).withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    static String regionOf(String fcId) {
        int dash = fcId.indexOf('-');
        return "us-" + (dash < 0 ? fcId : fcId.substring(0, dash));
    }
}
