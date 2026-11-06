import dev.conveyor.proto.order.v1.OrderEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Prints orders.events records exactly as consumers see them: coordinates, timestamp, key,
 * headers, the first bytes of the value, and the decoded OrderEvent.
 *
 *   java -Djarmode=tools -jar services/order-svc/build/libs/order-svc.jar extract --destination build/order-svc-extracted
 *   java -Dlogback.configurationFile=tools/events/logback-quiet.xml -cp 'build/order-svc-extracted/lib/*' tools/events/TailOrderEvents.java localhost:9092 [orderId] [seconds]
 */
public class TailOrderEvents {
    public static void main(String[] args) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, args.length > 0 ? args[0] : "localhost:9092");
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "tail-" + UUID.randomUUID()); // never commits, sees everything
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        String only = args.length > 1 && !args[1].equals("-") ? args[1] : null;
        long seconds = args.length > 2 ? Long.parseLong(args[2]) : 5;
        try (var consumer = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("orders.events"));
            long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (System.nanoTime() < end) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (only != null && !only.equals(r.key())) {
                        continue;
                    }
                    System.out.printf("%s-%d@%d  timestamp %s (%s)%n", r.topic(), r.partition(), r.offset(),
                            Instant.ofEpochMilli(r.timestamp()), r.timestampType());
                    System.out.printf("key     %s%n", r.key());
                    for (Header h : r.headers()) {
                        System.out.printf("header  %s = %s%n", h.key(), new String(h.value()));
                    }
                    byte[] v = r.value();
                    System.out.printf("value   %d bytes: %s ...%n", v.length,
                            HexFormat.ofDelimiter(" ").formatHex(v, 0, Math.min(32, v.length)));
                    try {
                        System.out.println(OrderEvent.parseFrom(v).toString().trim().replace("\n", "\n        "));
                    } catch (Exception e) {
                        System.out.println("        (not an OrderEvent: " + e + ")");
                    }
                    System.out.println();
                }
            }
        }
    }
}
