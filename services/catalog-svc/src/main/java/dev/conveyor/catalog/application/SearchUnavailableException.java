package dev.conveyor.catalog.application;

/** The search read model cannot answer right now. Product reads by SKU are unaffected. */
public class SearchUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
