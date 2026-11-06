package dev.conveyor.catalog.config;

import dev.conveyor.platform.security.KeycloakRealmRoles;
import dev.conveyor.platform.security.ProblemJsonServletSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Reads are public. Every write needs a Keycloak token with the realm role
 * {@code catalog-admin}. The gateway also checks for a token, but this service verifies the
 * signature and role itself: nothing behind the gateway trusts a request just because it got
 * through.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ProblemJsonServletSecurityHandlers problems) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // bearer tokens, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.PUT, "/v1/**")
                        .hasRole("catalog-admin")
                        .requestMatchers(HttpMethod.POST, "/v1/**")
                        .hasRole("catalog-admin")
                        .requestMatchers(HttpMethod.PATCH, "/v1/**")
                        .hasRole("catalog-admin")
                        .requestMatchers(HttpMethod.DELETE, "/v1/**")
                        .hasRole("catalog-admin")
                        .anyRequest()
                        .permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(KeycloakRealmRoles.jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }
}
