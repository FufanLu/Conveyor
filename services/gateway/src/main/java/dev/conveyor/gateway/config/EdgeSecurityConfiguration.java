package dev.conveyor.gateway.config;

import dev.conveyor.platform.security.KeycloakRealmRoles;
import dev.conveyor.platform.security.ProblemJsonReactiveSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Edge policy: reads pass, anything that changes state needs a valid Keycloak token. Invalid
 * and expired tokens are rejected here, before they cost an upstream call. The token is
 * forwarded unchanged and each service checks it again together with its own role rules, so
 * the gateway is a first filter rather than the only one.
 */
@Configuration(proxyBeanMethods = false)
class EdgeSecurityConfiguration {

    @Bean
    SecurityWebFilterChain edge(ServerHttpSecurity http, ProblemJsonReactiveSecurityHandlers problems) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance()) // stateless
                .authorizeExchange(exchanges -> exchanges
                        // A place in a deal's line turns into an admission for one customer.
                        .pathMatchers("/api/v1/deals/*/queue/**")
                        .authenticated()
                        .pathMatchers(HttpMethod.GET, "/**")
                        .permitAll()
                        .pathMatchers(HttpMethod.HEAD, "/**")
                        .permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(
                                        KeycloakRealmRoles.jwtAuthenticationConverter())))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
