package dev.conveyor.inventory.application;

import dev.conveyor.inventory.domain.FulfillmentCenter;
import java.util.List;

/** The FC network (read once; it changes on the scale of months). */
public interface FulfillmentCenters {

    List<FulfillmentCenter> all();
}
