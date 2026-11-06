package dev.conveyor.order.application;

public class PricingUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PricingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
