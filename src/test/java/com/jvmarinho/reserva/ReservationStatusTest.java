package com.jvmarinho.reserva;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static com.jvmarinho.reserva.ReservationStatus.CANCELLED;
import static com.jvmarinho.reserva.ReservationStatus.CONFIRMED;
import static com.jvmarinho.reserva.ReservationStatus.EXPIRED;
import static com.jvmarinho.reserva.ReservationStatus.HELD;
import static org.assertj.core.api.Assertions.assertThat;

class ReservationStatusTest {
    private final Instant expiry = Instant.parse("2026-11-23T12:02:00Z");

    @Test
    void reservationExpiresAtTheExactDeadline() {
        assertThat(HELD.at(expiry.minusNanos(1), expiry)).isEqualTo(HELD);
        assertThat(HELD.at(expiry, expiry)).isEqualTo(EXPIRED);
        assertThat(HELD.at(expiry.plusSeconds(1), expiry)).isEqualTo(EXPIRED);
    }

    @Test
    void terminalStatesStayTerminalPastTheDeadline() {
        Instant later = expiry.plusSeconds(1);
        assertThat(CONFIRMED.at(later, expiry)).isEqualTo(CONFIRMED);
        assertThat(CANCELLED.at(later, expiry)).isEqualTo(CANCELLED);
        assertThat(EXPIRED.at(later, expiry)).isEqualTo(EXPIRED);
    }
}
