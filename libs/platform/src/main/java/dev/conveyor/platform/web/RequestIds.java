package dev.conveyor.platform.web;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Request identifiers that follow one call across the gateway and every service it touches.
 *
 * <p>New ids are UUIDv7 (RFC 9562): the top 48 bits are the Unix epoch in milliseconds, so ids
 * sort by creation time, which makes log searches and index locality better than random UUIDv4.
 * A caller-supplied id is kept only if it is short and uses a safe alphabet; anything else is
 * replaced so a client cannot inject log lines or oversized headers.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = RequestIds.class.getName() + ".id";

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;

    private RequestIds() {}

    /** Returns {@code candidate} if it is an acceptable id, otherwise a freshly minted UUIDv7. */
    public static String acceptOrMint(@Nullable String candidate) {
        if (candidate != null && ACCEPTED.matcher(candidate).matches()) {
            return candidate;
        }
        return uuidV7(Clock.systemUTC()).toString();
    }

    /**
     * Mints a UUIDv7. Randomness comes from {@link ThreadLocalRandom}: request ids are correlation
     * handles, not secrets, and a shared {@code SecureRandom} would become a contention point at
     * gateway request rates.
     */
    public static UUID uuidV7(Clock clock) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long unixMillis = clock.millis();
        long mostSigBits = (unixMillis << 16) | VERSION_7 | (random.nextLong() & 0x0FFFL);
        long leastSigBits = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | VARIANT_RFC_9562;
        return new UUID(mostSigBits, leastSigBits);
    }

    /** Extracts the Unix epoch milliseconds embedded in a UUIDv7. */
    public static long unixMillis(UUID uuidV7) {
        if (uuidV7.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: " + uuidV7);
        }
        return uuidV7.getMostSignificantBits() >>> 16;
    }
}
