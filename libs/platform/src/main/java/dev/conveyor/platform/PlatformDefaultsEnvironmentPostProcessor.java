package dev.conveyor.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Adds {@code conveyor-platform-defaults.yaml} as the lowest-precedence property source.
 *
 * <p>Unlike {@code spring.config.import}, whose imported values win over the importing file,
 * this keeps the platform values as true defaults: any service can override them in its own
 * {@code application.yaml}, through environment variables, or on the command line.
 */
public final class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String RESOURCE = "conveyor-platform-defaults.yaml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("conveyor-platform-defaults", resource);
            sources.forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load " + RESOURCE, e);
        }
    }
}
