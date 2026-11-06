package dev.conveyor.inventory.grpc;

import io.grpc.ServerBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.ServerBuilderCustomizer;

/**
 * gRPC's default executor is a cached pool of platform threads: a 10,000-call burst created
 * thousands of them. Handlers block on Redis and JDBC, so they run on virtual threads instead.
 */
@Configuration(proxyBeanMethods = false)
class GrpcServerConfiguration {

    @Bean(destroyMethod = "close")
    ExecutorService grpcHandlers() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("grpc-handler-", 0).factory());
    }

    @Bean
    <T extends ServerBuilder<T>> ServerBuilderCustomizer<T> virtualThreadHandlers(ExecutorService grpcHandlers) {
        return builder -> builder.executor(grpcHandlers);
    }
}
