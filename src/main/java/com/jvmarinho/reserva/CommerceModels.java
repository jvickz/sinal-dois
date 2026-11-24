package com.jvmarinho.reserva;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CommerceModels {
    private CommerceModels() {}

    enum Status {
        AWAITING_PAYMENT("Pedido recebido"),
        PAID("Pagamento aprovado"),
        PICKING("Pedido em separação"),
        SHIPPED("Pedido expedido"),
        PAYMENT_FAILED("Pagamento recusado"),
        CANCELLED("Pedido cancelado"),
        EXPIRED("Prazo de pagamento expirado");

        private final String label;

        Status(String label) { this.label = label; }

        String label() { return label; }

        boolean releasesStock() {
            return this == PAYMENT_FAILED || this == CANCELLED || this == EXPIRED;
        }
    }

    enum Outcome { APPROVED, DECLINED }

    record Product(String sku, String name, String description, String category,
                   int priceCents, int available, String imageKey, String brand, List<String> specs) {}

    record Header(UUID id, UUID buyerId, Status status, long totalCents, Instant createdAt, Instant expiresAt) {
        Header withStatus(Status next) {
            return new Header(id, buyerId, next, totalCents, createdAt, expiresAt);
        }
    }

    record Item(String sku, String name, int quantity, int unitPriceCents) {}

    record RequestedItem(String sku, int quantity) {}

    record History(String type, String label, Instant at) {}

    record Order(UUID id, Status status, List<Item> items, long totalCents, Instant createdAt, Instant expiresAt,
                 List<History> timeline) {}

    record Payment(UUID id, UUID orderId, Outcome outcome, Instant processedAt) {}

    record Checkout(Order order, boolean created) {}
}
