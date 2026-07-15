package com.eventledger.gateway;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.service.EventReader;
import com.eventledger.gateway.service.EventWriter;
import com.eventledger.gateway.support.MutableTestClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EventIdempotencyTest {

    private static final String ACCOUNT_PATH = "/accounts/acct-1/transactions";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");

    private static final WireMockServer account = new WireMockServer(options().dynamicPort());

    @DynamicPropertySource
    static void accountBaseUrl(DynamicPropertyRegistry registry) {
        account.start();
        registry.add("clients.account.base-url", () -> "http://localhost:" + account.port());
    }

    @AfterAll
    static void stopAccount() {
        account.stop();
    }

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(RECEIVED_AT);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventRepository events;

    @Autowired
    private EventReader eventReader;

    @Autowired
    private EventWriter eventWriter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        account.resetAll();
        events.deleteAll();
    }

    @Test
    void submitEvent_shouldApplyExactlyOnce_whenNewConfirmedEvent() throws Exception {
        stubAccountApplied();

        HttpResponse<String> response = submit(baselineBody());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/events/evt-1");
        assertThat(events.count()).isEqualTo(1);
        assertThat(accountRequestCount()).isEqualTo(1);
        assertAppliedOnce(snapshot("evt-1"));
    }

    @Test
    void submitEvent_shouldReplayWithoutAccountCall_whenIdenticalEventAlreadyApplied() throws Exception {
        stubAccountApplied();
        assertThat(submit(baselineBody()).statusCode()).isEqualTo(201);
        StoredEvent afterApply = snapshot("evt-1");
        account.resetAll();

        HttpResponse<String> replay = submit(baselineBody());

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("X-Idempotent-Replay")).contains("true");
        assertThat(accountRequestCount()).isZero();
        assertThat(events.count()).isEqualTo(1);
        assertThat(snapshot("evt-1")).isEqualTo(afterApply);
    }

    @Test
    void submitEvent_shouldRecoverToApplied_whenDurableReceivedRowExists() throws Exception {
        eventWriter.insert(baselineEvent());
        stubAccountApplied();

        HttpResponse<String> response = submit(baselineBody());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Idempotent-Replay")).contains("true");
        assertThat(accountRequestCount()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        assertAppliedOnce(snapshot("evt-1"));
    }

    @Test
    void submitEvent_shouldRecoverToApplied_whenPreviousAttemptWasRetryableUnconfirmed() throws Exception {
        stubAccount(503, "{\"type\":\"about:blank\"}");
        assertThat(submit(baselineBody()).statusCode()).isEqualTo(503);
        StoredEvent afterFailure = snapshot("evt-1");
        assertThat(afterFailure.applicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(afterFailure.lastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertThat(afterFailure.attemptCount()).isEqualTo(1);

        account.resetAll();
        stubAccountApplied();
        HttpResponse<String> recovery = submit(baselineBody());

        assertThat(recovery.statusCode()).isEqualTo(200);
        assertThat(recovery.headers().firstValue("X-Idempotent-Replay")).contains("true");
        assertThat(accountRequestCount()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        StoredEvent recovered = snapshot("evt-1");
        assertThat(recovered.applicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(recovered.version()).isEqualTo(2);
        assertThat(recovered.lastAttemptAt()).isEqualTo(RECEIVED_AT);
        assertThat(recovered.appliedAt()).isEqualTo(RECEIVED_AT);
        assertThat(recovered.lastFailureCode()).isNull();
        assertThat(recovered.lastFailureMessage()).isNull();
    }

    @Test
    void submitEvent_shouldReturnRetainedCurrencyConflict_whenPreviousAttemptWasCurrencyConflict() throws Exception {
        stubAccount(409, "{\"type\":\"urn:event-ledger:problem:currency-conflict\",\"status\":409}");
        assertThat(submit(baselineBody()).statusCode()).isEqualTo(409);
        StoredEvent afterConflict = snapshot("evt-1");
        account.resetAll();

        HttpResponse<String> retained = submit(baselineBody());

        assertThat(retained.statusCode()).isEqualTo(409);
        assertThat(problemType(retained)).isEqualTo("urn:event-ledger:problem:currency-conflict");
        assertThat(accountRequestCount()).isZero();
        assertThat(snapshot("evt-1")).isEqualTo(afterConflict);
    }

    @Test
    void submitEvent_shouldReturnRetainedIdempotencyConflict_whenPreviousAttemptWasIdempotencyConflict() throws Exception {
        stubAccount(409, "{\"type\":\"urn:event-ledger:problem:idempotency-conflict\",\"status\":409}");
        assertThat(submit(baselineBody()).statusCode()).isEqualTo(409);
        StoredEvent afterConflict = snapshot("evt-1");
        account.resetAll();

        HttpResponse<String> retained = submit(baselineBody());

        assertThat(retained.statusCode()).isEqualTo(409);
        assertThat(problemType(retained)).isEqualTo("urn:event-ledger:problem:idempotency-conflict");
        assertThat(accountRequestCount()).isZero();
        assertThat(snapshot("evt-1")).isEqualTo(afterConflict);
    }

    @Test
    void submitEvent_shouldReturnSanitized502_whenPreviousAttemptWasContractError() throws Exception {
        stubAccount(400, "{\"type\":\"urn:event-ledger:problem:validation\",\"detail\":\"secret-internal-detail\"}");
        assertThat(submit(baselineBody()).statusCode()).isEqualTo(502);
        StoredEvent afterContractError = snapshot("evt-1");
        assertThat(afterContractError.lastFailureCode()).isEqualTo(LastFailureCode.CONTRACT_ERROR);
        account.resetAll();

        HttpResponse<String> retained = submit(baselineBody());

        assertThat(retained.statusCode()).isEqualTo(502);
        assertThat(problemType(retained)).isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(retained.body()).doesNotContain("secret-internal-detail");
        assertThat(accountRequestCount()).isZero();
        assertThat(snapshot("evt-1")).isEqualTo(afterContractError);
    }

    @Test
    void submitEvent_shouldReturnSanitized502_whenStoredFailureCodeIsNull() throws Exception {
        eventWriter.insert(baselineEvent());
        forceFailureState("evt-1", null, null);
        StoredEvent seeded = snapshot("evt-1");

        HttpResponse<String> response = submit(baselineBody());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(problemType(response)).isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(accountRequestCount()).isZero();
        assertThat(snapshot("evt-1")).isEqualTo(seeded);
    }

    @Test
    void submitEvent_shouldReturnSanitized502_whenTerminalConflictTokenIsUnknown() throws Exception {
        eventWriter.insert(baselineEvent());
        forceFailureState("evt-1", LastFailureCode.TERMINAL_CONFLICT, "mystery-token");
        StoredEvent seeded = snapshot("evt-1");

        HttpResponse<String> response = submit(baselineBody());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(problemType(response)).isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(accountRequestCount()).isZero();
        assertThat(snapshot("evt-1")).isEqualTo(seeded);
    }

    enum ChangedField {
        ACCOUNT_ID(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-2", "CREDIT", "150.00", "USD", EVENT_TIME, "{\"source\":\"batch\"}")),
        TYPE(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-1", "DEBIT", "150.00", "USD", EVENT_TIME, "{\"source\":\"batch\"}")),
        AMOUNT(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-1", "CREDIT", "151.00", "USD", EVENT_TIME, "{\"source\":\"batch\"}")),
        CURRENCY(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-1", "CREDIT", "150.00", "EUR", EVENT_TIME, "{\"source\":\"batch\"}")),
        EVENT_TIMESTAMP(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-1", "CREDIT", "150.00", "USD", Instant.parse("2026-05-15T14:02:12Z"), "{\"source\":\"batch\"}")),
        METADATA_VALUE(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-1", "CREDIT", "150.00", "USD", EVENT_TIME, "{\"source\":\"other\"}")),
        METADATA_NUMBER_REPRESENTATION(baseline("{\"count\":1}"), body("evt-1", "acct-1", "CREDIT", "150.00", "USD", EVENT_TIME, "{\"count\":1.0}")),
        METADATA_ARRAY_ORDER(baseline("{\"tags\":[1,2]}"), body("evt-1", "acct-1", "CREDIT", "150.00", "USD", EVENT_TIME, "{\"tags\":[2,1]}"));

        private final String originalBody;
        private final String changedBody;

        ChangedField(String originalBody, String changedBody) {
            this.originalBody = originalBody;
            this.changedBody = changedBody;
        }
    }

    @ParameterizedTest(name = "changed={0}")
    @EnumSource(ChangedField.class)
    void submitEvent_shouldReturn409AndPreserveOriginal_whenSameEventIdHasChangedPayload(ChangedField change)
            throws Exception {
        stubAccountApplied();
        assertThat(submit(change.originalBody).statusCode()).isEqualTo(201);
        StoredEvent original = snapshot("evt-1");
        account.resetAll();

        HttpResponse<String> conflict = submit(change.changedBody);

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(problemType(conflict)).isEqualTo("urn:event-ledger:problem:idempotency-conflict");
        assertThat(accountRequestCount()).isZero();
        assertThat(events.count()).isEqualTo(1);
        assertThat(snapshot("evt-1")).isEqualTo(original);
    }

    enum EquivalentField {
        AMOUNT_SCALE(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-1", "CREDIT", "150.0", "USD", EVENT_TIME, "{\"source\":\"batch\"}")),
        CURRENCY_CASE(baseline("{\"source\":\"batch\"}"), body("evt-1", "acct-1", "CREDIT", "150.00", "usd", EVENT_TIME, "{\"source\":\"batch\"}")),
        METADATA_KEY_ORDER(baseline("{\"a\":1,\"b\":2}"), body("evt-1", "acct-1", "CREDIT", "150.00", "USD", EVENT_TIME, "{\"b\":2,\"a\":1}")),
        METADATA_NULL_VS_EMPTY(baseline("{}"), bodyWithoutMetadata("evt-1", "acct-1", "CREDIT", "150.00", "USD", EVENT_TIME));

        private final String originalBody;
        private final String equivalentBody;

        EquivalentField(String originalBody, String equivalentBody) {
            this.originalBody = originalBody;
            this.equivalentBody = equivalentBody;
        }
    }

    @ParameterizedTest(name = "equivalent={0}")
    @EnumSource(EquivalentField.class)
    void submitEvent_shouldReplayWithoutAccountCall_whenSameEventIdHasEquivalentPayload(EquivalentField change)
            throws Exception {
        stubAccountApplied();
        assertThat(submit(change.originalBody).statusCode()).isEqualTo(201);
        StoredEvent original = snapshot("evt-1");
        account.resetAll();

        HttpResponse<String> replay = submit(change.equivalentBody);

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("X-Idempotent-Replay")).contains("true");
        assertThat(accountRequestCount()).isZero();
        assertThat(events.count()).isEqualTo(1);
        assertThat(snapshot("evt-1")).isEqualTo(original);
    }

    @Test
    void submitEvent_shouldInsertExactlyOneRow_whenTwoIdenticalFirstSubmissionsRace() throws Exception {
        stubAccountApplied();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readyGate = new CountDownLatch(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> submitAfter(readyGate, startGate, baselineBody()));
            Future<HttpResponse<String>> second = pool.submit(() -> submitAfter(readyGate, startGate, baselineBody()));
            awaitOrFail(readyGate, "Concurrent submissions did not become ready");
            startGate.countDown();

            HttpResponse<String> firstResponse = first.get(10, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstResponse.statusCode(), secondResponse.statusCode()))
                    .containsExactlyInAnyOrder(200, 201);
            HttpResponse<String> replay =
                    firstResponse.statusCode() == 200 ? firstResponse : secondResponse;
            assertThat(replay.headers().firstValue("X-Idempotent-Replay")).contains("true");
            assertThat(events.count()).isEqualTo(1);
            assertAppliedOnce(snapshot("evt-1"));
            assertThat(accountRequestCount())
                    .as("both requests may reach Account before either marks the event APPLIED")
                    .isBetween(1, 2);
        } finally {
            pool.shutdownNow();
        }
    }

    private NormalizedEvent baselineEvent() {
        return new NormalizedEvent("evt-1", "acct-1", EventType.CREDIT,
                new BigDecimal("150.00"), "USD", EVENT_TIME, "{}");
    }

    private String baselineBody() {
        return body("evt-1", "acct-1", "CREDIT", "150.00", "USD", EVENT_TIME, "{}");
    }

    private static String baseline(String metadataJson) {
        return body("evt-1", "acct-1", "CREDIT", "150.00", "USD", EVENT_TIME, metadataJson);
    }

    private static String body(String eventId, String accountId, String type,
                               String amount, String currency, Instant eventTime, String metadataJson) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,
                 "currency":"%s","eventTimestamp":"%s","metadata":%s}"""
                .formatted(eventId, accountId, type, amount, currency, eventTime, metadataJson);
    }

    private static String bodyWithoutMetadata(String eventId, String accountId, String type,
                                              String amount, String currency, Instant eventTime) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,
                 "currency":"%s","eventTimestamp":"%s"}"""
                .formatted(eventId, accountId, type, amount, currency, eventTime);
    }

    private void stubAccountApplied() {
        stubAccount(201, """
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                .formatted(EVENT_TIME, ACCOUNT_APPLIED_AT));
    }

    private void stubAccount(int status, String body) {
        account.stubFor(post(urlEqualTo(ACCOUNT_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private void forceFailureState(String eventId, LastFailureCode code, String message) {
        jdbc.update("""
                UPDATE gateway_events
                   SET application_status = 'APPLY_FAILED',
                       last_failure_code = ?,
                       last_failure_message = ?,
                       last_attempt_at = ?,
                       attempt_count = 1
                 WHERE event_id = ?""",
                code == null ? null : code.name(), message, java.sql.Timestamp.from(RECEIVED_AT), eventId);
    }

    private StoredEvent snapshot(String eventId) {
        return eventReader.find(eventId).orElseThrow();
    }

    private void assertAppliedOnce(StoredEvent event) {
        assertThat(event.applicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(event.attemptCount()).isEqualTo(1);
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.lastAttemptAt()).isEqualTo(RECEIVED_AT);
        assertThat(event.appliedAt()).isEqualTo(RECEIVED_AT);
        assertThat(event.lastFailureCode()).isNull();
        assertThat(event.lastFailureMessage()).isNull();
    }

    private int accountRequestCount() {
        return account.countRequestsMatching(postRequestedFor(urlEqualTo(ACCOUNT_PATH)).build())
                .getCount();
    }

    private String problemType(HttpResponse<String> response) {
        return json(response.body()).path("type").asString();
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    private HttpResponse<String> submitAfter(CountDownLatch readyGate, CountDownLatch startGate, String body)
            throws Exception {
        readyGate.countDown();
        awaitOrFail(startGate, "Concurrent submission start gate was not released");
        return submit(body);
    }

    private void awaitOrFail(CountDownLatch gate, String failureMessage) throws InterruptedException {
        if (!gate.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(failureMessage);
        }
    }

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
