package dev.conveyor.tracking.store;

import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Small helpers over DynamoDB's attribute maps. */
final class Attrs {

    private Attrs() {}

    static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }

    static AttributeValue n(double v) {
        return AttributeValue.fromN(Double.toString(v));
    }

    static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }

    static Double dbl(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.n() == null ? null : Double.valueOf(v.n());
    }

    static long lng(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.n() == null ? 0L : Long.parseLong(v.n());
    }

    static Instant instant(Map<String, AttributeValue> item, String name) {
        String v = str(item, name);
        return v == null ? null : Instant.parse(v);
    }
}
