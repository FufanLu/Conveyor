package dev.conveyor.order.application;

import java.util.List;

public class UnknownSkusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> skus;

    public UnknownSkusException(List<String> skus) {
        super("unknown SKUs: " + skus);
        this.skus = List.copyOf(skus);
    }

    public List<String> skus() {
        return skus;
    }
}
