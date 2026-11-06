package dev.conveyor.platform.web;

import java.net.ConnectException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Error contract for reactive services, the gateway above all: the same RFC 9457
 * {@code application/problem+json} body the servlet services send, carrying the same
 * {@code requestId} as the {@code X-Request-Id} header.
 *
 * <p>Upstream failures at the gateway get honest status codes: connection refused becomes
 * 503 with {@code Retry-After} (the dependency is down, try again later), a response timeout
 * becomes 504. A generic 500 would tell the client the gateway itself is broken.
 *
 * <p>Ordered ahead of Spring Boot's default {@code ErrorWebExceptionHandler} (order -1).
 */
public final class ProblemJsonWebExceptionHandler implements WebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ProblemJsonWebExceptionHandler.class);

    private final ServerResponse.Context context;

    public ProblemJsonWebExceptionHandler(ServerCodecConfigurer codecs) {
        this.context = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecs.getWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatus status = statusFor(error);
        String requestId = exchange.getAttribute(RequestIds.ATTRIBUTE);
        String path = exchange.getRequest().getPath().value();
        logFailure(status, requestId, path, error);

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detailFor(status, error));
        problem.put("instance", path);
        if (requestId != null) {
            problem.put("requestId", requestId);
        }
        problem.put("timestamp", Instant.now().toString());

        ServerResponse.BodyBuilder response =
                ServerResponse.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header("Retry-After", "1");
        }
        return response.bodyValue(problem).flatMap(r -> r.writeTo(exchange, context));
    }

    static HttpStatus statusFor(Throwable error) {
        if (error instanceof ResponseStatusException rse) {
            HttpStatus resolved = HttpStatus.resolve(rse.getStatusCode().value());
            return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (t instanceof TimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String detailFor(HttpStatus status, Throwable error) {
        if (error instanceof NoResourceFoundException) {
            // Spring's reason embeds the full request URL, host included.
            return ProblemTexts.NO_RESOURCE;
        }
        if (status.is4xxClientError() && error instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return switch (status) {
            case SERVICE_UNAVAILABLE -> ProblemTexts.DEPENDENCY_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> ProblemTexts.DEPENDENCY_TIMEOUT;
            default -> status.is5xxServerError() ? ProblemTexts.UNEXPECTED : status.getReasonPhrase();
        };
    }

    private static void logFailure(HttpStatus status, String requestId, String path, Throwable error) {
        if (!status.is5xxServerError()) {
            return;
        }
        MDC.put(RequestIds.MDC_KEY, requestId);
        try {
            if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
                log.error("unhandled error for {}", path, error);
            } else {
                log.warn("{} for {}: {}", status.value(), path, error.toString());
            }
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
