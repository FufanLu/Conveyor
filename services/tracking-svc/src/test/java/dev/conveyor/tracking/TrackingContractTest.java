package dev.conveyor.tracking;

import dev.conveyor.platform.test.ServiceContractTest;
import org.springframework.context.annotation.Import;

@Import(TrackingTestcontainers.class)
class TrackingContractTest extends ServiceContractTest {}
