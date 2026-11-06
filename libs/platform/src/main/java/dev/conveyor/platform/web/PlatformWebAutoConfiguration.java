package dev.conveyor.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;

/**
 * Registers the platform web conventions for whichever web stack the service runs on: request
 * ids and access logging, plus the problem+json error contract ({@link ProblemDetailsHandler}
 * on servlet, {@link ProblemJsonWebExceptionHandler} on reactive). Ordered before Spring MVC's
 * auto-configuration so that our handler wins over Boot's default problem-details handler.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration")
public class PlatformWebAutoConfiguration {

    // Class references are by name: reactive services do not have spring-webmvc on the classpath.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    static class Servlet {

        @Bean
        RequestIdServletFilter requestIdServletFilter() {
            return new RequestIdServletFilter();
        }

        @Bean
        @ConditionalOnMissingBean(
                type = "org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler")
        ProblemDetailsHandler problemDetailsHandler() {
            return new ProblemDetailsHandler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class Reactive {

        @Bean
        RequestIdWebFilter requestIdWebFilter() {
            return new RequestIdWebFilter();
        }

        @Bean
        ProblemJsonWebExceptionHandler problemJsonWebExceptionHandler(ServerCodecConfigurer codecs) {
            return new ProblemJsonWebExceptionHandler(codecs);
        }
    }
}
