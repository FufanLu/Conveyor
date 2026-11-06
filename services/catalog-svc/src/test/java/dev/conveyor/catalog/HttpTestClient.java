package dev.conveyor.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.core.env.Environment;

/** Real HTTP against the running test server, like a client on the network would send it. */
public final class HttpTestClient {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final Environment environment;

    public HttpTestClient(Environment environment) {
        this.environment = environment;
    }

    public HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        return send(request(path, headers).GET());
    }

    public HttpResponse<String> put(String path, String json, String... headers)
            throws IOException, InterruptedException {
        return send(request(path, headers)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(10));
        if (headers.length > 0) {
            builder.headers(headers);
        }
        return builder;
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
