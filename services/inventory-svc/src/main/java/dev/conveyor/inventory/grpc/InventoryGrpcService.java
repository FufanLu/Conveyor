package dev.conveyor.inventory.grpc;

import com.google.protobuf.Timestamp;
import dev.conveyor.inventory.application.InvalidReservationException;
import dev.conveyor.inventory.application.ReservationService;
import dev.conveyor.inventory.application.ReserveCommand;
import dev.conveyor.inventory.domain.Line;
import dev.conveyor.inventory.domain.Reservation;
import dev.conveyor.proto.inventory.v1.Allocation;
import dev.conveyor.proto.inventory.v1.CommitRequest;
import dev.conveyor.proto.inventory.v1.CommitResponse;
import dev.conveyor.proto.inventory.v1.GetAvailabilityRequest;
import dev.conveyor.proto.inventory.v1.GetAvailabilityResponse;
import dev.conveyor.proto.inventory.v1.InventoryServiceGrpc;
import dev.conveyor.proto.inventory.v1.ReleaseRequest;
import dev.conveyor.proto.inventory.v1.ReleaseResponse;
import dev.conveyor.proto.inventory.v1.ReservationStatus;
import dev.conveyor.proto.inventory.v1.ReserveRequest;
import dev.conveyor.proto.inventory.v1.ReserveResponse;
import dev.conveyor.proto.inventory.v1.SkuAvailability;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * The inventory contract from libs/proto (conveyor.inventory.v1). Business outcomes travel in
 * the response (REJECTED with shortfalls); only broken requests and broken dependencies become
 * gRPC errors (INVALID_ARGUMENT, NOT_FOUND, UNAVAILABLE), so the saga can tell "no stock" from
 * "try again".
 */
@Service
class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcService.class);

    private final ReservationService reservations;

    InventoryGrpcService(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Override
    public void reserve(ReserveRequest request, StreamObserver<ReserveResponse> out) {
        answer(out, () -> {
            Reservation r = reservations.reserve(new ReserveCommand(
                    request.getIdempotencyKey(),
                    request.getOrderId(),
                    request.getLinesList().stream()
                            .map(l -> new Line(l.getSku(), l.getQuantity()))
                            .toList(),
                    request.getShipToRegion(),
                    request.hasHoldTtl()
                            ? Duration.ofSeconds(
                                    request.getHoldTtl().getSeconds(),
                                    request.getHoldTtl().getNanos())
                            : null));
            java.time.Instant expires = r.expiresAt();
            ReserveResponse.Builder response = ReserveResponse.newBuilder()
                    .setReservationId(r.id().toString())
                    .setStatus(status(r.status()))
                    .setExpiresAt(Timestamp.newBuilder()
                            .setSeconds(expires.getEpochSecond())
                            .setNanos(expires.getNano()));
            r.allocations()
                    .forEach(a -> response.addAllocations(Allocation.newBuilder()
                            .setSku(a.sku())
                            .setFcId(a.fcId())
                            .setQuantity(a.quantity())));
            r.shortfalls()
                    .forEach(s -> response.addShortfalls(dev.conveyor.proto.inventory.v1.Line.newBuilder()
                            .setSku(s.sku())
                            .setQuantity(s.quantity())));
            return response.build();
        });
    }

    @Override
    public void commit(CommitRequest request, StreamObserver<CommitResponse> out) {
        answer(
                out,
                () -> CommitResponse.newBuilder()
                        .setStatus(status(reservations
                                .commit(id(request.getReservationId()))
                                .status()))
                        .build());
    }

    @Override
    public void release(ReleaseRequest request, StreamObserver<ReleaseResponse> out) {
        answer(out, () -> {
            log.info("release {} ({})", request.getReservationId(), request.getReason());
            return ReleaseResponse.newBuilder()
                    .setStatus(status(
                            reservations.release(id(request.getReservationId())).status()))
                    .build();
        });
    }

    @Override
    public void getAvailability(GetAvailabilityRequest request, StreamObserver<GetAvailabilityResponse> out) {
        answer(out, () -> {
            GetAvailabilityResponse.Builder response = GetAvailabilityResponse.newBuilder();
            reservations
                    .availability(request.getSkusList())
                    .forEach((sku, units) -> response.addItems(
                            SkuAvailability.newBuilder().setSku(sku).setSellable(units)));
            return response.build();
        });
    }

    private static <T> void answer(StreamObserver<T> out, Supplier<T> call) {
        T response;
        try {
            response = call.get();
        } catch (InvalidReservationException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (StatusRuntimeException e) {
            out.onError(e);
            return;
        } catch (IllegalArgumentException e) {
            out.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (DataAccessException e) {
            log.warn("inventory dependency failed: {}", e.toString());
            out.onError(Status.UNAVAILABLE
                    .withDescription("inventory storage unavailable")
                    .asRuntimeException());
            return;
        }
        out.onNext(response);
        out.onCompleted();
    }

    private static UUID id(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("reservation_id is a UUID")
                    .asRuntimeException();
        }
    }

    private static ReservationStatus status(dev.conveyor.inventory.domain.ReservationStatus s) {
        return switch (s) {
            case HELD -> ReservationStatus.RESERVATION_STATUS_HELD;
            case COMMITTED -> ReservationStatus.RESERVATION_STATUS_COMMITTED;
            case RELEASED -> ReservationStatus.RESERVATION_STATUS_RELEASED;
            case EXPIRED -> ReservationStatus.RESERVATION_STATUS_EXPIRED;
            case REJECTED -> ReservationStatus.RESERVATION_STATUS_REJECTED;
        };
    }
}
