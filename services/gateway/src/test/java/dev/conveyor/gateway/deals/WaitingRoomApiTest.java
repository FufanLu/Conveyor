package dev.conveyor.gateway.deals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.conveyor.platform.test.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A deal with 4 units and no overbooking, admitting 2 customers a second: six customers join,
 * the last two hear at once that it sold out, the first four are admitted over the next seconds
 * and get admissions only when signed in.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "conveyor.deals.secret=test-secret-sixteen+",
            "conveyor.deals.items[0].id=lightning-1",
            "conveyor.deals.items[0].sku=B09E3779B1",
            "conveyor.deals.items[0].units=4",
            "conveyor.deals.items[0].overbook=1.0",
            "conveyor.deals.items[0].initial-rate=2",
            "conveyor.deals.items[0].min-rate=2",
        })
@Import(WaitingRoomApiTest.Containers.class)
class WaitingRoomApiTest {

    private static final Pattern TICKET = Pattern.compile("\"ticket\":\"([^\"]+)\"");

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
        }

        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return TestJwts.reactiveDecoder();
        }
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String token = "Bearer " + TestJwts.tokenForSubject("shopper-1", "customer");

    private HttpResponse<String> send(String method, String path, String auth) throws Exception {
        return send(method, path, auth, "key-" + java.util.UUID.randomUUID());
    }

    private HttpResponse<String> send(String method, String path, String auth, String key) throws Exception {
        var b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                .header("Idempotency-Key", key)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theLineAdmitsAtItsRateAndTellsLateComersAtOnce() throws Exception {
        assertThat(send("POST", "/api/v1/deals/lightning-1/queue", null).statusCode())
                .isEqualTo(401);

        List<String> tickets = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            HttpResponse<String> joined = send("POST", "/api/v1/deals/lightning-1/queue", token);
            assertThat(joined.statusCode()).isEqualTo(201);
            assertThat(joined.body()).contains("\"place\":" + i);
            Matcher m = TICKET.matcher(joined.body());
            assertThat(m.find()).isTrue();
            tickets.add(m.group(1));
            if (i > 4) {
                assertThat(joined.body()).contains("\"state\":\"SOLD_OUT\"");
            }
        }

        // a join retried with the same key keeps its place instead of taking a new one
        HttpResponse<String> again = send("POST", "/api/v1/deals/lightning-1/queue", token, "retry-key-1");
        HttpResponse<String> replay = send("POST", "/api/v1/deals/lightning-1/queue", token, "retry-key-1");
        assertThat(again.body()).contains("\"place\":7");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(replay.body()).contains("\"place\":7");

        String path = "/api/v1/deals/lightning-1/queue/" + tickets.get(3);
        assertThat(send("GET", path, null).statusCode()).isEqualTo(401);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(send("GET", path, token).body()).contains("\"state\":\"ADMITTED\""));
        assertThat(send("GET", path, token).body()).contains("\"admission\":\"");

        assertThat(send("GET", "/api/v1/deals", null).body())
                .contains("\"joined\":7")
                .contains("\"admitted\":4");

        // a ticket for another deal, or a forged one, is no place in this line
        assertThat(send("GET", "/api/v1/deals/lightning-1/queue/forged.ticket", token)
                        .statusCode())
                .isEqualTo(404);
        // an order with a forged admission never reaches order-svc
        assertThat(http.send(
                                HttpRequest.newBuilder(URI.create("http://localhost:"
                                                + environment.getProperty("local.server.port") + "/api/v1/orders"))
                                        .header("Authorization", token)
                                        .header("Conveyor-Admission", "forged.admission")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(403);
    }
}
