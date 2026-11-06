package dev.conveyor.order.domain;

import java.nio.charset.StandardCharsets;

/** Which of the 16 logical shards a customer's orders live in. */
public final class Shards {

    private Shards() {}

    /** Stable for the life of the system: changing it means moving every customer's orders. */
    public static int forUser(String userId) {
        byte[] bytes = userId.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf2_9ce4_8422_2325L; // FNV-1a 64
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x0000_0100_0000_01B3L;
        }
        hash ^= hash >>> 33; // fold the well-mixed high bits into the low ones
        return Math.floorMod(hash, OrderId.SHARDS);
    }
}
