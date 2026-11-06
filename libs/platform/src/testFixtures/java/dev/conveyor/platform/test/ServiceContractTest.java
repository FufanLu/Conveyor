package dev.conveyor.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;

/**
 * The platform contract every Conveyor service must honor, checked over real HTTP against the
 * running service, servlet or reactive alike. A service opts in with one line:
 * {@code class XContractTest extends ServiceContractTest {}}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class ServiceContractTest {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Autowired
    private Environment environment;

    protected HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static String requestIdOf(HttpResponse<?> response) {
        assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
        return response.headers().firstValue("X-Request-Id").orElseThrow();
    }

    @Test
    void healthIsUpAndMintsUuidV7RequestId() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(UUID.fromString(requestIdOf(response)).version()).isEqualTo(7);
    }

    @Test
    void readinessAndLivenessProbesAreExposed() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void echoesAcceptableCallerRequestId() throws Exception {
        HttpResponse<String> response = get("/actuator/health", "X-Request-Id", "client-req-0001");

        assertThat(requestIdOf(response)).isEqualTo("client-req-0001");
    }

    @Test
    void replacesUnacceptableCallerRequestId() throws Exception {
        HttpResponse<String> response = get("/actuator/health", "X-Request-Id", "<script>");

        assertThat(requestIdOf(response)).isNotEqualTo("<script>");
        assertThat(UUID.fromString(requestIdOf(response)).version()).isEqualTo(7);
    }

    @Test
    void exportsHttpServerHistogramForPrometheus() throws Exception {
        get("/actuator/health");

        HttpResponse<String> scrape = get("/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("http_server_requests_seconds_bucket");
    }

    @Test
    void unknownPathIsProblemJsonCarryingTheHeaderRequestId() throws Exception {
        HttpResponse<String> response = get("/v1/no-such-resource", "X-Request-Id", "contract-test-404");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(requestIdOf(response)).isEqualTo("contract-test-404");
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body())
                .contains("\"status\":404")
                .contains("\"requestId\":\"contract-test-404\"")
                .contains("\"instance\":\"/v1/no-such-resource\"")
                .contains("\"detail\":\"No resource at this path.\"")
                .doesNotContain("localhost");
    }
}
