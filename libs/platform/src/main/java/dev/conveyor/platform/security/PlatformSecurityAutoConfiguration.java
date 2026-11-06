package dev.conveyor.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security building blocks for services that act as OAuth2 resource servers. Each service still
 * writes its own filter chain (the rules differ per service) and plugs these in.
 */
@AutoConfiguration
@ConditionalOnClass(
        name = "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter")
public class PlatformSecurityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.security.web.AuthenticationEntryPoint")
    static class Servlet {

        @Bean
        @ConditionalOnMissingBean
        ProblemJsonServletSecurityHandlers problemJsonServletSecurityHandlers() {
            return new ProblemJsonServletSecurityHandlers();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.security.web.server.ServerAuthenticationEntryPoint")
    static class Reactive {

        @Bean
        @ConditionalOnMissingBean
        ProblemJsonReactiveSecurityHandlers problemJsonReactiveSecurityHandlers() {
            return new ProblemJsonReactiveSecurityHandlers();
        }
    }
}
