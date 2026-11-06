package dev.conveyor.order;

import dev.conveyor.platform.test.ServiceContractTest;
import org.springframework.context.annotation.Import;

@Import(OrderTestcontainers.class)
class OrderContractTest extends ServiceContractTest {}
