package dev.conveyor.catalog.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PriceUpdateRequest(@Min(0) @Max(100_000_000) long amountCents) {}
