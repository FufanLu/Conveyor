package dev.conveyor.catalog.infrastructure.search;

import java.net.URISyntaxException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class OpenSearchConfiguration {

    @Bean(destroyMethod = "close")
    OpenSearchTransport openSearchTransport(SearchProperties properties) throws URISyntaxException {
        return ApacheHttpClient5TransportBuilder.builder(HttpHost.create(properties.url()))
                .setMapper(new JacksonJsonpMapper())
                .setHttpClientConfigCallback(
                        client -> client.setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                                .setMaxConnTotal(64)
                                .setMaxConnPerRoute(64)
                                .setDefaultConnectionConfig(ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.ofSeconds(1))
                                        .build())
                                .build()))
                .setRequestConfigCallback(request -> request.setResponseTimeout(Timeout.ofSeconds(3)))
                .build();
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}
