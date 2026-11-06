package dev.conveyor.catalog.api;

import static dev.conveyor.catalog.CatalogTestcontainers.KNOWN_SKU;
import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.catalog.CatalogTestcontainers;
import dev.conveyor.catalog.HttpTestClient;
import dev.conveyor.platform.test.TestJwts;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** The product API over real HTTP: reads, conditional requests, and guarded writes. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(CatalogTestcontainers.class)
class ProductApiTest {

    // Row 2 of the seed. Only this test writes to it.
    private static final String EDITED_SKU = "B03C6EF362";

    @Autowired
    private Environment environment;

    private HttpTestClient http;

    @BeforeEach
    void client() {
        http = new HttpTestClient(environment);
    }

    @Test
    void readsAProductWithItsVersionAsETag() throws Exception {
        HttpResponse<String> response = http.get("/v1/products/" + KNOWN_SKU);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("ETag")).hasValue("\"1\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("max-age=5, public");
        assertThat(response.body())
                .contains("\"sku\":\"" + KNOWN_SKU + "\"")
                .contains("\"price\":{\"amountCents\":")
                .contains("\"version\":1");
    }

    @Test
    void ifNoneMatchWithTheCurrentETagIs304WithoutABody() throws Exception {
        HttpResponse<String> response = http.get("/v1/products/" + KNOWN_SKU, "If-None-Match", "\"1\"");

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void unknownSkuIs404Problem() throws Exception {
        HttpResponse<String> response = http.get("/v1/products/B0FFFFFFFF");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"detail\":\"No product with SKU B0FFFFFFFF.\"");
    }

    @Test
    void behindTheGatewayProblemsNameThePublicPath() throws Exception {
        HttpResponse<String> response = http.get("/v1/products/B0FFFFFFFF", "X-Forwarded-Prefix", "/api");

        assertThat(response.body()).contains("\"instance\":\"/api/v1/products/B0FFFFFFFF\"");
    }

    @Test
    void malformedSkuIs400BeforeAnyLookup() throws Exception {
        HttpResponse<String> response = http.get("/v1/products/not-a-sku");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("SKU must be B0 followed by 8 upper-case hex digits.");
    }

    @Test
    void writeWithoutTokenIs401ProblemWithBearerChallenge() throws Exception {
        HttpResponse<String> response = http.put(priceOf(EDITED_SKU), "{\"amountCents\":999}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body())
                .contains("\"detail\":\"A valid bearer token is required for this operation.\"")
                .contains("\"requestId\":\"");
    }

    @Test
    void expiredTokenIs401InvalidToken() throws Exception {
        HttpResponse<String> response = http.put(
                priceOf(EDITED_SKU),
                "{\"amountCents\":999}",
                "Authorization",
                "Bearer " + TestJwts.expiredToken("ops", "catalog-admin"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("Bearer error=\"invalid_token\""));
    }

    @Test
    void tokenIssuedForAnotherApiIs401() throws Exception {
        HttpResponse<String> response = http.put(
                priceOf(EDITED_SKU),
                "{\"amountCents\":999}",
                "Authorization",
                "Bearer " + TestJwts.tokenForAnotherAudience("ops", "catalog-admin"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("Bearer error=\"invalid_token\""));
    }

    @Test
    void customerTokenIs403InsufficientScope() throws Exception {
        HttpResponse<String> response = http.put(
                priceOf(EDITED_SKU),
                "{\"amountCents\":999}",
                "Authorization",
                "Bearer " + TestJwts.token("alice", "customer"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("Bearer error=\"insufficient_scope\""));
        assertThat(response.body()).contains("\"status\":403");
    }

    @Test
    void conditionalPriceUpdateLifecycle() throws Exception {
        String admin = "Bearer " + TestJwts.token("ops", "catalog-admin");
        HttpResponse<String> before = http.get("/v1/products/" + EDITED_SKU);
        String etag = before.headers().firstValue("ETag").orElseThrow();

        // No precondition: refused, so a blind write cannot clobber someone else's edit.
        HttpResponse<String> blind = http.put(priceOf(EDITED_SKU), "{\"amountCents\":4242}", "Authorization", admin);
        assertThat(blind.statusCode()).isEqualTo(428);

        // Matching precondition: applied, new ETag.
        HttpResponse<String> updated =
                http.put(priceOf(EDITED_SKU), "{\"amountCents\":4242}", "Authorization", admin, "If-Match", etag);
        assertThat(updated.statusCode()).isEqualTo(200);
        String newEtag = updated.headers().firstValue("ETag").orElseThrow();
        assertThat(ETags.version(newEtag).getAsLong())
                .isEqualTo(ETags.version(etag).getAsLong() + 1);
        assertThat(updated.body()).contains("\"amountCents\":4242");

        // The old ETag is now stale: 412 with the current version, so the client can re-read.
        HttpResponse<String> stale =
                http.put(priceOf(EDITED_SKU), "{\"amountCents\":1}", "Authorization", admin, "If-Match", etag);
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(stale.headers().firstValue("ETag")).hasValue(newEtag);
        assertThat(stale.body())
                .contains("\"currentVersion\":" + ETags.version(newEtag).getAsLong());

        // Readers see the new price at once: the write went through to the cache.
        HttpResponse<String> after = http.get("/v1/products/" + EDITED_SKU);
        assertThat(after.headers().firstValue("ETag")).hasValue(newEtag);
        assertThat(after.body()).contains("\"amountCents\":4242");
    }

    @Test
    void invalidPriceIs400() throws Exception {
        HttpResponse<String> response = http.put(
                priceOf(KNOWN_SKU),
                "{\"amountCents\":-5}",
                "Authorization",
                "Bearer " + TestJwts.token("ops", "catalog-admin"),
                "If-Match",
                "\"1\"");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private static String priceOf(String sku) {
        return "/v1/products/" + sku + "/price";
    }
}
