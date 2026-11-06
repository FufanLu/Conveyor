package dev.conveyor.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRolesTest {

    @Test
    void mapsRealmRolesToRoleAuthoritiesAndKeepsScopes() {
        Jwt jwt = jwt(Map.of(
                "preferred_username", "ops",
                "scope", "openid profile",
                "realm_access", Map.of("roles", List.of("catalog-admin", "offline_access"))));

        var authentication = KeycloakRealmRoles.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getName()).isEqualTo("ops");
        // FACTOR_BEARER is added by Spring Security 7 itself: it records which authentication
        // factor was used, for multi-factor authorization rules.
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "SCOPE_openid", "SCOPE_profile", "ROLE_catalog-admin", "ROLE_offline_access", "FACTOR_BEARER");
    }

    @Test
    void tokenWithoutRealmAccessHasOnlyScopes() {
        Jwt jwt = jwt(Map.of("preferred_username", "svc", "scope", "openid"));

        assertThat(new KeycloakRealmRoles().convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_openid");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
