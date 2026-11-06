package dev.conveyor.catalog.infrastructure.search;

import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.transport.httpclient5.ResponseException;

/**
 * Recognises OpenSearch's "an equal or newer version is already indexed" (HTTP 409). The Java
 * client reports it in two shapes: OpenSearchException when it parses the error body, and the
 * transport's ResponseException (an IOException) when it does not, which is what a 409 from an
 * external-version index request produced in practice. Both mean the same thing here.
 */
final class VersionConflicts {

    static final int STATUS = 409;

    private VersionConflicts() {}

    static boolean is(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof OpenSearchException e && e.status() == STATUS) {
                return true;
            }
            if (t instanceof ResponseException e && e.status() == STATUS) {
                return true;
            }
        }
        return false;
    }
}
