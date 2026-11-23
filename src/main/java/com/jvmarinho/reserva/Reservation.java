package com.jvmarinho.reserva;

import java.time.Instant;
import java.util.UUID;

record Reservation(UUID id, String sku, int quantity, ReservationStatus status,
                   Instant createdAt, Instant expiresAt) {
    Reservation withStatus(ReservationStatus next) {
        return new Reservation(id, sku, quantity, next, createdAt, expiresAt);
    }
}
