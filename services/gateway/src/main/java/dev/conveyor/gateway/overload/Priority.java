package dev.conveyor.gateway.overload;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Who gets the last slots. Under overload the edge sheds browsing before buying: search may use
 * 40 % of the concurrency limit, product pages 60 %, and orders all of it. A customer who is
 * already checking out is worth more than a new search, and a search retried in a second costs
 * the business nothing.
 */
public enum Priority {
    CRITICAL(1.0),
    NORMAL(0.6),
    SHEDDABLE(0.4),
    /** Long-lived streams (SSE): never counted, they would hold a slot for minutes. */
    UNLIMITED(Double.POSITIVE_INFINITY);

    final double share;

    Priority(double share) {
        this.share = share;
    }

    public static Priority of(ServerHttpRequest request) {
        String path = request.getPath().value();
        if (path.endsWith("/events") || path.equals("/api/v1/ops/map")) {
            return UNLIMITED;
        }
        if (path.startsWith("/api/v1/deals")) {
            return UNLIMITED; // the waiting room: answered by the gateway from memory, no upstream
        }
        if (path.startsWith("/api/v1/orders")) {
            return CRITICAL;
        }
        if (path.startsWith("/api/v1/search")) {
            return SHEDDABLE;
        }
        if (path.startsWith("/api/v1/products") && HttpMethod.GET.equals(request.getMethod())) {
            return NORMAL;
        }
        if (path.startsWith("/api/")) {
            return NORMAL;
        }
        return UNLIMITED; // actuator and the like: not upstream traffic
    }
}
