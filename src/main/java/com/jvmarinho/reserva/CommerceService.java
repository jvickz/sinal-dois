package com.jvmarinho.reserva;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jvmarinho.reserva.CommerceModels.*;
import static com.jvmarinho.reserva.CommerceModels.Status.*;

@Service
class CommerceService {
    private final CommerceRepository orders;
    private final ReservationRepository inventory;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;
    private final Clock clock;
    private final Duration ttl;

    CommerceService(CommerceRepository orders, ReservationRepository inventory, TransactionTemplate transactions,
                    Clock clock, @Value("${reservation.ttl}") Duration ttl) {
        this.orders = orders;
        this.inventory = inventory;
        this.transactions = transactions;
        this.clock = clock;
        this.ttl = ttl;
        snapshots = new TransactionTemplate(transactions.getTransactionManager());
        snapshots.setReadOnly(true);
        snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    List<Product> catalog() {
        return orders.catalog();
    }

    List<Order> list(UUID buyer) {
        return snapshots.execute(ignored -> orders.orders(buyer).stream().map(orders::details).toList());
    }

    Order get(UUID buyer, UUID id) {
        return snapshots.execute(ignored -> orders.details(visible(orders.order(id), buyer)));
    }

    Checkout checkout(UUID buyer, String key, List<RequestedItem> input) {
        Map<String, Integer> requested = new TreeMap<>();
        for (var line : input) {
            int quantity = requested.merge(line.sku(), line.quantity(), Integer::sum);
            if (quantity > 99) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "O limite por produto é de 99 unidades.");
            }
        }

        return transactions.execute(ignored -> checkoutInTransaction(buyer, key, requested));
    }

    private Checkout checkoutInTransaction(UUID buyer, String key, Map<String, Integer> requested) {
        var prior = orders.claimCheckout(buyer, key);
        if (prior.isPresent()) {
            Header saved = visible(orders.lockOrder(prior.get()), buyer);
            Order order = orders.details(saved);
            var savedItems = order.items().stream().collect(Collectors.toMap(Item::sku, Item::quantity));
            if (!savedItems.equals(requested)) {
                throw new ApiProblem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Esta chave já foi usada com outro carrinho.");
            }
            return new Checkout(order, false);
        }

        var items = requested.entrySet().stream().map(entry -> {
            Product product = orders.product(entry.getKey()).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                    "SKU_NOT_FOUND", "Produto " + entry.getKey() + " não encontrado no catálogo."));
            return new Item(product.sku(), product.name(), entry.getValue(), product.priceCents());
        }).toList();

        for (Item item : items) {
            if (!inventory.debitIfAvailable(item.sku(), item.quantity())) {
                throw new ApiProblem(HttpStatus.CONFLICT, ApiProblem.INSUFFICIENT_STOCK,
                        "Estoque insuficiente para " + item.name() + " (" + item.sku() + ").");
            }
        }

        Instant created = now();
        long total = items.stream().mapToLong(item -> (long) item.unitPriceCents() * item.quantity()).sum();
        Header order = new Header(UUID.randomUUID(), buyer, AWAITING_PAYMENT, total, created,
                created.plus(ttl).truncatedTo(ChronoUnit.MICROS));
        orders.insertOrder(order, items);
        orders.linkCheckout(buyer, key, order.id());
        orders.appendHistory(order.id(), order.status().name(), order.status().label(), created);
        return new Checkout(orders.details(order), true);
    }

    void queuePayment(UUID buyer, UUID orderId, UUID eventId, Outcome outcome) {
        transactions.executeWithoutResult(ignored -> {
            visible(orders.order(orderId), buyer);
            orders.enqueue(eventId, orderId, outcome, now());
            Payment event = orders.payment(eventId);
            if (!event.orderId().equals(orderId) || event.outcome() != outcome) {
                throw new ApiProblem(HttpStatus.CONFLICT, "PAYMENT_EVENT_REUSED",
                        "Este identificador de evento já pertence a outro resultado.");
            }
        });
    }

    void processPayment(UUID eventId) {
        transactions.executeWithoutResult(ignored -> {
            Payment event = orders.lockPayment(eventId);
            if (event.processedAt() != null) {
                return;
            }
            Header current = visible(orders.lockOrder(event.orderId()), null);
            Instant processedAt = now();
            Header order = expireIfDue(current, processedAt);
            if (order.status() == AWAITING_PAYMENT) {
                change(order, event.outcome() == Outcome.APPROVED ? PAID : PAYMENT_FAILED, processedAt);
            } else {
                orders.appendHistory(order.id(), "PAYMENT_IGNORED", "Evento de pagamento ignorado", processedAt);
            }
            orders.markProcessed(event.id(), processedAt);
        });
    }

    Order cancel(UUID buyer, UUID id) {
        return command(buyer, id, AWAITING_PAYMENT, CANCELLED);
    }

    Order pick(UUID id) {
        return command(null, id, PAID, PICKING);
    }

    Order ship(UUID id) {
        return command(null, id, PICKING, SHIPPED);
    }

    private Order command(UUID buyer, UUID id, Status from, Status to) {
        Order result = transactions.execute(ignored -> {
            Header locked = visible(orders.lockOrder(id), buyer);
            Instant time = now();
            Header current = expireIfDue(locked, time);
            return orders.details(current.status() == from ? change(current, to, time) : current);
        });
        if (result.status() != to) {
            throw new ApiProblem(HttpStatus.CONFLICT, "ORDER_STATE_CONFLICT",
                    "Esta operação não está disponível para a situação atual do pedido.");
        }
        return result;
    }

    void expire(UUID id) {
        transactions.executeWithoutResult(ignored -> expireIfDue(visible(orders.lockOrder(id), null), now()));
    }

    private Header expireIfDue(Header order, Instant time) {
        if (order.status() == AWAITING_PAYMENT && !time.isBefore(order.expiresAt())) {
            return change(order, EXPIRED, time);
        }
        return order;
    }

    private Header change(Header order, Status next, Instant time) {
        orders.setStatus(order.id(), next);
        if (next.releasesStock()) {
            for (Item item : orders.items(order.id())) {
                inventory.credit(item.sku(), item.quantity());
            }
        }
        orders.appendHistory(order.id(), next.name(), next.label(), time);
        return order.withStatus(next);
    }

    private static Header visible(Optional<Header> candidate, UUID buyer) {
        return candidate.filter(order -> buyer == null || order.buyerId().equals(buyer))
                .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido não encontrado."));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
