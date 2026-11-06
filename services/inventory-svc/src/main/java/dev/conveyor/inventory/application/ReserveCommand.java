package dev.conveyor.inventory.application;

import dev.conveyor.inventory.domain.Line;
import java.time.Duration;
import java.util.List;

public record ReserveCommand(
        String idempotencyKey, String orderId, List<Line> lines, String shipToRegion, Duration holdTtl) {}
