package dev.conveyor.platform.web;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Renders the platform's RFC 9457 body for places that cannot go through a message converter,
 * such as servlet filters (Spring Security's entry points run before the DispatcherServlet).
 * Field order and names match {@link ProblemDetailsHandler} and {@link ProblemJsonWebExceptionHandler}.
 */
public final class ProblemJson {

    private ProblemJson() {}

    public static String render(int status, String title, String detail, String instance, @Nullable String requestId) {
        StringBuilder json = new StringBuilder(256).append('{');
        field(json, "type", "about:blank").append(',');
        field(json, "title", title).append(',');
        json.append("\"status\":").append(status).append(',');
        field(json, "detail", detail).append(',');
        field(json, "instance", instance).append(',');
        if (requestId != null) {
            field(json, "requestId", requestId).append(',');
        }
        field(json, "timestamp", Instant.now().toString());
        return json.append('}').toString();
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"');
    }
}
