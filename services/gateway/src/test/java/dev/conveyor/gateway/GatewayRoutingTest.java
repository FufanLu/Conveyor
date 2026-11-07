package dev.conveyor.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Drives a real request through the gateway into a stub upstream and inspects both sides. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    private static final AtomicReference<String> UPSTREAM_PATH = new AtomicReference<>();
    private static final AtomicReference<String> UPSTREAM_REQUEST_ID = new AtomicReference<>();
    private static final AtomicReference<String> UPSTREAM_FORWARDED_PREFIX = new AtomicReference<>();
    private static final HttpServer UPSTREAM = startUpstream();

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void pointRoutesAtStub(DynamicPropertyRegistry registry) {
        String stub = "http://localhost:" + UPSTREAM.getAddress().getPort();
        for (String upstream : List.of("catalog", "order", "fulfillment")) {
            registry.add("conveyor.upstreams." + upstream, () -> stub);
        }
        // tracking-svc is "down": nothing listens on this port.
        registry.add("conveyor.upstreams.tracking", () -> "http://localhost:" + unusedPort());
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Test
    void publishesOneRoutePerPublicResource() {
        List<String> ids =
                routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids)
                .containsExactlyInAnyOrder(
                        "catalog", "orders", "ops-orders", "delivery-promises", "shipments", "ops-map");
    }

    @Test
    void stripsApiPrefixAndForwardsTheSameRequestIdItReturns() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:"
                                        + environment.getRequiredProperty("local.server.port")
                                        + "/api/v1/orders/o-123"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(UPSTREAM_PATH.get()).isEqualTo("/v1/orders/o-123");
        // Lets the service rebuild the public path, e.g. for problem+json "instance".
        assertThat(UPSTREAM_FORWARDED_PREFIX.get()).isEqualTo("/api");
        // The upstream echoes X-Request-Id too; the client must still see exactly one.
        assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
        String returned = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(returned).version()).isEqualTo(7);
        assertThat(UPSTREAM_REQUEST_ID.get()).isEqualTo(returned);
    }

    @Test
    void clientCannotSpoofTheForwardedPrefix() throws Exception {
        get("/api/v1/orders/o-9", "X-Forwarded-Prefix", "/evil");

        assertThat(UPSTREAM_FORWARDED_PREFIX.get()).isEqualTo("/api");
    }

    @Test
    void upstreamDownIs503ProblemWithRetryAfter() throws Exception {
        HttpResponse<String> response = get("/api/v1/shipments/s-1", "X-Request-Id", "routing-test-503");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body())
                .contains("\"requestId\":\"routing-test-503\"")
                .contains("\"instance\":\"/api/v1/shipments/s-1\"")
                .doesNotContain("Connection refused"); // no internals leak to clients
    }

    @Test
    void pathWithoutPublicRouteIs404ProblemWithTheHeaderRequestId() throws Exception {
        HttpResponse<String> response = get("/api/v1/admin/users", "X-Request-Id", "routing-test-404");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().allValues("X-Request-Id")).containsExactly("routing-test-404");
        assertThat(response.body()).contains("\"requestId\":\"routing-test-404\"");
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                UPSTREAM_PATH.set(exchange.getRequestURI().getPath());
                UPSTREAM_REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
                UPSTREAM_FORWARDED_PREFIX.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Prefix"));
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
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
