package com.jvmarinho.reserva;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "reservation.expiry.enabled", havingValue = "true", matchIfMissing = true)
class ReservationExpiryWorker {
    private static final int BATCH_SIZE = 50;

    private final ReservationRepository repository;
    private final ReservationService service;
    private final Clock clock;

    ReservationExpiryWorker(ReservationRepository repository, ReservationService service, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${reservation.expiry.interval}")
    void run() {
        var due = repository.findDue(clock.instant().truncatedTo(ChronoUnit.MICROS), BATCH_SIZE);
        due.forEach(service::expire);
    }
}
