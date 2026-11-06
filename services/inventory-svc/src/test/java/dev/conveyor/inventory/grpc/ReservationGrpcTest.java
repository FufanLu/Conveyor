package dev.conveyor.inventory.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Duration;
import dev.conveyor.inventory.InventoryTestcontainers;
import dev.conveyor.inventory.StockFixtures;
import dev.conveyor.proto.inventory.v1.CommitRequest;
import dev.conveyor.proto.inventory.v1.GetAvailabilityRequest;
import dev.conveyor.proto.inventory.v1.InventoryServiceGrpc;
import dev.conveyor.proto.inventory.v1.Line;
import dev.conveyor.proto.inventory.v1.ReleaseReason;
import dev.conveyor.proto.inventory.v1.ReleaseRequest;
import dev.conveyor.proto.inventory.v1.ReservationStatus;
import dev.conveyor.proto.inventory.v1.ReserveRequest;
import dev.conveyor.proto.inventory.v1.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The inventory contract over gRPC, as the order saga will call it. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(InventoryTestcontainers.class)
class ReservationGrpcTest {

    @Autowired
    private ManagedChannel channel;

    @Autowired
    private JdbcClient jdbc;

    private InventoryServiceGrpc.InventoryServiceBlockingStub inventory;

    @BeforeEach
    void stub() {
        inventory = InventoryServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void holdsStockInTheCustomersRegionFirst() {
        ReserveResponse r = reserve("us-west", InventoryTestcontainers.DEAL_SKU, 3);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_HELD);
        assertThat(r.getAllocationsList())
                .extracting(a -> a.getFcId() + ":" + a.getQuantity())
                .containsExactly("west-1:3");
        assertThat(r.getExpiresAt().getSeconds()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    void splitsALineAcrossFcsWhenTheFirstRunsShort() {
        String sku = StockFixtures.sku(jdbc, Map.of("east-1", 2, "east-2", 3, "west-1", 10));

        ReserveResponse r = reserve("us-east", sku, 4);

        assertThat(r.getAllocationsList())
                .extracting(a -> a.getFcId() + ":" + a.getQuantity())
                .containsExactly("east-1:2", "east-2:2");
        assertThat(available(sku)).isEqualTo(15 - 4);
    }

    @Test
    void notEnoughStockIsRejectedAndClaimsNothing() {
        String sku = StockFixtures.sku(jdbc, Map.of("east-1", 1, "south-1", 1));
        String other = StockFixtures.sku(jdbc, Map.of("east-1", 5));

        ReserveResponse r = inventory.reserve(ReserveRequest.newBuilder()
                .setIdempotencyKey("k-" + UUID.randomUUID())
                .setOrderId("order-1")
                .setShipToRegion("us-east")
                .addLines(Line.newBuilder().setSku(other).setQuantity(2))
                .addLines(Line.newBuilder().setSku(sku).setQuantity(3))
                .build());

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_REJECTED);
        assertThat(r.getShortfallsList()).extracting(Line::getSku).containsExactly(sku);
        assertThat(r.getAllocationsList()).isEmpty();
        // the line that could have been served was given back: all-or-nothing per reservation
        assertThat(available(other)).isEqualTo(5);
        assertThat(available(sku)).isEqualTo(2);
    }

    @Test
    void retriesWithTheSameKeyReturnTheSameReservation() {
        String sku = StockFixtures.sku(jdbc, Map.of("east-1", 10));
        ReserveRequest request = ReserveRequest.newBuilder()
                .setIdempotencyKey("k-" + UUID.randomUUID())
                .setOrderId("order-2")
                .setShipToRegion("us-east")
                .addLines(Line.newBuilder().setSku(sku).setQuantity(4))
                .build();

        ReserveResponse first = inventory.reserve(request);
        ReserveResponse retry = inventory.reserve(request);

        assertThat(retry.getReservationId()).isEqualTo(first.getReservationId());
        assertThat(available(sku)).isEqualTo(6); // held once
    }

    @Test
    void commitThenReleaseGivesStockBackExactlyOnce() {
        String sku = StockFixtures.sku(jdbc, Map.of("central-1", 10));
        ReserveResponse r = reserve("us-central", sku, 7);
        String id = r.getReservationId();

        assertThat(inventory
                        .commit(CommitRequest.newBuilder().setReservationId(id).build())
                        .getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_COMMITTED);
        assertThat(available(sku)).isEqualTo(3); // committed units stay out of sellable stock

        ReleaseRequest release = ReleaseRequest.newBuilder()
                .setReservationId(id)
                .setReason(ReleaseReason.RELEASE_REASON_ORDER_CANCELLED)
                .build();
        assertThat(inventory.release(release).getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        assertThat(inventory.release(release).getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        assertThat(available(sku)).isEqualTo(10); // not 17

        assertThat(inventory
                        .commit(CommitRequest.newBuilder().setReservationId(id).build())
                        .getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED); // too late to commit
    }

    @Test
    void brokenRequestsAreInvalidArgumentAndUnknownIdsNotFound() {
        assertThatThrownBy(() -> inventory.reserve(ReserveRequest.newBuilder()
                        .setOrderId("o")
                        .addLines(Line.newBuilder()
                                .setSku(InventoryTestcontainers.DEAL_SKU)
                                .setQuantity(1))
                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> inventory.commit(
                        CommitRequest.newBuilder().setReservationId("nope").build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> inventory.commit(CommitRequest.newBuilder()
                        .setReservationId(UUID.randomUUID().toString())
                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    private ReserveResponse reserve(String region, String sku, int quantity) {
        return inventory.reserve(ReserveRequest.newBuilder()
                .setIdempotencyKey("k-" + UUID.randomUUID())
                .setOrderId("order-" + UUID.randomUUID())
                .setShipToRegion(region)
                .setHoldTtl(Duration.newBuilder().setSeconds(600))
                .addLines(Line.newBuilder().setSku(sku).setQuantity(quantity))
                .build());
    }

    private long available(String sku) {
        return inventory
                .getAvailability(
                        GetAvailabilityRequest.newBuilder().addSkus(sku).build())
                .getItems(0)
                .getSellable();
    }
}
