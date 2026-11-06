package dev.conveyor.catalog.api;

import dev.conveyor.catalog.application.PriceUpdate;
import dev.conveyor.catalog.application.ProductCatalog;
import dev.conveyor.catalog.domain.Product;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Products by SKU, with HTTP-native concurrency control: GET returns the version as an ETag
 * (and 304 on {@code If-None-Match}); PUT requires {@code If-Match} and answers 412 if the
 * product changed since the client read it, so two operators editing the same price at the
 * same time cannot silently overwrite each other.
 */
@RestController
@RequestMapping("/v1/products")
class ProductController {

    private final ProductCatalog catalog;

    ProductController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{sku}")
    ResponseEntity<ProductResponse> get(
            @PathVariable String sku,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatch) {
        requireValidSku(sku);
        Product product = catalog.find(sku).orElseThrow(() -> notFound(sku));
        String etag = ETags.of(product.version());
        OptionalLong clientVersion = ETags.version(ifNoneMatch);
        if (clientVersion.isPresent() && clientVersion.getAsLong() == product.version()) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic())
                .body(ProductResponse.of(product));
    }

    @PutMapping("/{sku}/price")
    ResponseEntity<ProductResponse> updatePrice(
            @PathVariable String sku,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody PriceUpdateRequest request) {
        requireValidSku(sku);
        if (ifMatch == null) {
            throw Problems.of(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "Send If-Match with the ETag from your last read, so concurrent edits cannot overwrite each other.");
        }
        long expected = ETags.version(ifMatch)
                .orElseThrow(
                        () -> Problems.of(HttpStatus.BAD_REQUEST, "If-Match must be an ETag returned by this API."));
        return switch (catalog.updatePrice(sku, expected, request.amountCents())) {
            case PriceUpdate.Updated(Product updated) ->
                ResponseEntity.ok().eTag(ETags.of(updated.version())).body(ProductResponse.of(updated));
            case PriceUpdate.NotFound() -> throw notFound(sku);
            case PriceUpdate.VersionMismatch(Product current) -> {
                ErrorResponseException conflict = Problems.of(
                        HttpStatus.PRECONDITION_FAILED,
                        "The product changed since you read it. Re-read it and retry.",
                        Map.of("currentVersion", current.version()));
                conflict.getHeaders().setETag(ETags.of(current.version()));
                throw conflict;
            }
        };
    }

    private static void requireValidSku(String sku) {
        if (!Product.isValidSku(sku)) {
            throw Problems.of(HttpStatus.BAD_REQUEST, "SKU must be B0 followed by 8 upper-case hex digits.");
        }
    }

    private static ErrorResponseException notFound(String sku) {
        return Problems.of(HttpStatus.NOT_FOUND, "No product with SKU " + sku + ".");
    }
}
