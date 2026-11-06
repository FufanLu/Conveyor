package dev.conveyor.order;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.core.env.Environment;

public final class HttpTestClient {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final Environment environment;

    public HttpTestClient(Environment environment) {
        this.environment = environment;
    }

    public HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        return HTTP.send(request(path, headers).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String path, String json, String... headers)
            throws IOException, InterruptedException {
        return HTTP.send(
                request(path, headers)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(20));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return b;
    }
}
