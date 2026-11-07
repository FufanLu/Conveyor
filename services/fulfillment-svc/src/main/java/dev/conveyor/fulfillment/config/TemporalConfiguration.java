package dev.conveyor.fulfillment.config;

import dev.conveyor.fulfillment.activities.FulfillmentActivitiesImpl;
import dev.conveyor.fulfillment.workflow.OrderFulfillment;
import dev.conveyor.fulfillment.workflow.OrderFulfillmentWorkflow;
import dev.conveyor.proto.inventory.v1.InventoryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The plain Temporal SDK, wired by hand: one client, one worker on the order-fulfillment task
 * queue. The stubs connect lazily, so the service starts (and reports healthy) before Temporal
 * is reachable; the worker keeps polling until it is.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FulfillmentProperties.class)
class TemporalConfiguration {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(FulfillmentProperties p) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(p.temporalTarget())
                .build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, FulfillmentProperties p) {
        return WorkflowClient.newInstance(
                stubs,
                WorkflowClientOptions.newBuilder().setNamespace(p.namespace()).build());
    }

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel inventoryChannel(FulfillmentProperties p, ObservationRegistry observations) {
        return ManagedChannelBuilder.forTarget(p.inventoryTarget())
                .usePlaintext()
                // Reserve / Commit / Release become client spans in the order's trace.
                .intercept(new ObservationGrpcClientInterceptor(observations))
                .build();
    }

    private static RestClient http(RestClient.Builder builder, String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    FulfillmentActivitiesImpl fulfillmentActivities(
            ManagedChannel inventoryChannel, FulfillmentProperties p, RestClient.Builder builder) {
        return new FulfillmentActivitiesImpl(
                InventoryServiceGrpc.newBlockingStub(inventoryChannel),
                http(builder, p.paymentUrl()),
                http(builder, p.orderUrl()),
                p);
    }

    @Bean
    WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    /** Registers the workflow and activities and starts polling once the context is up. */
    @Bean
    SmartLifecycle temporalWorker(
            WorkerFactory factory, FulfillmentActivitiesImpl activities, FulfillmentProperties p) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                Worker worker = factory.newWorker(
                        OrderFulfillment.TASK_QUEUE,
                        WorkerOptions.newBuilder()
                                .setMaxConcurrentActivityExecutionSize(200)
                                .build());
                worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflow.class);
                worker.registerActivitiesImplementations(activities);
                if (p.workerEnabled()) {
                    factory.start();
                }
                running = true;
            }

            @Override
            public void stop() {
                if (p.workerEnabled()) {
                    factory.shutdown();
                }
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
