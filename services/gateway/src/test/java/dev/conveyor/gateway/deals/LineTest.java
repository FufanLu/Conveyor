package dev.conveyor.gateway.deals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LineTest {

    @Test
    void placesUpToTheFrontierAreAdmittedAndPastTheCapacityAreToldAtOnce() {
        assertThat(Line.status(100, 100, 6600, 20).state()).isEqualTo(Line.State.ADMITTED);
        assertThat(Line.status(6601, 100, 6600, 20).state()).isEqualTo(Line.State.SOLD_OUT);

        Line.Status waiting = Line.status(700, 100, 6600, 20);
        assertThat(waiting.state()).isEqualTo(Line.State.WAITING);
        assertThat(waiting.ahead()).isEqualTo(600);
        assertThat(waiting.etaSeconds()).isEqualTo(30);
        assertThat(waiting.pollAfterMillis()).isEqualTo(15_000); // eta 30 s
        assertThat(Line.status(6000, 100, 6600, 2).pollAfterMillis()).isEqualTo(60_000);
        // close to the front: poll every second
        assertThat(Line.status(110, 100, 6600, 20).pollAfterMillis()).isEqualTo(1000);
    }

    @Test
    void theRateBacksOffOnTroubleAndGrowsWhilePeopleWait() {
        assertThat(Line.nextRate(40, 2, 500, 1, 0, 100, 500, true)).isEqualTo(30);
        assertThat(Line.nextRate(40, 2, 500, 0, 0, 900, 500, true)).isEqualTo(30);
        assertThat(Line.nextRate(2, 2, 500, 3, 0, 100, 500, true)).isEqualTo(2);
        assertThat(Line.nextRate(40, 2, 500, 0, 0, 100, 500, true)).isEqualTo(42);
        assertThat(Line.nextRate(10, 2, 500, 0, 0, 100, 500, true)).isEqualTo(11);
        assertThat(Line.nextRate(40, 2, 500, 0, 0, 100, 500, false)).isEqualTo(40);
    }

    @Test
    void outstandingAdmissionsAreHeldToStockAndComeBackWhenTheyExpire() {
        // 100 units left, 10 % overbooking: 110 admissions may be outstanding
        assertThat(Line.allowance(100, 1.1, 0, 0)).isEqualTo(110);
        // 80 admitted in the last ten minutes, 30 of them already ordered: 50 outstanding
        assertThat(Line.allowance(100, 1.1, 80, 30)).isEqualTo(60);
        // walked-away customers hold every admission: wait until they expire
        assertThat(Line.allowance(100, 1.1, 200, 0)).isZero();
        assertThat(Line.allowance(0, 1.1, 0, 0)).isZero();
    }
}
