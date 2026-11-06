package dev.conveyor.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ETagsTest {

    @Test
    void roundTrips() {
        assertThat(ETags.of(7)).isEqualTo("\"7\"");
        assertThat(ETags.version("\"7\"")).hasValue(7);
        assertThat(ETags.version("W/\"7\"")).hasValue(7);
        assertThat(ETags.version(" \"12\" ")).hasValue(12);
    }

    @Test
    void rejectsAnythingElse() {
        for (String bad : new String[] {null, "", "7", "\"\"", "\"x\"", "\"0\"", "\"-1\"", "*"}) {
            assertThat(ETags.version(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }
}
