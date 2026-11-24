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

import static com.jvmarinho.reserva.CommerceModels.*;

@Repository
class CommerceRepository {
    private static final String CATALOG = """
            SELECT i.sku, c.name, c.description, c.category, c.price_cents, i.available, c.image_key, c.brand, c.specs
            FROM commerce_catalog c JOIN inventory i ON i.sku = c.sku
            WHERE c.active
            """;
    private final JdbcClient jdbc;

    CommerceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Product> catalog() {
        return jdbc.sql(CATALOG + "ORDER BY c.display_order, c.sku")
                .query(CommerceRepository::product).list();
    }

    Optional<Product> product(String sku) {
        return jdbc.sql(CATALOG + "AND c.sku = :sku").param("sku", sku).query(CommerceRepository::product).optional();
    }

    Optional<UUID> claimCheckout(UUID buyerId, String key) {
        jdbc.sql("""
                INSERT INTO commerce_checkout_requests (buyer_id, request_key) VALUES (:buyer, :key)
                ON CONFLICT (buyer_id, request_key) DO NOTHING
                """).param("buyer", buyerId).param("key", key).update();
        return jdbc.sql("""
                SELECT order_id FROM commerce_checkout_requests WHERE buyer_id = :buyer AND request_key = :key FOR UPDATE
                """).param("buyer", buyerId).param("key", key)
                .query((row, ignored) -> Optional.ofNullable(row.getObject("order_id", UUID.class))).single();
    }

    void linkCheckout(UUID buyerId, String key, UUID orderId) {
        jdbc.sql("""
                UPDATE commerce_checkout_requests SET order_id = :id WHERE buyer_id = :buyer AND request_key = :key
                """).param("buyer", buyerId).param("key", key).param("id", orderId).update();
    }

    void insertOrder(Header order, List<Item> items) {
        jdbc.sql("""
                INSERT INTO commerce_orders (id, buyer_id, status, total_cents, created_at, expires_at)
                VALUES (:id, :buyer, :status, :total, :created, :expires)
                """).param("id", order.id()).param("buyer", order.buyerId()).param("status", order.status().name())
                .param("total", order.totalCents()).param("created", utc(order.createdAt()))
                .param("expires", utc(order.expiresAt())).update();
        for (Item item : items) {
            jdbc.sql("""
                    INSERT INTO commerce_order_items (order_id, sku, name, quantity, unit_price_cents)
                    VALUES (:order, :sku, :name, :quantity, :price)
                    """).param("order", order.id()).param("sku", item.sku()).param("name", item.name())
                    .param("quantity", item.quantity()).param("price", item.unitPriceCents()).update();
        }
    }

    Optional<Header> order(UUID id) {
        return jdbc.sql("SELECT * FROM commerce_orders WHERE id = :id")
                .param("id", id).query(CommerceRepository::header).optional();
    }

    Optional<Header> lockOrder(UUID id) {
        return jdbc.sql("SELECT * FROM commerce_orders WHERE id = :id FOR UPDATE")
                .param("id", id).query(CommerceRepository::header).optional();
    }

    List<Header> orders(UUID buyerId) {
        if (buyerId == null) {
            return jdbc.sql("SELECT * FROM commerce_orders ORDER BY created_at DESC, id DESC")
                    .query(CommerceRepository::header).list();
        }
        return jdbc.sql("SELECT * FROM commerce_orders WHERE buyer_id = :buyer ORDER BY created_at DESC, id DESC")
                .param("buyer", buyerId).query(CommerceRepository::header).list();
    }

    List<Item> items(UUID orderId) {
        return jdbc.sql("""
                SELECT sku, name, quantity, unit_price_cents FROM commerce_order_items WHERE order_id = :id ORDER BY sku
                """).param("id", orderId).query((row, ignored) -> new Item(row.getString("sku"), row.getString("name"),
                row.getInt("quantity"), row.getInt("unit_price_cents"))).list();
    }

    Order details(Header order) {
        var history = jdbc.sql("""
                SELECT type, label, happened_at FROM commerce_order_history WHERE order_id = :id ORDER BY sequence
                """).param("id", order.id()).query((row, ignored) -> new History(row.getString("type"),
                row.getString("label"), instant(row, "happened_at"))).list();
        return new Order(order.id(), order.status(), items(order.id()), order.totalCents(), order.createdAt(),
                order.expiresAt(), history);
    }

    void setStatus(UUID orderId, Status status) {
        jdbc.sql("UPDATE commerce_orders SET status = :status WHERE id = :id")
                .param("id", orderId).param("status", status.name()).update();
    }

    void appendHistory(UUID orderId, String type, String label, Instant at) {
        jdbc.sql("""
                INSERT INTO commerce_order_history (order_id, type, label, happened_at)
                VALUES (:id, :type, :label, :at)
                """).param("id", orderId).param("type", type).param("label", label).param("at", utc(at)).update();
    }

    void enqueue(UUID id, UUID orderId, Outcome outcome, Instant now) {
        jdbc.sql("""
                INSERT INTO commerce_payment_events (id, order_id, outcome, received_at)
                VALUES (:id, :order, :outcome, :now) ON CONFLICT (id) DO NOTHING
                """).param("id", id).param("order", orderId).param("outcome", outcome.name()).param("now", utc(now)).update();
    }

    Payment payment(UUID id) {
        return jdbc.sql("SELECT * FROM commerce_payment_events WHERE id = :id")
                .param("id", id).query(CommerceRepository::payment).single();
    }

    Payment lockPayment(UUID id) {
        return jdbc.sql("SELECT * FROM commerce_payment_events WHERE id = :id FOR UPDATE")
                .param("id", id).query(CommerceRepository::payment).single();
    }

    void markProcessed(UUID id, Instant now) {
        jdbc.sql("UPDATE commerce_payment_events SET processed_at = :now WHERE id = :id")
                .param("id", id).param("now", utc(now)).update();
    }

    List<UUID> pendingPayments(int limit) {
        return jdbc.sql("""
                SELECT id FROM commerce_payment_events WHERE processed_at IS NULL ORDER BY received_at, id LIMIT :limit
                """).param("limit", limit).query(UUID.class).list();
    }

    List<UUID> dueOrders(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id FROM commerce_orders WHERE status = 'AWAITING_PAYMENT' AND expires_at <= :now
                ORDER BY expires_at, id LIMIT :limit
                """).param("now", utc(now)).param("limit", limit).query(UUID.class).list();
    }

    private static Product product(ResultSet row, int ignored) throws SQLException {
        return new Product(row.getString("sku"), row.getString("name"), row.getString("description"),
                row.getString("category"), row.getInt("price_cents"), row.getInt("available"), row.getString("image_key"),
                row.getString("brand"), List.of((String[]) row.getArray("specs").getArray()));
    }

    private static Header header(ResultSet row, int ignored) throws SQLException {
        return new Header(row.getObject("id", UUID.class), row.getObject("buyer_id", UUID.class),
                Status.valueOf(row.getString("status")), row.getLong("total_cents"),
                instant(row, "created_at"), instant(row, "expires_at"));
    }

    private static Payment payment(ResultSet row, int ignored) throws SQLException {
        return new Payment(row.getObject("id", UUID.class), row.getObject("order_id", UUID.class),
                Outcome.valueOf(row.getString("outcome")), instant(row, "processed_at"));
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        OffsetDateTime value = row.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
