package dev.conveyor.fulfillment;

import dev.conveyor.platform.test.ServiceContractTest;
import org.springframework.test.context.TestPropertySource;

// No Temporal or Kafka here: the worker and the starter stay off; the web contract still holds.
@TestPropertySource(properties = "conveyor.fulfillment.worker-enabled=false")
class FulfillmentContractTest extends ServiceContractTest {}
