package dev.conveyor.catalog.application;

import dev.conveyor.catalog.domain.Product;

/** Outcome of a conditional price change. */
public sealed interface PriceUpdate {

    record Updated(Product product) implements PriceUpdate {}

    record NotFound() implements PriceUpdate {}

    /** The caller's If-Match version is stale; {@code current} is what is stored now. */
    record VersionMismatch(Product current) implements PriceUpdate {}
}
