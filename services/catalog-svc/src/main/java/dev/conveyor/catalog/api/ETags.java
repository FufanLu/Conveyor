package dev.conveyor.catalog.api;

import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/** Strong ETags carrying the product version: {@code "7"}. */
final class ETags {

    private ETags() {}

    static String of(long version) {
        return "\"" + version + "\"";
    }

    /** Parses {@code "7"} (or {@code W/"7"}) into 7; empty if the header is not one of ours. */
    static OptionalLong version(@Nullable String header) {
        if (header == null) {
            return OptionalLong.empty();
        }
        String tag = header.trim();
        if (tag.startsWith("W/")) {
            tag = tag.substring(2);
        }
        if (tag.length() < 3 || tag.charAt(0) != '"' || tag.charAt(tag.length() - 1) != '"') {
            return OptionalLong.empty();
        }
        try {
            long version = Long.parseLong(tag.substring(1, tag.length() - 1));
            return version >= 1 ? OptionalLong.of(version) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
