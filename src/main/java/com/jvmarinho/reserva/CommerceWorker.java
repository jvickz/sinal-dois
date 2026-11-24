package com.jvmarinho.reserva;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.worker.enabled", havingValue = "true", matchIfMissing = true)
class CommerceWorker {
    private static final int BATCH_SIZE = 50;
    private final CommerceRepository repository;
    private final CommerceService service;
    private final Clock clock;

    CommerceWorker(CommerceRepository repository, CommerceService service, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${commerce.worker.interval}")
    void expireOrders() {
        repository.dueOrders(clock.instant().truncatedTo(ChronoUnit.MICROS), BATCH_SIZE).forEach(service::expire);
    }

    @Scheduled(fixedDelayString = "${commerce.worker.interval}")
    void processPayments() {
        repository.pendingPayments(BATCH_SIZE).forEach(service::processPayment);
    }
}
