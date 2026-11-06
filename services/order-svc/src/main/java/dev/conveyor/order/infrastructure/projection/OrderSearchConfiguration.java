package dev.conveyor.order.infrastructure.projection;

import java.net.URISyntaxException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.apache.kafka.common.TopicPartition;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderSearchProperties.class)
class OrderSearchConfiguration {

    @Bean(destroyMethod = "close")
    OpenSearchTransport orderSearchTransport(OrderSearchProperties properties) throws URISyntaxException {
        return ApacheHttpClient5TransportBuilder.builder(HttpHost.create(properties.url()))
                .setMapper(new JacksonJsonpMapper())
                .setHttpClientConfigCallback(
                        client -> client.setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                                .setDefaultConnectionConfig(ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.ofSeconds(1))
                                        .build())
                                .build()))
                .setRequestConfigCallback(request -> request.setResponseTimeout(Timeout.ofSeconds(3)))
                .build();
    }

    @Bean
    OpenSearchClient orderSearchClient(OpenSearchTransport orderSearchTransport) {
        return new OpenSearchClient(orderSearchTransport);
    }

    /**
     * A record that keeps failing (three retries 500 ms apart) goes to orders.events.dlt
     * instead of blocking its partition forever.
     */
    @Bean
    DefaultErrorHandler projectionErrorHandler(KafkaTemplate<String, byte[]> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, e) -> new TopicPartition(OrderTopics.ORDER_EVENTS_DLT, -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(500, 3));
    }
}
