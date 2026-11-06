package dev.conveyor.inventory.infrastructure.persistence;

import dev.conveyor.inventory.application.ReservationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Holds that were neither committed nor released in time go back to sellable. */
@Component
class ExpirySweeper {

    private final ReservationService reservations;

    ExpirySweeper(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${conveyor.inventory.expiry-interval:PT5S}", initialDelayString = "PT5S")
    void sweep() {
        while (reservations.expireDue(500) == 500) {
            // keep going while there is a backlog
        }
    }
}
