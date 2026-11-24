package com.jvmarinho.reserva;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "commerce.worker.enabled=false"})
@Import(CommerceApiIT.ClockConfig.class)
class CommerceApiIT {
    private static final String ORDERS = "/orders";
    private static final Instant START = Instant.parse("2026-11-23T15:00:00Z");
    private static final String CAFE = "{\"items\":[{\"sku\":\"CAFE-250G\",\"quantity\":1}]}";
    private static final String FILTERS = "{\"items\":[{\"sku\":\"FILTRO-01\",\"quantity\":2}]}";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CommerceService commerce;

    @BeforeEach
    void reset() {
        clock.set(START);
        jdbc.sql("TRUNCATE commerce_payment_events, commerce_order_history, commerce_order_items, "
                + "commerce_checkout_requests, commerce_orders").update();
        jdbc.sql("UPDATE inventory SET available = CASE sku WHEN 'CAFE-250G' THEN 1 ELSE 10 END "
                + "WHERE sku IN ('CAFE-250G', 'FILTRO-01')").update();
    }

    @Test
    void twoIndependentBuyersShareTheLastUnit() throws Exception {
        try (var first = new Browser(); var second = new Browser()) {
            first.start();
            second.start();
            var one = first.async("POST", ORDERS, CAFE, "last-item");
            var two = second.async("POST", ORDERS, CAFE, "last-item");
            var results = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
            var losing = results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertThat(json(losing).path("code").asString()).isEqualTo("INSUFFICIENT_STOCK");
            assertThat(stock("CAFE-250G")).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM commerce_orders").query(Long.class).single()).isEqualTo(1);
        }
    }

    @Test
    void unavailableLineRollsBackTheWholeBasketAndKeepsTheRequestKeyRetryable() throws Exception {
        try (var buyer = new Browser()) {
            buyer.start();
            var unavailableBasket = """
                    {"items":[{"sku":"CAFE-250G","quantity":1},{"sku":"FILTRO-01","quantity":11}]}
                    """;
            assertThat(buyer.send("POST", ORDERS, unavailableBasket, "retry").statusCode()).isEqualTo(409);
            assertThat(stock("CAFE-250G")).isEqualTo(1);
            assertThat(stock("FILTRO-01")).isEqualTo(10);
            var availableBasket = unavailableBasket.replace("11", "2");
            var created = buyer.send("POST", ORDERS, availableBasket, "retry");
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(json(created).path("items").size()).isEqualTo(2);
            var replay = buyer.send("POST", ORDERS, availableBasket, "retry");
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(json(replay).path("id")).isEqualTo(json(created).path("id"));
            assertThat(stock("FILTRO-01")).isEqualTo(8);
            assertThat(buyer.send("POST", ORDERS, FILTERS, "retry").statusCode()).isEqualTo(409);
        }
    }

    @Test
    void duplicatedPaymentProducesOneTransitionAndOperatorCanShipTheOrder() throws Exception {
        try (var buyer = new Browser(); var operator = new Browser()) {
            buyer.start();
            operator.start();
            String id = buyer.create(CAFE);
            assertThat(operator.send("GET", ORDERS + "/" + id, null, null).statusCode()).isEqualTo(404);
            assertThat(operator.send("GET", "/operator/orders", null, null).statusCode()).isEqualTo(401);
            assertThat(operator.send("POST", "/operator/orders/" + id + "/ship", null, null).statusCode()).isEqualTo(401);
            operator.login();
            assertThat(operator.send("POST", "/operator/orders/" + id + "/ship", null, null).statusCode()).isEqualTo(409);

            String event = payment("APPROVED");
            assertThat(buyer.send("POST", ORDERS + "/" + id + "/payment-events", event, null).statusCode()).isEqualTo(202);
            assertThat(buyer.send("POST", ORDERS + "/" + id + "/payment-events", event, null).statusCode()).isEqualTo(202);
            assertThat(buyer.send("POST", ORDERS + "/" + id + "/payment-events",
                    event.replace("APPROVED", "DECLINED"), null).statusCode()).isEqualTo(409);
            process(event);
            process(event);
            assertThat(buyer.status(id)).isEqualTo("PAID");
            assertThat(jdbc.sql("SELECT count(*) FROM commerce_payment_events").query(Long.class).single()).isEqualTo(1);
            assertThat(operator.send("POST", "/operator/orders/" + id + "/pick", null, null).statusCode()).isEqualTo(200);
            assertThat(operator.send("POST", "/operator/orders/" + id + "/ship", null, null).statusCode()).isEqualTo(200);
            var order = json(buyer.send("GET", ORDERS + "/" + id, null, null));
            assertThat(order.path("status").asString()).isEqualTo("SHIPPED");
            assertThat(order.path("timeline").size()).isEqualTo(4);
            assertThat(stock("CAFE-250G")).isZero();
        }
    }

    @Test
    void rejectionAndExpiryReleaseTheWholeOrderExactlyOnce() throws Exception {
        try (var buyer = new Browser()) {
            buyer.start();
            String declined = buyer.create(FILTERS);
            String event = payment("DECLINED");
            assertThat(buyer.send("POST", ORDERS + "/" + declined + "/payment-events", event, null).statusCode()).isEqualTo(202);
            assertThat(buyer.send("POST", ORDERS + "/" + declined + "/payment-events", event, null).statusCode()).isEqualTo(202);
            process(event);
            process(event);
            assertThat(buyer.status(declined)).isEqualTo("PAYMENT_FAILED");
            assertThat(stock("FILTRO-01")).isEqualTo(10);

            String expired = buyer.create(FILTERS);
            clock.set(START.plusSeconds(120));
            String lateApproval = payment("APPROVED");
            assertThat(buyer.send("POST", ORDERS + "/" + expired + "/payment-events", lateApproval, null)
                    .statusCode()).isEqualTo(202);
            process(lateApproval);
            var order = json(buyer.send("GET", ORDERS + "/" + expired, null, null));
            assertThat(order.path("status").asString()).isEqualTo("EXPIRED");
            assertThat(order.path("timeline").size()).isEqualTo(3);
            assertThat(stock("FILTRO-01")).isEqualTo(10);
        }
    }

