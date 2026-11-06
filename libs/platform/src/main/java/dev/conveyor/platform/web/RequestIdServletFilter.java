package dev.conveyor.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet-stack request id filter. Runs first so that every log line written while handling the
 * request, including errors, carries the id through the MDC. Writes one access-log line when the
 * request completes.
 */
public final class RequestIdServletFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("conveyor.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIds.acceptOrMint(request.getHeader(RequestIds.HEADER));
        long startNanos = System.nanoTime();
        request.setAttribute(RequestIds.ATTRIBUTE, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        MDC.put(RequestIds.MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (ACCESS_LOG.isInfoEnabled()) {
                ACCESS_LOG.info(
                        "{} {} -> {} in {} us",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        (System.nanoTime() - startNanos) / 1_000);
            }
            MDC.remove(RequestIds.MDC_KEY);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
