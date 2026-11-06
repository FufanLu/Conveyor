package dev.conveyor.order.infrastructure.shard;

import dev.conveyor.order.domain.OrderId;
import java.util.List;

/**
 * Logical shard to physical database and table names. 16 logical shards over 2 databases
 * today: moving shards to a new database later means copying whole tables and changing this
 * mapping, never re-hashing customers.
 */
public final class ShardRouter {

    private final List<ShardGroup> groups;

    public ShardRouter(List<ShardGroup> groups) {
        for (int shard = 0; shard < OrderId.SHARDS; shard++) {
            int s = shard;
            if (groups.stream().filter(g -> g.holds(s)).count() != 1) {
                throw new IllegalArgumentException("shard " + shard + " must belong to exactly one database");
            }
        }
        this.groups = List.copyOf(groups);
    }

    public ShardGroup group(int shard) {
        for (ShardGroup g : groups) {
            if (g.holds(shard)) {
                return g;
            }
        }
        throw new IllegalArgumentException("no database for shard " + shard);
    }

    public List<ShardGroup> groups() {
        return groups;
    }

    /** orders_07, order_line_07 ... */
    public static String suffix(int shard) {
        return String.format("%02d", shard);
    }
}
