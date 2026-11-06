package dev.conveyor.catalog;

import dev.conveyor.platform.test.ServiceContractTest;
import org.springframework.context.annotation.Import;

@Import(CatalogTestcontainers.class)
class CatalogContractTest extends ServiceContractTest {}
