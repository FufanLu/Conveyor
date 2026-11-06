package dev.conveyor.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authorizations, idempotent by key. Deterministic triggers make scenarios reproducible:
 * amounts ending in 13 cents are declined, amounts ending in 37 cents go PENDING and are
 * approved by a callback after {@code callbackDelayMs}. On top, {@code PUT /v1/admin/behaviour}
 * injects random declines, 500s and latency.
 */
@RestController
class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    record AuthorizeRequest(String idempotencyKey, String orderId, long amountCents, String callbackUrl) {}

    record Authorization(String authorizationId, String orderId, long amountCents, String status, String reason) {}

    record Behaviour(double declineRate, double errorRate, long latencyMs, long callbackDelayMs) {}

    private final Map<String, Authorization> byKey = new ConcurrentHashMap<>();
    private final Map<String, Authorization> byId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService callbacks = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile Behaviour behaviour = new Behaviour(0, 0, 0, 2_000);

    @PutMapping("/v1/admin/behaviour")
    Behaviour configure(@RequestBody Behaviour next) {
        behaviour = next;
        log.info("behaviour now {}", next);
        return next;
    }

    @PostMapping("/v1/authorizations")
    Authorization authorize(@RequestBody AuthorizeRequest request) throws InterruptedException {
        Behaviour b = behaviour;
        if (b.latencyMs() > 0) {
            Thread.sleep(b.latencyMs());
        }
        if (ThreadLocalRandom.current().nextDouble() < b.errorRate()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "injected failure");
        }
        return byKey.computeIfAbsent(request.idempotencyKey(), key -> decide(request, b));
    }

    private Authorization decide(AuthorizeRequest r, Behaviour b) {
        String id = "auth-" + UUID.randomUUID();
        long cents = r.amountCents() % 100;
        Authorization a;
        if (cents == 13 || ThreadLocalRandom.current().nextDouble() < b.declineRate()) {
            a = new Authorization(id, r.orderId(), r.amountCents(), "DECLINED", "card declined");
        } else if (cents == 37) {
            a = new Authorization(id, r.orderId(), r.amountCents(), "PENDING", "customer authentication required");
            scheduleApproval(a, r.callbackUrl(), b.callbackDelayMs());
        } else {
            a = new Authorization(id, r.orderId(), r.amountCents(), "AUTHORIZED", null);
        }
        byId.put(id, a);
        log.info("{} {} for order {} ({} cents)", a.status(), id, r.orderId(), r.amountCents());
        return a;
    }

    private void scheduleApproval(Authorization a, String callbackUrl, long delayMs) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return; // nobody to tell: the order stays pending until the saga gives up
        }
        // The task catches everything itself; nothing waits on its future.
        var _ = callbacks.schedule(
                () -> {
                    Authorization approved =
                            new Authorization(a.authorizationId(), a.orderId(), a.amountCents(), "AUTHORIZED", null);
                    byId.put(a.authorizationId(), approved);
                    String body = "{\"orderId\":\"" + a.orderId() + "\",\"authorizationId\":\"" + a.authorizationId()
                            + "\",\"status\":\"AUTHORIZED\"}";
                    try {
                        HttpResponse<Void> response = http.send(
                                HttpRequest.newBuilder(URI.create(callbackUrl))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(body))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
                        log.info("callback for {} -> {}", a.orderId(), response.statusCode());
                    } catch (Exception e) {
                        log.warn("callback for {} failed: {}", a.orderId(), e.toString());
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS);
    }

    @PostMapping("/v1/authorizations/{id}/void")
    Authorization voidAuthorization(@PathVariable String id) {
        Authorization a = byId.get(id);
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no authorization " + id);
        }
        Authorization voided = new Authorization(a.authorizationId(), a.orderId(), a.amountCents(), "VOIDED", null);
        byId.put(id, voided);
        return voided;
    }

    @GetMapping("/v1/authorizations/{id}")
    Authorization get(@PathVariable String id) {
        Authorization a = byId.get(id);
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no authorization " + id);
        }
        return a;
    }
}
