package dev.conveyor.inventory.application;

public class DuplicateReservationKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateReservationKeyException(String key, Throwable cause) {
        super("idempotency key already used: " + key, cause);
    }
}
