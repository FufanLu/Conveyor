package dev.conveyor.platform.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 401 and 403 for reactive services (the gateway): set the RFC 6750 challenge, then fail with a
 * ResponseStatusException so the platform's ProblemJsonWebExceptionHandler renders the same
 * problem+json body as every other error.
 */
public final class ProblemJsonReactiveSecurityHandlers
        implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        exchange.getResponse().getHeaders().set(BearerChallenge.HEADER, BearerChallenge.forAuthentication(ex));
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, SecurityTexts.UNAUTHENTICATED));
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        exchange.getResponse().getHeaders().set(BearerChallenge.HEADER, BearerChallenge.forAccessDenied());
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, SecurityTexts.FORBIDDEN));
    }
}
