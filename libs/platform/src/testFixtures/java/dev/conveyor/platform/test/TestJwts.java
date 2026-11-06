package dev.conveyor.platform.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Keycloak-shaped RS256 access tokens for tests, plus decoders that trust them. Tests register
 * {@link #decoder()} (or {@link #reactiveDecoder()}) as a bean, which replaces the issuer-based
 * decoder Spring Boot would build, and then call the service over real HTTP with
 * {@code Authorization: Bearer <token>}.
 */
public final class TestJwts {

    public static final String ISSUER = "http://keycloak.test/realms/conveyor";
    public static final String AUDIENCE = "conveyor-api";

    private static final RSAKey KEY = generateKey();

    private TestJwts() {}

    /** A valid token for {@code username} carrying the given Keycloak realm roles. */
    public static String token(String username, String... realmRoles) {
        return sign(username, Instant.now().plus(Duration.ofMinutes(5)), realmRoles);
    }

    /** A correctly signed token that expired a minute ago. */
    public static String expiredToken(String username, String... realmRoles) {
        return sign(username, Instant.now().minus(Duration.ofMinutes(1)), realmRoles);
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(validator());
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ReactiveJwtDecoder reactiveDecoder() {
        try {
            NimbusReactiveJwtDecoder decoder =
                    NimbusReactiveJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(validator());
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same checks the services apply in production: signature, expiry, issuer, audience. */
    private static OAuth2TokenValidator<Jwt> validator() {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(AUDIENCE)));
    }

    /** Signed and current, but issued for some other API. */
    public static String tokenForAnotherAudience(String username, String... realmRoles) {
        return sign(username, Instant.now().plus(Duration.ofMinutes(5)), "some-other-api", realmRoles);
    }

    private static String sign(String username, Instant expiresAt, String... realmRoles) {
        return sign(username, expiresAt, AUDIENCE, realmRoles);
    }

    private static String sign(String username, Instant expiresAt, String audience, String... realmRoles) {
        Instant issuedAt = expiresAt.minus(Duration.ofMinutes(5));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(audience)
                .subject(UUID.nameUUIDFromBytes(username.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString())
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .claim("scope", "openid profile")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(KEY.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("conveyor-test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
