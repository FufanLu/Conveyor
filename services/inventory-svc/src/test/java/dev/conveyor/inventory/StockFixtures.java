package dev.conveyor.inventory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Creates SKUs with exactly the stock a test needs. SKUs are outside the seed's range. */
public final class StockFixtures {

    private static final AtomicLong NEXT = new AtomicLong(0xA000_0000L);

    private StockFixtures() {}

    public static String sku(JdbcClient jdbc, Map<String, Integer> onHandPerFc) {
        String sku = String.format("B0%08X", NEXT.incrementAndGet());
        onHandPerFc.forEach(
                (fc, units) -> jdbc.sql("INSERT INTO stock (sku, fc_id, on_hand) VALUES (:sku, :fc, :units)")
                        .param("sku", sku)
                        .param("fc", fc)
                        .param("units", units)
                        .update());
        return sku;
    }
}
