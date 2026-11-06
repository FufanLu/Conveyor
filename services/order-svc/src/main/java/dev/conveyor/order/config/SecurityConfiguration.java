package dev.conveyor.order.config;

import dev.conveyor.platform.security.KeycloakRealmRoles;
import dev.conveyor.platform.security.ProblemJsonServletSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Orders belong to customers: every order endpoint needs a token. Ops queries need role ops. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ProblemJsonServletSecurityHandlers problems) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // /internal/** has no public route at the gateway; it is reachable only inside the
                // service network. Service-to-service identity (mTLS or client credentials) is M7.
                .authorizeHttpRequests(auth -> auth.requestMatchers("/internal/**")
                        .permitAll()
                        .requestMatchers("/v1/ops/**")
                        .hasRole("ops")
                        .requestMatchers("/v1/orders", "/v1/orders/**")
                        .authenticated()
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
