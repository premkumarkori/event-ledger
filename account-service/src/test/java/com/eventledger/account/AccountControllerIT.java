package com.eventledger.account;

import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.persistence.AccountEntity;
import com.eventledger.account.persistence.AccountRepository;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import com.eventledger.account.support.MutableTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountControllerIT {

    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11.123456789Z");
    private static final Instant FIRST_APPLY_TIME = Instant.parse("2026-07-14T16:00:00Z");
    private static final Instant LATER_TIME = Instant.parse("2026-07-14T17:30:00Z");

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(FIRST_APPLY_TIME);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountTransactionRepository transactions;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void resetDatabaseAndClock() {
        transactions.deleteAll();
        accounts.deleteAll();
        clock.setInstant(FIRST_APPLY_TIME);
    }

    private String requestBody(
            String eventId, String type, String amount, String currency, String timestamp) {
        return """
                {"eventId":"%s","type":"%s","amount":%s,"currency":"%s","eventTimestamp":"%s"}"""
                .formatted(eventId, type, amount, currency, timestamp);
    }

    private HttpResponse<String> postTransaction(
            String accountId, String requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/accounts/" + accountId + "/transactions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode readJson(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    @Test
    void applyTransaction_shouldReturn201AndPersistAccountAndTransaction_whenFirstApply() throws Exception {
        HttpResponse<String> response = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode payload = readJson(response);
        assertThat(payload.path("eventId").asString()).isEqualTo("evt-1");
        assertThat(payload.path("accountId").asString()).isEqualTo("acct-1");
        assertThat(payload.path("type").asString()).isEqualTo("CREDIT");
        assertThat(new BigDecimal(payload.path("amount").asString())).isEqualByComparingTo("150.00");
        assertThat(payload.path("currency").asString()).isEqualTo("USD");
        assertThat(payload.path("eventTimestamp").asString()).isEqualTo(EVENT_TIME.toString());
        assertThat(payload.path("appliedAt").asString()).isEqualTo(FIRST_APPLY_TIME.toString());
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(accounts.findById("acct-1").orElseThrow().getCurrency()).isEqualTo("USD");
    }

    @Test
    void applyTransaction_shouldReturn200WithOriginalAppliedAt_whenIdenticalReplay() throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));
        clock.setInstant(LATER_TIME);

        HttpResponse<String> replay = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(readJson(replay).path("appliedAt").asString()).isEqualTo(FIRST_APPLY_TIME.toString());
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void applyTransaction_shouldTreatScaleVariantsAsIdenticalReplay_whenScaleDiffers() throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.0", "USD", EVENT_TIME.toString()));

        HttpResponse<String> replay = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void applyTransaction_shouldTreatCurrencyCaseVariantsAsIdenticalReplay_whenCurrencyIsLowercase() throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        HttpResponse<String> replay = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", "150.00", "usd", EVENT_TIME.toString()));

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @ParameterizedTest(name = "changed {0}")
    @CsvSource({
            "type,      DEBIT,  150.00, USD",
            "amount,    CREDIT, 151.00, USD",
            "currency,  CREDIT, 150.00, EUR",
    })
    void applyTransaction_shouldReturn409IdempotencyConflict_whenSemanticFieldChanges(
            String changedField, String type, String amount, String currency) throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        HttpResponse<String> conflict = postTransaction("acct-1",
                requestBody("evt-1", type, amount, currency, EVENT_TIME.toString()));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(readJson(conflict).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:idempotency-conflict");
        AccountTransactionEntity original = transactions.findById("evt-1").orElseThrow();
        assertThat(original.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(original.getAmount()).isEqualByComparingTo("150.00");
        assertThat(original.getCurrency()).isEqualTo("USD");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void applyTransaction_shouldReturn409IdempotencyConflict_whenEventTimestampChanges() throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        HttpResponse<String> conflict = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.plusNanos(1).toString()));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void applyTransaction_shouldReturn409IdempotencyConflict_whenAccountIdChanges() throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        HttpResponse<String> conflict = postTransaction("acct-2",
                requestBody("evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void applyTransaction_shouldReturn409CurrencyConflict_whenSecondEventUsesDifferentCurrency() throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        HttpResponse<String> conflict = postTransaction("acct-1",
                requestBody("evt-2", "CREDIT", "10.00", "EUR", EVENT_TIME.toString()));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(readJson(conflict).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:currency-conflict");
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(accounts.findById("acct-1").orElseThrow().getCurrency()).isEqualTo("USD");
    }

    @Test
    void applyTransaction_shouldCreateSecondTransaction_whenAccountCurrencyMatches() throws Exception {
        postTransaction("acct-1", requestBody(
                "evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        HttpResponse<String> response = postTransaction("acct-1",
                requestBody("evt-2", "DEBIT", "10.00", "usd", EVENT_TIME.toString()));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(readJson(response).path("eventId").asString()).isEqualTo("evt-2");
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(2);
        assertThat(transactions.findById("evt-2").orElseThrow().getCurrency()).isEqualTo("USD");
    }

    @ParameterizedTest(name = "rejected {0}")
    @CsvSource({
            "zero amount,          0,                          USD",
            "negative amount,      -5,                         USD",
            "21 integer digits,    123456789012345678901,      USD",
            "19 fraction digits,   1.1234567890123456789,      USD",
            "unknown currency,     150.00,                     ABC",
    })
    void applyTransaction_shouldReturn400AndPersistNothing_whenAmountOrCurrencyIsInvalid(
            String rejectedCase, String amount, String currency) throws Exception {
        HttpResponse<String> response = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", amount, currency, EVENT_TIME.toString()));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode problem = readJson(response);
        assertThat(problem.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(problem.path("errors").isArray()).isTrue();
        assertThat(problem.path("errors").size()).isGreaterThanOrEqualTo(1);
        assertThat(accounts.count()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void applyTransaction_shouldReturn400WithAccountIdField_whenPathIdentifierIsInvalid() throws Exception {
        HttpResponse<String> response = postTransaction("-bad",
                requestBody("evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).path("errors").get(0).path("field").asString())
                .isEqualTo("accountId");
        assertThat(accounts.count()).isZero();
    }

    @Test
    void applyTransaction_shouldReturn400AndPersistNothing_whenTransactionTypeIsUnknown() throws Exception {
        HttpResponse<String> response = postTransaction("acct-1",
                requestBody("evt-1", "TRANSFER", "150.00", "USD", EVENT_TIME.toString()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:validation");
        assertThat(accounts.count()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void applyTransaction_shouldReturn400AndPersistNothing_whenEventTimestampIsMalformed() throws Exception {
        HttpResponse<String> response = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", "150.00", "USD", "not-a-timestamp"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:validation");
        assertThat(accounts.count()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void applyTransaction_shouldAcceptTrailingZeroEquivalentAmount_whenAmountIsRepresentable() throws Exception {
        HttpResponse<String> response = postTransaction("acct-1",
                requestBody("evt-1", "CREDIT", "0.1000000000000000000", "USD", EVENT_TIME.toString()));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(transactions.findById("evt-1").orElseThrow().getAmount())
                .isEqualByComparingTo("0.1");
    }

    @Test
    void getBalance_shouldReturnCurrencyExactBalanceAndClockAsOf_whenAccountExists() throws Exception {
        postTransaction("acct-1", requestBody("evt-1", "CREDIT", "150.00", "USD", EVENT_TIME.toString()));
        postTransaction("acct-1", requestBody(
                "evt-2", "DEBIT", "29.50", "USD", EVENT_TIME.plusSeconds(60).toString()));
        clock.setInstant(LATER_TIME);

        HttpResponse<String> response = getResource("/accounts/acct-1/balance");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode payload = readJson(response);
        assertThat(payload.propertyNames())
                .containsExactlyInAnyOrder("accountId", "currency", "balance", "asOf");
        assertThat(payload.path("accountId").asString()).isEqualTo("acct-1");
        assertThat(payload.path("currency").asString()).isEqualTo("USD");
        assertThat(payload.path("balance").isNumber()).isTrue();
        assertThat(new BigDecimal(payload.path("balance").asString())).isEqualByComparingTo("120.50");
        assertThat(payload.path("asOf").asString()).isEqualTo(LATER_TIME.toString());
    }

    @Test
    void getDetails_shouldExposeOnlyContractFields_whenAccountExists() throws Exception {
        postTransaction("acct-1", requestBody("evt-1", "DEBIT", "24.50", "USD", EVENT_TIME.toString()));

        HttpResponse<String> response = getResource("/accounts/acct-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode payload = readJson(response);
        assertThat(payload.propertyNames())
                .containsExactlyInAnyOrder("accountId", "currency", "balance", "recentTransactions");
        assertThat(payload.path("accountId").asString()).isEqualTo("acct-1");
        assertThat(payload.path("currency").asString()).isEqualTo("USD");
        assertThat(payload.path("balance").isNumber()).isTrue();
        assertThat(new BigDecimal(payload.path("balance").asString())).isEqualByComparingTo("-24.50");
        JsonNode summary = payload.path("recentTransactions").get(0);
        assertThat(summary.propertyNames())
                .containsExactlyInAnyOrder("eventId", "type", "amount", "eventTimestamp");
        assertThat(summary.path("eventId").asString()).isEqualTo("evt-1");
        assertThat(summary.path("type").asString()).isEqualTo("DEBIT");
        assertThat(summary.path("amount").isNumber()).isTrue();
        assertThat(new BigDecimal(summary.path("amount").asString())).isEqualByComparingTo("24.50");
        assertThat(summary.path("eventTimestamp").asString()).isEqualTo(EVENT_TIME.toString());
    }

    @Test
    void getDetails_shouldReturnNewestTwentyInContractOrder_whenMoreThanTwentyEventsExist() throws Exception {
        for (int i = 1; i <= 22; i++) {
            String eventId = "evt-%03d".formatted(i);
            long secondsAfterBase = Math.min(i, 21);
            HttpResponse<String> created = postTransaction("acct-1", requestBody(
                    eventId,
                    "CREDIT",
                    "1.00",
                    "USD",
                    EVENT_TIME.plusSeconds(secondsAfterBase).toString()));
            assertThat(created.statusCode()).isEqualTo(201);
        }

        HttpResponse<String> response = getResource("/accounts/acct-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode payload = readJson(response);
        assertThat(new BigDecimal(payload.path("balance").asString()))
                .isEqualByComparingTo("22.00");
        List<String> actualEventIds = payload.path("recentTransactions").values().stream()
                .map(transaction -> transaction.path("eventId").asString())
                .toList();
        List<String> expectedEventIds = new ArrayList<>();
        expectedEventIds.add("evt-022");
        for (int i = 21; i >= 3; i--) {
            expectedEventIds.add("evt-%03d".formatted(i));
        }
        assertThat(actualEventIds).containsExactlyElementsOf(expectedEventIds);
    }

    @Test
    void getBalance_shouldReturn404ProblemDetail_whenAccountIsUnknown() throws Exception {
        HttpResponse<String> response = getResource("/accounts/acct-missing/balance");

        assertThat(response.statusCode()).isEqualTo(404);
        assertProblemDetailContentType(response);
        JsonNode problem = readJson(response);
        assertThat(problem.path("type").asString()).isEqualTo("urn:event-ledger:problem:not-found");
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("instance").asString()).isEqualTo("/accounts/acct-missing/balance");
    }

    @Test
    void getDetails_shouldReturn404ProblemDetail_whenAccountIsUnknown() throws Exception {
        HttpResponse<String> response = getResource("/accounts/acct-missing");

        assertThat(response.statusCode()).isEqualTo(404);
        assertProblemDetailContentType(response);
        JsonNode problem = readJson(response);
        assertThat(problem.path("type").asString()).isEqualTo("urn:event-ledger:problem:not-found");
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("instance").asString()).isEqualTo("/accounts/acct-missing");
    }

    @Test
    void getAccountQueries_shouldNotMutateStoredRows_whenAccountExists() throws Exception {
        postTransaction("acct-1", requestBody("evt-1", "CREDIT", "10.00", "USD", EVENT_TIME.toString()));
        AccountEntity accountBefore = accounts.findById("acct-1").orElseThrow();
        AccountTransactionEntity transactionBefore = transactions.findById("evt-1").orElseThrow();
        long accountsBefore = accounts.count();
        long transactionsBefore = transactions.count();

        HttpResponse<String> balance = getResource("/accounts/acct-1/balance");
        HttpResponse<String> details = getResource("/accounts/acct-1");

        assertThat(balance.statusCode()).isEqualTo(200);
        assertThat(details.statusCode()).isEqualTo(200);
        assertThat(accounts.count()).isEqualTo(accountsBefore);
        assertThat(transactions.count()).isEqualTo(transactionsBefore);
        assertThat(accounts.findById("acct-1").orElseThrow())
                .usingRecursiveComparison()
                .isEqualTo(accountBefore);
        assertThat(transactions.findById("evt-1").orElseThrow())
                .usingRecursiveComparison()
                .isEqualTo(transactionBefore);
    }

    private void assertProblemDetailContentType(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(contentType ->
                        assertThat(contentType).startsWith("application/problem+json"));
    }

    private HttpResponse<String> getResource(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
