package dev.conveyor.platform.security;

import dev.conveyor.platform.web.ProblemJson;
import dev.conveyor.platform.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 for servlet services: the RFC 6750 {@code WWW-Authenticate} challenge plus the
 * platform's problem+json body with the request id. Security filters run before the
 * DispatcherServlet, so the body is rendered here rather than by the controller advice.
 */
public final class ProblemJsonServletSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader(BearerChallenge.HEADER, BearerChallenge.forAuthentication(ex));
        write(request, response, HttpStatus.UNAUTHORIZED, SecurityTexts.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        response.setHeader(BearerChallenge.HEADER, BearerChallenge.forAccessDenied());
        write(request, response, HttpStatus.FORBIDDEN, SecurityTexts.FORBIDDEN);
    }

    private static void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        Object requestId = request.getAttribute(RequestIds.ATTRIBUTE);
        byte[] body = ProblemJson.render(
                        status.value(),
                        status.getReasonPhrase(),
                        detail,
                        request.getRequestURI(),
                        requestId == null ? null : requestId.toString())
                .getBytes(StandardCharsets.UTF_8);
        response.setStatus(status.value());
        // JSON is UTF-8 by definition (RFC 8259), so no charset parameter: same Content-Type as
        // every other platform error. Writing bytes keeps the servlet from adding one.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
