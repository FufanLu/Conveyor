package dev.conveyor.platform.deals;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two tokens of a deal's waiting room, both HMAC-SHA256 signed with a secret the gateway and
 * order-svc share, so neither needs a lookup to check one:
 *
 * <ul>
 *   <li>a <b>ticket</b>: a place in line (deal, sequence number). The gateway hands it out when a
 *       customer joins and reads the place back from it on every poll, so the line itself is one
 *       counter in Redis.
 *   <li>an <b>admission</b>: permission to order the deal's SKU, bound to one customer (the token
 *       subject) and valid for a few minutes. order-svc refuses the deal SKU without one.
 * </ul>
 *
 * Format: base64url(payload) "." base64url(first 16 bytes of the MAC). The payload is
 * {@code kind|deal|value|epochSeconds}; deal ids and subjects never contain '|'.
 */
public final class DealTokens {

    public record Ticket(String dealId, long seq, Instant issuedAt) {}

    public record Admission(String dealId, String subject, Instant expiresAt) {}

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final int MAC_BYTES = 16;

    private final SecretKeySpec key;

    public DealTokens(String secret) {
        if (secret.length() < 16) {
            throw new IllegalArgumentException("deal token secret must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String ticket(Ticket t) {
        return sign("t|" + t.dealId() + "|" + t.seq() + "|" + t.issuedAt().getEpochSecond());
    }

    public String admission(Admission a) {
        return sign("a|" + a.dealId() + "|" + a.subject() + "|" + a.expiresAt().getEpochSecond());
    }

    public Optional<Ticket> readTicket(String token) {
        return verify(token, "t").flatMap(p -> {
            try {
                return Optional.of(new Ticket(p[1], Long.parseLong(p[2]), Instant.ofEpochSecond(Long.parseLong(p[3]))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    /** A valid, unexpired admission, or empty. */
    public Optional<Admission> readAdmission(String token, Instant now) {
        return verify(token, "a")
                .flatMap(p -> {
                    try {
                        return Optional.of(new Admission(p[1], p[2], Instant.ofEpochSecond(Long.parseLong(p[3]))));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .filter(a -> a.expiresAt().isAfter(now));
    }

    private String sign(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENC.encodeToString(bytes) + "." + ENC.encodeToString(mac(bytes));
    }

    private Optional<String[]> verify(String token, String kind) {
        int dot = token.indexOf('.');
        if (dot <= 0 || token.length() > 512) {
            return Optional.empty();
        }
        byte[] payload;
        byte[] given;
        try {
            payload = DEC.decode(token.substring(0, dot));
            given = DEC.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac(payload), given)) {
            return Optional.empty();
        }
        String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
        return parts.length == 4 && parts[0].equals(kind) ? Optional.of(parts) : Optional.empty();
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Arrays.copyOf(mac.doFinal(payload), MAC_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
