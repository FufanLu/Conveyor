package dev.conveyor.inventory.infrastructure.persistence;

import dev.conveyor.inventory.application.FulfillmentCenters;
import dev.conveyor.inventory.domain.FulfillmentCenter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFulfillmentCenters implements FulfillmentCenters {

    private final JdbcClient jdbc;
    private final AtomicReference<List<FulfillmentCenter>> cached = new AtomicReference<>();

    JdbcFulfillmentCenters(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FulfillmentCenter> all() {
        List<FulfillmentCenter> fcs = cached.get();
        if (fcs == null) {
            fcs = jdbc.sql("SELECT fc_id, region, fallback_rank FROM fulfillment_center ORDER BY fallback_rank")
                    .query((rs, n) -> new FulfillmentCenter(rs.getString(1), rs.getString(2), rs.getInt(3)))
                    .list();
            cached.set(fcs);
        }
        return fcs;
    }
}
