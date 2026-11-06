package dev.conveyor.catalog.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Builds RFC 9457 errors as {@link ErrorResponseException}, so they flow through the platform's
 * ProblemDetailsHandler and pick up {@code requestId} and {@code timestamp} like every other error.
 */
final class Problems {

    private Problems() {}

    static ErrorResponseException of(HttpStatus status, String detail) {
        return of(status, detail, Map.of());
    }

    static ErrorResponseException of(HttpStatus status, String detail, Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        properties.forEach(problem::setProperty);
        return new ErrorResponseException(status, problem, null);
    }
}
