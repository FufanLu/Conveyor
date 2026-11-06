package dev.conveyor.inventory;

import dev.conveyor.platform.test.ServiceContractTest;
import org.springframework.context.annotation.Import;

@Import(InventoryTestcontainers.class)
class InventoryContractTest extends ServiceContractTest {}
