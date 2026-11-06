package dev.conveyor.platform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps a Keycloak access token to Spring authorities: every realm role in
 * {@code realm_access.roles} becomes {@code ROLE_<role>} (so {@code hasRole("catalog-admin")}
 * works), and OAuth scopes keep their usual {@code SCOPE_<scope>} form. The principal name is
 * {@code preferred_username}, which is what shows up in audit logs.
 */
public final class KeycloakRealmRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
        if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    }

    /** The converter services plug into {@code oauth2ResourceServer().jwt()}. */
    public static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoles());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
