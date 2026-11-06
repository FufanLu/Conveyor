package dev.conveyor.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestIdsTest {

    @Test
    void uuidV7CarriesVersionVariantAndTimestamp() {
        Instant now = Instant.parse("2026-11-06T09:00:00.123Z");

        UUID id = RequestIds.uuidV7(Clock.fixed(now, ZoneOffset.UTC));

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // RFC 9562 variant bits 10
        assertThat(RequestIds.unixMillis(id)).isEqualTo(now.toEpochMilli());
    }

    @Test
    void uuidV7SortsByMillisecond() {
        Instant t0 = Instant.parse("2026-11-06T09:00:00.000Z");
        UUID earlier = RequestIds.uuidV7(Clock.fixed(t0, ZoneOffset.UTC));
        UUID later = RequestIds.uuidV7(Clock.fixed(t0.plusMillis(1), ZoneOffset.UTC));

        // String order is what log stores and B-tree indexes see.
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void uuidV7IsUniqueWithinTheSameMillisecond() {
        Clock frozen = Clock.fixed(Instant.parse("2026-11-06T09:00:00Z"), ZoneOffset.UTC);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            ids.add(RequestIds.uuidV7(frozen));
        }
        assertThat(ids).hasSize(100_000);
    }

    @Test
    void acceptsSaneCallerIds() {
        assertThat(RequestIds.acceptOrMint("order-7f3a:retry.2")).isEqualTo("order-7f3a:retry.2");
    }

    @Test
    void mintsWhenCallerIdIsMissingTooShortTooLongOrUnsafe() {
        for (String bad : new String[] {null, "", "short", "a".repeat(129), "evil\r\nX-Admin: 1", "<script>"}) {
            String minted = RequestIds.acceptOrMint(bad);
            assertThat(minted).isNotEqualTo(bad);
            assertThat(UUID.fromString(minted).version()).isEqualTo(7);
        }
    }

    @Test
    void rejectsNonV7WhenExtractingTimestamp() {
        assertThatIllegalArgumentException().isThrownBy(() -> RequestIds.unixMillis(UUID.randomUUID()));
    }
}
