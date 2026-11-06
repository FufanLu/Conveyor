package dev.conveyor.platform.security;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;

/**
 * RFC 6750 {@code WWW-Authenticate} values. No token: {@code Bearer}. A rejected token (bad
 * signature, expired, wrong issuer): {@code Bearer error="invalid_token", ...}. A valid token
 * without the needed role: {@code Bearer error="insufficient_scope", ...}.
 */
final class BearerChallenge {

    static final String HEADER = "WWW-Authenticate";

    private BearerChallenge() {}

    static String forAuthentication(AuthenticationException ex) {
        if (ex instanceof OAuth2AuthenticationException oauth && oauth.getError() instanceof BearerTokenError error) {
            StringBuilder value = new StringBuilder("Bearer error=\"")
                    .append(error.getErrorCode())
                    .append('"');
            if (error.getDescription() != null) {
                value.append(", error_description=\"")
                        .append(error.getDescription().replace('"', '\''))
                        .append('"');
            }
            return value.toString();
        }
        return "Bearer";
    }

    static String forAccessDenied() {
        return "Bearer error=\"insufficient_scope\", "
                + "error_description=\"The request requires higher privileges than provided by the access token.\"";
    }
}
