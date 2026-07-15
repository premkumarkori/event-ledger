package com.eventledger.integration;

import com.eventledger.account.AccountServiceApplication;
import com.eventledger.gateway.EventGatewayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EventLedgerAcceptanceTest {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FUTURE_TIMEOUT = Duration.ofSeconds(30);
    private static final String EVENT_TIME = "2026-05-15T14:02:11Z";
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("150.00");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ConfigurableApplicationContext accountContext;
    private static ConfigurableApplicationContext gatewayContext;
    private static int accountPort;
    private static int gatewayPort;
    private static String accountJdbcUrl;
    private static String gatewayJdbcUrl;

    @BeforeAll
    static void startBothServices() {
        try {
            accountContext = new SpringApplicationBuilder(AccountServiceApplication.class)
                    .run("--spring.config.location=classpath:/account-acceptance.yml");
            accountPort = localPort(accountContext);
            accountJdbcUrl = jdbcUrl(accountContext);

            gatewayContext = new SpringApplicationBuilder(EventGatewayApplication.class)
                    .run(
                            "--spring.config.location=classpath:/gateway-acceptance.yml",
                            "--clients.account.base-url=http://127.0.0.1:" + accountPort);
            gatewayPort = localPort(gatewayContext);
            gatewayJdbcUrl = jdbcUrl(gatewayContext);
        } catch (RuntimeException startupFailure) {
            closeContext(gatewayContext);
            closeContext(accountContext);
            accountContext = null;
            gatewayContext = null;
            throw startupFailure;
        }
    }

    @AfterAll
    static void stopBothServices() {
        closeContext(gatewayContext);
        closeContext(accountContext);
        gatewayContext = null;
        accountContext = null;
    }

    @Test
    void submitEvent_shouldApplyOneFinancialEffect_whenEventIsSubmittedAndReplayed() throws Exception {
        String accountId = "acct-acceptance-1";
        String eventId = "evt-acceptance-1";
        String body = eventBody(eventId, accountId);

        HttpResponse<String> created = postEvent(body);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json(created).path("applicationStatus").asText()).isEqualTo("APPLIED");

        HttpResponse<String> replayed = postEvent(body);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.headers().firstValue("X-Idempotent-Replay")).contains("true");
        assertThat(json(replayed).path("applicationStatus").asText()).isEqualTo("APPLIED");

        HttpResponse<String> stored = getEvent(eventId);
        assertThat(stored.statusCode()).isEqualTo(200);
        JsonNode event = json(stored);
        assertThat(event.path("eventId").asText()).isEqualTo(eventId);
        assertThat(event.path("accountId").asText()).isEqualTo(accountId);
        assertThat(event.path("type").asText()).isEqualTo("CREDIT");
        assertThat(new BigDecimal(event.path("amount").asText())).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(event.path("currency").asText()).isEqualTo("USD");
        assertThat(event.path("eventTimestamp").asText()).isEqualTo(EVENT_TIME);
        assertThat(event.path("metadata").isObject()).isTrue();
        assertThat(event.path("metadata").isEmpty()).isTrue();
        assertThat(event.path("applicationStatus").asText()).isEqualTo("APPLIED");

        HttpResponse<String> balance = getBalance(accountId);
        assertThat(balance.statusCode()).isEqualTo(200);
        JsonNode balanceBody = json(balance);
        assertThat(balanceBody.path("accountId").asText()).isEqualTo(accountId);
        assertThat(balanceBody.path("currency").asText()).isEqualTo("USD");
        assertThat(new BigDecimal(balanceBody.path("balance").asText()))
                .isEqualByComparingTo(CREDIT_AMOUNT);

        assertThat(gatewayEventCount(eventId)).isEqualTo(1);
        assertThat(gatewayApplicationStatus(eventId)).isEqualTo("APPLIED");
        assertThat(accountTransactionCount(eventId)).isEqualTo(1);
        assertThat(accountTransactionAmount(eventId)).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(accountFinancialEffect(accountId)).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(accountJdbcUrl).isNotBlank();
        assertThat(gatewayJdbcUrl).isNotBlank();
        assertThat(accountJdbcUrl).isNotEqualTo(gatewayJdbcUrl);
        assertThat(accountPort).isPositive();
        assertThat(gatewayPort).isPositive();
        assertThat(accountPort).isNotEqualTo(gatewayPort);
    }

    @Test
    void submitEvent_shouldApplyOneFinancialEffect_whenIdenticalRequestsArriveConcurrently()
            throws Exception {
        String accountId = "acct-acceptance-concurrent";
        String eventId = "evt-acceptance-concurrent";
        String body = eventBody(eventId, accountId);

        CyclicBarrier releaseTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                releaseTogether.await(FUTURE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                return postEvent(body);
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                releaseTogether.await(FUTURE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                return postEvent(body);
            });
            responses.add(first.get(FUTURE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            responses.add(second.get(FUTURE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();
        assertThat(statuses).containsExactlyInAnyOrder(201, 200);
        assertThat(statuses).noneMatch(status -> status >= 500);
        assertThat(responses).allSatisfy(response ->
                assertThat(json(response).path("applicationStatus").asText()).isEqualTo("APPLIED"));

        HttpResponse<String> replayResponse = responses.stream()
                .filter(response -> response.statusCode() == 200)
                .findFirst()
                .orElseThrow();
        assertThat(replayResponse.headers().firstValue("X-Idempotent-Replay")).contains("true");

        assertThat(gatewayEventCount(eventId)).isEqualTo(1);
        assertThat(gatewayApplicationStatus(eventId)).isEqualTo("APPLIED");
        assertThat(accountTransactionCount(eventId)).isEqualTo(1);
        assertThat(accountTransactionAmount(eventId)).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(accountFinancialEffect(accountId)).isEqualByComparingTo(CREDIT_AMOUNT);

        HttpResponse<String> balance = getBalance(accountId);
        assertThat(balance.statusCode()).isEqualTo(200);
        assertThat(new BigDecimal(json(balance).path("balance").asText()))
                .isEqualByComparingTo(CREDIT_AMOUNT);
    }

    private static String eventBody(String eventId, String accountId) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s"}"""
                .formatted(eventId, accountId, EVENT_TIME);
    }

    private static HttpResponse<String> postEvent(String body) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(gatewayUrl("/events")))
                        .header("Content-Type", "application/json")
                        .timeout(HTTP_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getEvent(String eventId) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(gatewayUrl("/events/" + eventId)))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getBalance(String accountId) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(gatewayUrl("/accounts/" + accountId + "/balance")))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String gatewayUrl(String path) {
        return "http://127.0.0.1:" + gatewayPort + path;
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static int gatewayEventCount(String eventId) {
        Integer count = gatewayJdbc().queryForObject(
                "select count(*) from gateway_events where event_id = ?",
                Integer.class,
                eventId);
        return count == null ? 0 : count;
    }

    private static int accountTransactionCount(String eventId) {
        Integer count = accountJdbc().queryForObject(
                "select count(*) from account_transactions where event_id = ?",
                Integer.class,
                eventId);
        return count == null ? 0 : count;
    }

    private static String gatewayApplicationStatus(String eventId) {
        return gatewayJdbc().queryForObject(
                "select application_status from gateway_events where event_id = ?",
                String.class,
                eventId);
    }

    private static BigDecimal accountTransactionAmount(String eventId) {
        return accountJdbc().queryForObject(
                "select amount from account_transactions where event_id = ?",
                BigDecimal.class,
                eventId);
    }

    private static BigDecimal accountFinancialEffect(String accountId) {
        return accountJdbc().queryForObject(
                """
                        select coalesce(sum(case when event_type = 'CREDIT' then amount else -amount end), 0)
                        from account_transactions
                        where account_id = ?
                        """,
                BigDecimal.class,
                accountId);
    }

    private static JdbcTemplate accountJdbc() {
        return new JdbcTemplate(accountContext.getBean(DataSource.class));
    }

    private static JdbcTemplate gatewayJdbc() {
        return new JdbcTemplate(gatewayContext.getBean(DataSource.class));
    }

    private static int localPort(ConfigurableApplicationContext context) {
        if (!(context instanceof WebApplicationContext webContext)) {
            throw new IllegalStateException("expected a web application context");
        }
        String port = webContext.getEnvironment().getProperty("local.server.port");
        if (port == null || port.isBlank()) {
            throw new IllegalStateException("local.server.port was not published");
        }
        return Integer.parseInt(port);
    }

    private static String jdbcUrl(ConfigurableApplicationContext context) {
        DataSource dataSource = context.getBean(DataSource.class);
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not read JDBC URL", failure);
        }
    }

    private static void closeContext(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }
}
