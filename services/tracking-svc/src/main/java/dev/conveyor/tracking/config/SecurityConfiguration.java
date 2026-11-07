package dev.conveyor.tracking.config;

import dev.conveyor.platform.security.KeycloakRealmRoles;
import dev.conveyor.platform.security.ProblemJsonReactiveSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * A customer sees their own shipments (checked in the controller against the shipment's
 * owner); the live map of all shipments needs role ops. Courier ingest has no public route at
 * the gateway and is reachable only inside the service network; device identity is M7.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain api(ServerHttpSecurity http, ProblemJsonReactiveSecurityHandlers problems) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(ex -> ex.pathMatchers("/v1/ops/**")
                        .hasRole("ops")
                        .pathMatchers("/v1/shipments/**")
                        .authenticated()
                        .anyExchange()
                        .permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(
                                        KeycloakRealmRoles.jwtAuthenticationConverter())))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
