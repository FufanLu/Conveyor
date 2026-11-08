package dev.conveyor.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.conveyor.order.application.PlaceOrder;
import dev.conveyor.platform.deals.DealTokens;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.ErrorResponseException;

class DealAdmissionsTest {

    private static final String SECRET = "a-secret-of-sixteen+";
    private final Instant now = Instant.parse("2026-07-08T09:00:00Z");
    private final DealAdmissions deals = new DealAdmissions(
            new DealAdmissions.Settings(SECRET, List.of(new DealAdmissions.Settings.Item("lightning-1", "B09E3779B1"))),
            Clock.fixed(now, ZoneOffset.UTC));
    private final DealTokens tokens = new DealTokens(SECRET);

    private String admission(String deal, String subject) {
        return tokens.admission(new DealTokens.Admission(deal, subject, now.plusSeconds(600)));
    }

    private static int status(ErrorResponseException e) {
        return e.getStatusCode().value();
    }

    @Test
    void otherSkusNeedNothing() {
        deals.check("alice", List.of(new PlaceOrder.Item("B03C6EF362", 3)), null);
    }

    @Test
    void theDealSkuNeedsTheCustomersOwnAdmissionForThatDeal() {
        List<PlaceOrder.Item> one = List.of(new PlaceOrder.Item("B09E3779B1", 1));
        deals.check("alice", one, admission("lightning-1", "alice"));

        assertThatThrownBy(() -> deals.check("alice", one, null))
                .isInstanceOfSatisfying(
                        ErrorResponseException.class, e -> assertThat(status(e)).isEqualTo(403));
        assertThatThrownBy(() -> deals.check("bob", one, admission("lightning-1", "alice")))
                .isInstanceOfSatisfying(
                        ErrorResponseException.class, e -> assertThat(status(e)).isEqualTo(403));
        assertThatThrownBy(() -> deals.check("alice", one, admission("lightning-2", "alice")))
                .isInstanceOfSatisfying(
                        ErrorResponseException.class, e -> assertThat(status(e)).isEqualTo(403));
    }

    @Test
    void oneUnitPerOrder() {
        assertThatThrownBy(() -> deals.check(
                        "alice", List.of(new PlaceOrder.Item("B09E3779B1", 2)), admission("lightning-1", "alice")))
                .isInstanceOfSatisfying(
                        ErrorResponseException.class, e -> assertThat(status(e)).isEqualTo(400));
    }
}
