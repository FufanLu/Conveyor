package dev.conveyor.platform.deals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DealTokensTest {

    private final DealTokens tokens = new DealTokens("a-secret-of-sixteen+");
    private final Instant now = Instant.parse("2026-07-08T09:00:00Z");

    @Test
    void ticketsAndAdmissionsRoundTrip() {
        String ticket = tokens.ticket(new DealTokens.Ticket("lightning-1", 4242, now));
        assertThat(tokens.readTicket(ticket)).hasValue(new DealTokens.Ticket("lightning-1", 4242, now));

        String admission = tokens.admission(new DealTokens.Admission("lightning-1", "f3a1-sub", now.plusSeconds(600)));
        assertThat(tokens.readAdmission(admission, now))
                .hasValueSatisfying(a -> assertThat(a.subject()).isEqualTo("f3a1-sub"));
    }

    @Test
    void forgedExpiredAndSwappedTokensAreRefused() {
        String admission = tokens.admission(new DealTokens.Admission("lightning-1", "sub", now.plusSeconds(600)));
        assertThat(tokens.readAdmission(admission, now.plusSeconds(601))).isEmpty();

        String otherKey = new DealTokens("another-secret-16+")
                .admission(new DealTokens.Admission("lightning-1", "sub", now.plusSeconds(600)));
        assertThat(tokens.readAdmission(otherKey, now)).isEmpty();

        // a ticket is not an admission, even though both are signed with the same key
        String ticket = tokens.ticket(new DealTokens.Ticket("lightning-1", 1, now));
        assertThat(tokens.readAdmission(ticket, now)).isEmpty();

        String tampered = "dHxsaWdodG5pbmctMXw5OTk5OXwx" + admission.substring(admission.indexOf('.'));
        assertThat(tokens.readTicket(tampered)).isEmpty();
        assertThat(tokens.readAdmission("not-a-token", now)).isEmpty();
        assertThat(tokens.readAdmission("!!.??", now)).isEmpty();
    }
}
