package com.eventledger.account;

import com.eventledger.account.domain.TransactionType;
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

}
