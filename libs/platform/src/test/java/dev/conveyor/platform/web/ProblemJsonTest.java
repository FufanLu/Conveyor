package dev.conveyor.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProblemJsonTest {

    @Test
    void rendersPlatformShapeAndEscapesStrings() {
        String json = ProblemJson.render(401, "Unauthorized", "say \"hi\"\n", "/v1/a\\b", "req-12345678");

        assertThat(json)
                .startsWith("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,")
                .contains("\"detail\":\"say \\\"hi\\\"\\n\"")
                .contains("\"instance\":\"/v1/a\\\\b\"")
                .contains("\"requestId\":\"req-12345678\"")
                .contains("\"timestamp\":\"");
    }

    @Test
    void omitsRequestIdWhenUnknown() {
        assertThat(ProblemJson.render(403, "Forbidden", "no", "/x", null)).doesNotContain("requestId");
    }
}
