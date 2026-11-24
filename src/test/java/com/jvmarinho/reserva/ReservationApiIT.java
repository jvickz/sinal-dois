package com.jvmarinho.reserva;

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

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "reservation.expiry.enabled=false")
@Import(ReservationApiIT.ClockConfiguration.class)
class ReservationApiIT {
    private static final Instant START = Instant.parse("2026-11-23T12:00:00Z");
    private static final String RESERVATIONS = "/api/v1/reservations";
    private static final String INVENTORY = "/api/v1/inventory/CAFE-250G";
    private static final String ONE_UNIT = """
            {"sku":"CAFE-250G","quantity":1}
            """;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    DataSource dataSource;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = new JsonMapper();

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM reservation_requests").update();
        jdbc.sql("DELETE FROM reservations").update();
        jdbc.sql("DELETE FROM inventory").update();
        jdbc.sql("INSERT INTO inventory (sku, available) VALUES ('CAFE-250G', 1)").update();
        clock.set(START);
    }

    @Test
    void simultaneousBuyersCannotReserveTheSameUnit() throws Exception {
        var responses = race(create("buyer-a", ONE_UNIT), create("buyer-b", ONE_UNIT));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        var refused = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        assertProblem(refused, 409, "INSUFFICIENT_STOCK");
        assertStock(0);
        assertThat(jdbc.sql("SELECT count(*) FROM reservations").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void simultaneousRetriesReturnTheSameReservationAndDebitOnce() throws Exception {
        var responses = race(create("same-command", ONE_UNIT), create("same-command", ONE_UNIT));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200);
        assertThat(body(responses.getFirst())).isEqualTo(body(responses.getLast()));
        assertStock(0);
        assertProblem(send(create("same-command", """
                {"sku":"UNKNOWN","quantity":1}
                """)), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void refusedCreationCanBeRetriedAfterCancellation() throws Exception {
        String first = createAndLocate("original");
        assertProblem(send(create("retry-after-refusal", ONE_UNIT)), 409, "INSUFFICIENT_STOCK");

        assertState(send(command(first + "/cancel")), "CANCELLED");
        assertState(send(command(first + "/cancel")), "CANCELLED");
        assertStock(1);
        assertThat(send(create("retry-after-refusal", ONE_UNIT)).statusCode()).isEqualTo(201);
        assertStock(0);
    }

    @Test
    void confirmingKeepsTheDebitAndReplayReturnsTheCurrentState() throws Exception {
        String location = createAndLocate("confirm");
        var original = send(get(location));
        assertState(original, "HELD");
        assertThat(body(original).path("expiresAt").asString()).isEqualTo("2026-11-23T12:02:00Z");

        assertState(send(command(location + "/confirm")), "CONFIRMED");
        assertState(send(command(location + "/confirm")), "CONFIRMED");
        assertState(send(create("confirm", ONE_UNIT)), "CONFIRMED");
        assertProblem(send(command(location + "/cancel")), 409, "RESERVATION_STATE_CONFLICT");
        assertStock(0);
    }

    @Test
    void confirmationAtTheDeadlinePersistsExpiryEvenWhenItReturnsConflict() throws Exception {
        String location = createAndLocate("deadline");
        clock.set(START.plusSeconds(120));

        assertState(send(get(location)), "HELD");
        assertProblem(send(create("blocked-until-expiry", ONE_UNIT)), 409, "INSUFFICIENT_STOCK");
        assertProblem(send(command(location + "/confirm")), 409, "RESERVATION_STATE_CONFLICT");
        assertState(send(get(location)), "EXPIRED");
        assertProblem(send(command(location + "/cancel")), 409, "RESERVATION_STATE_CONFLICT");
        assertStock(1);
        assertThat(send(create("blocked-until-expiry", ONE_UNIT)).statusCode()).isEqualTo(201);
    }

    @Test
    void confirmationAndCancellationHaveOneWinner() throws Exception {
        String location = createAndLocate("race-transition");
        var responses = race(command(location + "/confirm"), command(location + "/cancel"));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        String state = body(send(get(location))).path("status").asString();
        assertThat(state).isIn("CONFIRMED", "CANCELLED");
        assertStock(state.equals("CANCELLED") ? 1 : 0);
    }

    @Test
    void confirmationThatWaitedForALockUsesTheTimeAfterAcquiringIt() throws Exception {
        String location = createAndLocate("deadline-during-lock");
        var id = UUID.fromString(location.substring(RESERVATIONS.length() + 1));
        clock.set(START.plusSeconds(119));

        try (var blocker = dataSource.getConnection();
             var lock = blocker.prepareStatement("SELECT id FROM reservations WHERE id = ? FOR UPDATE")) {
            blocker.setAutoCommit(false);
            lock.setObject(1, id);
            try (var row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
            }

            var response = http.sendAsync(command(location + "/confirm"), HttpResponse.BodyHandlers.ofString());
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()
                    AND wait_event_type = 'Lock' AND query LIKE '%FROM reservations WHERE id = % FOR UPDATE%')
                    """).query(Boolean.class).single());

            clock.set(START.plusSeconds(120));
            blocker.commit();
            assertProblem(response.get(5, TimeUnit.SECONDS), 409, "RESERVATION_STATE_CONFLICT");
        }
        assertState(send(get(location)), "EXPIRED");
        assertStock(1);
    }

    @Test
    void restartedWorkerExpiresPersistedReservationsAndReturnsTheUnitOnce() throws Exception {
        String location = createAndLocate("worker-restart");
        assertStock(0);

        try (var restarted = new SpringApplicationBuilder(StockReservationApplication.class, ClockConfiguration.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--reservation.expiry.interval=100ms", "--spring.main.banner-mode=off")) {
            restarted.getBean(TestClock.class).set(START.plusSeconds(121));
            await().atMost(Duration.ofSeconds(7)).untilAsserted(() -> assertState(send(get(location)), "EXPIRED"));
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertStock(1));
        }
        assertProblem(send(command(location + "/confirm")), 409, "RESERVATION_STATE_CONFLICT");
    }

    @Test
    void swaggerDescribesCreationReplayAndConflict() throws Exception {
        var ui = send(get("/swagger-ui/index.html"));
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");

        var document = send(get("/v3/api-docs"));
        assertThat(document.statusCode()).isEqualTo(200);
        var create = body(document).at("/paths/~1api~1v1~1reservations/post");
        assertThat(create.path("responses").propertyNames()).contains("201", "200", "409");
        assertThat(create.path("parameters").toString()).contains("Idempotency-Key", "checkout-001");
    }

    @Test
    void actuatorReportsHealthAndCountsOnlyCommittedCreations() throws Exception {
        var health = send(get("/actuator/health"));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(body(health).path("status").asString()).isEqualTo("UP");

        String metric = "/actuator/metrics/inventory.reservations.events?tag=result:created";
        double before = body(send(get(metric))).at("/measurements/0/value").asDouble();
        createAndLocate("metrics");
        assertState(send(create("metrics", ONE_UNIT)), "HELD");
        var after = send(get(metric));
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(body(after).at("/measurements/0/value").asDouble()).isEqualTo(before + 1);
    }

    @Test
    void invalidInputAndMissingResourcesReturnProblemDetails() throws Exception {
        assertProblem(send(create(null, ONE_UNIT)), 400, "INVALID_REQUEST");
        assertProblem(send(create("bad key", ONE_UNIT)), 400, "INVALID_REQUEST");
        for (String quantity : List.of("0", "-1", "1.5", "\"1\"", "null")) {
            assertProblem(send(create("invalid", "{\"sku\":\"CAFE-250G\",\"quantity\":" + quantity + "}")),
                    400, "INVALID_REQUEST");
        }
        assertProblem(send(get(RESERVATIONS + "/invalid-uuid")), 400, "INVALID_REQUEST");
        assertProblem(send(get(RESERVATIONS + "/00000000-0000-0000-0000-000000000000")),
                404, "RESERVATION_NOT_FOUND");
        assertProblem(send(create("missing-sku", ONE_UNIT.replace("CAFE-250G", "UNKNOWN"))), 404, "SKU_NOT_FOUND");
        assertProblem(send(get("/api/v1/inventory/UNKNOWN")), 404, "SKU_NOT_FOUND");
        assertStock(1);
    }

    @Test
    void abbreviatedReservationIdsReturnBadRequest() throws Exception {
        String abbreviated = RESERVATIONS + "/0-0-0-0-0";
        assertProblem(send(get(abbreviated)), 400, "INVALID_REQUEST");
        assertProblem(send(command(abbreviated + "/confirm")), 400, "INVALID_REQUEST");
        assertProblem(send(command(abbreviated + "/cancel")), 400, "INVALID_REQUEST");
    }

    @Test
    void skuMustBeAJsonStringEvenWhenItsNumberMatchesAnExistingSku() throws Exception {
        jdbc.sql("INSERT INTO inventory (sku, available) VALUES ('123', 1)").update();

        assertProblem(send(create("numeric-sku", "{\"sku\":123,\"quantity\":1}")), 400, "INVALID_REQUEST");
        assertStock("/api/v1/inventory/123", 1);
        assertThat(send(create("numeric-sku", "{\"sku\":\"123\",\"quantity\":1}")).statusCode()).isEqualTo(201);
    }

    @Test
    void cancellationRestoresTheFullReservedQuantityOnce() throws Exception {
        jdbc.sql("UPDATE inventory SET available = 5 WHERE sku = 'CAFE-250G'").update();
        String threeUnits = ONE_UNIT.replace("\"quantity\":1", "\"quantity\":3");
        var created = send(create("three-units", threeUnits));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created).path("quantity").asInt()).isEqualTo(3);
        assertStock(2);

        assertState(send(create("three-units", threeUnits)), "HELD");
        assertProblem(send(create("three-units", ONE_UNIT)), 409, "IDEMPOTENCY_KEY_REUSED");
        assertStock(2);

        String cancel = created.headers().firstValue("Location").orElseThrow() + "/cancel";
        assertState(send(command(cancel)), "CANCELLED");
        assertState(send(command(cancel)), "CANCELLED");
        assertStock(5);
    }

    private String createAndLocate(String key) throws Exception {
        var response = send(create(key, ONE_UNIT));
        assertThat(response.statusCode()).isEqualTo(201);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo(RESERVATIONS + "/" + body(response).path("id").asString());
        return location;
    }

    private void assertStock(int amount) throws Exception {
        assertStock(INVENTORY, amount);
    }

    private void assertStock(String path, int amount) throws Exception {
        var response = send(get(path));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("available").asInt()).isEqualTo(amount);
    }

    private void assertState(HttpResponse<String> response, String state) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("status").asString()).isEqualTo(state);
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));
        assertThat(body(response).path("code").asString()).isEqualTo(code);
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<HttpResponse<String>> race(HttpRequest first, HttpRequest second) throws Exception {
        var start = new CompletableFuture<Void>();
        var one = start.thenCompose(ignored -> http.sendAsync(first, HttpResponse.BodyHandlers.ofString()));
        var two = start.thenCompose(ignored -> http.sendAsync(second, HttpResponse.BodyHandlers.ofString()));
        start.complete(null);
        return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
    }

    private HttpRequest create(String key, String body) {
        var request = request(RESERVATIONS).header("Content-Type", "application/json");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest command(String path) {
        return request(path).POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("O teste usa UTC");
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
