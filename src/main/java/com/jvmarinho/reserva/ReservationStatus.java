package com.jvmarinho.reserva;

import java.time.Instant;

enum ReservationStatus {
    HELD, CONFIRMED, CANCELLED, EXPIRED;

    ReservationStatus at(Instant now, Instant expiresAt) {
        if (this == HELD && !now.isBefore(expiresAt)) {
            return EXPIRED;
        }
        return this;
    }
}