    @Test
    void anotherInstanceRecoversQueuedApprovalsAndKeepsTheBuyerSession() throws Exception {
        try (var buyer = new Browser()) {
            buyer.start();
            String older = buyer.create(FILTERS);
            clock.set(START.plusSeconds(60));
            String newer = buyer.create(FILTERS);
            for (String id : List.of(older, newer)) {
                assertThat(buyer.send("POST", ORDERS + "/" + id + "/payment-events", payment("APPROVED"), null)
                        .statusCode()).isEqualTo(202);
                assertThat(buyer.status(id)).isEqualTo("AWAITING_PAYMENT");
            }
            assertThat(jdbc.sql("SELECT count(*) FROM commerce_payment_events WHERE processed_at IS NULL")
                    .query(Long.class).single()).isEqualTo(2);

            try (var restarted = new SpringApplicationBuilder(StockReservationApplication.class)
                    .web(WebApplicationType.SERVLET).profiles("demo")
                    .initializers(context -> ((GenericApplicationContext) context).registerBean("recoveryClock", Clock.class,
                            () -> Clock.fixed(START.plusSeconds(120), ZoneOffset.UTC), bean -> bean.setPrimary(true)))
                    .run("--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                            "--spring.datasource.username=" + POSTGRES.getUsername(),
                            "--spring.datasource.password=" + POSTGRES.getPassword(), "--commerce.worker.interval=100ms")) {
                buyer.destinationPort = ((WebServerApplicationContext) restarted).getWebServer().getPort();
                await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
                    assertThat(buyer.status(newer)).isEqualTo("PAID");
                    var expired = json(buyer.send("GET", ORDERS + "/" + older, null, null));
                    assertThat(expired.path("status").asString()).isEqualTo("EXPIRED");
                    assertThat(expired.at("/timeline/2/type").asString()).isEqualTo("PAYMENT_IGNORED");
                    assertThat(stock("FILTRO-01")).isEqualTo(8);
                });
            }
        }
    }

    @Test
    void sessionGuardsMutationsAndCancellationRestocks() throws Exception {
        try (var buyer = new Browser()) {
            buyer.start();
            assertThat(buyer.client.send(buyer.request("POST", ORDERS, CAFE, "no-csrf", false),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            String id = buyer.create(CAFE);
            assertThat(stock("CAFE-250G")).isZero();
            assertThat(buyer.send("POST", ORDERS + "/" + id + "/cancel", null, null).statusCode()).isEqualTo(200);
            assertThat(buyer.send("POST", ORDERS + "/" + id + "/cancel", null, null).statusCode()).isEqualTo(200);
            assertThat(buyer.status(id)).isEqualTo("CANCELLED");
            assertThat(stock("CAFE-250G")).isEqualTo(1);
        }
    }

    private int stock(String sku) {
        return jdbc.sql("SELECT available FROM inventory WHERE sku = :sku").param("sku", sku).query(Integer.class).single();
    }

    private static String payment(String outcome) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"outcome\":\"" + outcome + "\"}";
    }

    private void process(String event) {
        commerce.processPayment(UUID.fromString(new JsonMapper().readTree(event).path("eventId").asString()));
    }

    private static JsonNode json(HttpResponse<String> response) {
        return new JsonMapper().readTree(response.body());
    }

    private final class Browser implements AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String csrf;
        private int destinationPort = port;

        void start() throws Exception {
            var response = send("GET", "/session", null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            csrf = json(response).path("csrfToken").asString();
        }

        void login() throws Exception {
            var response = send("POST", "/operator/session", """
                    {"username":"operador","password":"demo-operacoes"}
                    """, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("operator").asBoolean()).isTrue();
            csrf = json(response).path("csrfToken").asString();
        }

        String create(String body) throws Exception {
            var response = send("POST", ORDERS, body, UUID.randomUUID().toString());
            assertThat(response.statusCode()).isEqualTo(201);
            return json(response).path("id").asString();
        }

        String status(String id) throws Exception {
            return json(send("GET", ORDERS + "/" + id, null, null)).path("status").asString();
        }

        HttpResponse<String> send(String method, String path, String body, String key) throws Exception {
            return client.send(request(method, path, body, key, true), HttpResponse.BodyHandlers.ofString());
        }

        CompletableFuture<HttpResponse<String>> async(String method, String path, String body, String key) {
            return client.sendAsync(request(method, path, body, key, true), HttpResponse.BodyHandlers.ofString());
        }

        HttpRequest request(String method, String path, String body, String key, boolean withCsrf) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + destinationPort + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(8));
            if (body != null) request.header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            if (withCsrf && csrf != null) request.header("X-CSRF-Token", csrf);
            return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build();
        }

        @Override
        public void close() {
            client.close();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean @Primary
        MutableClock commerceTestClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void set(Instant instant) { now.set(instant); }

        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
    }
}
