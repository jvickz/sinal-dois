package com.jvmarinho.reserva;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReservationRepository {
    private final JdbcClient jdbc;

    ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Stock> stock(String sku) {
        return jdbc.sql("SELECT sku, available FROM inventory WHERE sku = :sku")
                .param("sku", sku)
                .query((row, ignored) -> new Stock(row.getString("sku"), row.getInt("available")))
                .optional();
    }

    Optional<UUID> claimRequest(String key) {
        jdbc.sql("""
                INSERT INTO reservation_requests (request_key) VALUES (:key)
                ON CONFLICT (request_key) DO NOTHING
                """).param("key", key).update();

        // A second transaction waits for the first command before deciding whether to replay it.
        return jdbc.sql("""
                SELECT reservation_id FROM reservation_requests WHERE request_key = :key FOR UPDATE
                """).param("key", key)
                .query((row, ignored) -> Optional.ofNullable(row.getObject("reservation_id", UUID.class)))
                .single();
    }

    void linkRequest(String key, UUID reservationId) {
        jdbc.sql("UPDATE reservation_requests SET reservation_id = :id WHERE request_key = :key")
                .param("id", reservationId).param("key", key).update();
    }

    boolean debitIfAvailable(String sku, int quantity) {
        return jdbc.sql("""
                UPDATE inventory SET available = available - :quantity
                WHERE sku = :sku AND available >= :quantity
                """).param("sku", sku).param("quantity", quantity).update() == 1;
    }

    void credit(String sku, int quantity) {
        jdbc.sql("UPDATE inventory SET available = available + :quantity WHERE sku = :sku")
                .param("sku", sku).param("quantity", quantity).update();
    }

    void insert(Reservation reservation) {
        jdbc.sql("""
                INSERT INTO reservations (id, sku, quantity, status, created_at, expires_at)
                VALUES (:id, :sku, :quantity, :status, :createdAt, :expiresAt)
                """)
                .param("id", reservation.id())
                .param("sku", reservation.sku())
                .param("quantity", reservation.quantity())
                .param("status", reservation.status().name())
                .param("createdAt", utc(reservation.createdAt()))
                .param("expiresAt", utc(reservation.expiresAt()))
                .update();
    }

    Optional<Reservation> find(UUID id) {
        return jdbc.sql("""
                SELECT id, sku, quantity, status, created_at, expires_at FROM reservations WHERE id = :id
                """).param("id", id).query(ReservationRepository::mapReservation).optional();
    }

    Optional<Reservation> lock(UUID id) {
        return jdbc.sql("""
                SELECT id, sku, quantity, status, created_at, expires_at
                FROM reservations WHERE id = :id FOR UPDATE
                """).param("id", id).query(ReservationRepository::mapReservation).optional();
    }

    void setStatus(UUID id, ReservationStatus status) {
        jdbc.sql("UPDATE reservations SET status = :status WHERE id = :id")
                .param("id", id).param("status", status.name()).update();
    }

    List<UUID> findDue(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id FROM reservations WHERE status = 'HELD' AND expires_at <= :now
                ORDER BY expires_at, id LIMIT :limit
                """).param("now", utc(now)).param("limit", limit).query(UUID.class).list();
    }

    private static Reservation mapReservation(ResultSet row, int ignored) throws SQLException {
        return new Reservation(row.getObject("id", UUID.class), row.getString("sku"), row.getInt("quantity"),
                ReservationStatus.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
