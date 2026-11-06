package dev.conveyor.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.conveyor.platform.test.TestJwts;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Edge authentication over real HTTP, with a stub upstream that records what reached it. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(GatewayAuthTest.Jwts.class)
class GatewayAuthTest {

    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static final AtomicReference<String> UPSTREAM_AUTHORIZATION = new AtomicReference<>();
    private static final HttpServer UPSTREAM = startUpstream();

    @Autowired
    private Environment environment;

    @TestConfiguration(proxyBeanMethods = false)
    static class Jwts {
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return TestJwts.reactiveDecoder();
        }
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        String stub = "http://localhost:" + UPSTREAM.getAddress().getPort();
        for (String upstream : List.of("catalog", "order", "fulfillment", "tracking")) {
            registry.add("conveyor.upstreams." + upstream, () -> stub);
        }
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    @Test
    void readsNeedNoToken() throws Exception {
        assertThat(send("GET", "/api/v1/products/B09E3779B1").statusCode()).isEqualTo(200);
    }

    @Test
    void writeWithoutTokenIsRejectedAtTheEdge() throws Exception {
        int callsBefore = UPSTREAM_CALLS.get();

        HttpResponse<String> response =
                send("PUT", "/api/v1/products/B09E3779B1/price", "X-Request-Id", "edge-auth-401");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body())
                .contains("\"detail\":\"A valid bearer token is required for this operation.\"")
                .contains("\"requestId\":\"edge-auth-401\"");
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(callsBefore); // never left the gateway
    }

    @Test
    void forgedOrExpiredTokensAreRejectedAtTheEdge() throws Exception {
        HttpResponse<String> expired = send(
                "PUT",
                "/api/v1/products/B09E3779B1/price",
                "Authorization",
                "Bearer " + TestJwts.expiredToken("ops", "catalog-admin"));
        HttpResponse<String> garbage =
                send("PUT", "/api/v1/products/B09E3779B1/price", "Authorization", "Bearer not.a.jwt");

        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("Bearer error=\"invalid_token\""));
        assertThat(garbage.statusCode()).isEqualTo(401);
    }

    @Test
    void validTokenIsForwardedUnchangedForTheServiceToCheckAgain() throws Exception {
        String token = TestJwts.token("ops", "catalog-admin");

        HttpResponse<String> response =
                send("PUT", "/api/v1/products/B09E3779B1/price", "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(UPSTREAM_AUTHORIZATION.get()).isEqualTo("Bearer " + token);
    }

    private HttpResponse<String> send(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                .method(
                        method,
                        method.equals("GET")
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString("{\"amountCents\":1}"));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                UPSTREAM_CALLS.incrementAndGet();
                UPSTREAM_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
