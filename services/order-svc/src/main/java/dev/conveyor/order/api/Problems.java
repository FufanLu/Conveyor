package dev.conveyor.order.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** RFC 9457 errors that flow through the platform handler (request id, timestamp). */
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
