package com.jvmarinho.reserva;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jvmarinho.reserva.ReservationStatus.CANCELLED;
import static com.jvmarinho.reserva.ReservationStatus.EXPIRED;
import static com.jvmarinho.reserva.ReservationStatus.HELD;

@Service
class ReservationService {
    private final ReservationRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration ttl;
    private final Counter created;
    private final Counter expired;
    private final Counter refused;

    ReservationService(ReservationRepository repository, TransactionTemplate transactions, Clock clock,
                       @Value("${reservation.ttl}") Duration ttl, MeterRegistry registry) {
        if (ttl.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("O prazo de reserva deve ser de pelo menos 1 ms.");
        }
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
        this.ttl = ttl;
        created = registry.counter("inventory.reservations.events", "result", "created");
        expired = registry.counter("inventory.reservations.events", "result", "expired");
        refused = registry.counter("inventory.reservations.events", "result", "insufficient_stock");
    }

    Stock stock(String sku) {
        return repository.stock(sku).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                "SKU_NOT_FOUND", "O SKU informado não existe."));
    }

    Reservation get(UUID id) {
        return repository.find(id).orElseThrow(ReservationService::reservationNotFound);
    }

    Creation create(String key, String sku, int quantity) {
        try {
            Creation result = transactions.execute(ignored -> createInTransaction(key, sku, quantity));
            if (result.created()) {
                created.increment();
            }
            return result;
        } catch (ApiProblem problem) {
            if (problem.code().equals(ApiProblem.INSUFFICIENT_STOCK)) {
                refused.increment();
            }
            throw problem;
        }
    }

    Reservation changeStatus(UUID id, ReservationStatus requested) {
        Transition result = transition(id, requested);
        if (result.reservation().status() != requested) {
            // Expiry initiated by an HTTP command remains committed before the conflict is reported.
            throw new ApiProblem(HttpStatus.CONFLICT, "RESERVATION_STATE_CONFLICT",
                    "A reserva no estado " + result.reservation().status() + " não aceita esta operação.");
        }
        return result.reservation();
    }

    void expire(UUID id) {
        transition(id, EXPIRED);
    }

    private Creation createInTransaction(String key, String sku, int quantity) {
        var previous = repository.claimRequest(key);
        if (previous.isPresent()) {
            Reservation saved = get(previous.get());
            if (!saved.sku().equals(sku) || saved.quantity() != quantity) {
                throw new ApiProblem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "A chave já pertence a uma reserva com outro SKU ou quantidade.");
            }
            return new Creation(saved, false);
        }

        if (!repository.debitIfAvailable(sku, quantity)) {
            stock(sku);
            throw new ApiProblem(HttpStatus.CONFLICT, ApiProblem.INSUFFICIENT_STOCK,
                    "O saldo disponível é insuficiente para esta reserva.");
        }

        Instant createdAt = now();
        Instant expiresAt = createdAt.plus(ttl).truncatedTo(ChronoUnit.MICROS);
        var reservation = new Reservation(UUID.randomUUID(), sku, quantity, HELD, createdAt, expiresAt);
        repository.insert(reservation);
        repository.linkRequest(key, reservation.id());
        return new Creation(reservation, true);
    }

    private Transition transition(UUID id, ReservationStatus requested) {
        Transition result = transactions.execute(ignored -> transitionInTransaction(id, requested));
        if (result.newlyExpired()) {
            expired.increment();
        }
        return result;
    }

    private Transition transitionInTransaction(UUID id, ReservationStatus requested) {
        Reservation current = repository.lock(id).orElseThrow(ReservationService::reservationNotFound);
        ReservationStatus next = current.status().at(now(), current.expiresAt());
        if (next == HELD && requested != EXPIRED) {
            next = requested;
        }
        if (current.status() == next) {
            return new Transition(current, false);
        }

        repository.setStatus(id, next);
        if (next == CANCELLED || next == EXPIRED) {
            repository.credit(current.sku(), current.quantity());
        }
        return new Transition(current.withStatus(next), next == EXPIRED);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiProblem reservationNotFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "A reserva informada não existe.");
    }

    record Creation(Reservation reservation, boolean created) {}

    private record Transition(Reservation reservation, boolean newlyExpired) {}
}
