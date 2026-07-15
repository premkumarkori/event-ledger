package com.eventledger.gateway;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import com.eventledger.gateway.service.EventReader;
import com.eventledger.gateway.service.EventWriter;
import com.eventledger.gateway.support.MutableTestClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EventApplicationServiceIT {

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

        @Bean
        OutboundBoundaryHook outboundBoundaryHook(EventReader reader) {
            return new OutboundBoundaryHook(reader);
        }

        @Bean
        RestClientCustomizer outboundBoundaryCustomizer(OutboundBoundaryHook hook) {
            return builder -> builder.requestInterceptor(hook);
        }
    }

    static class OutboundBoundaryHook implements ClientHttpRequestInterceptor {

        private final EventReader reader;
        private volatile String observedEventId;
        private volatile ApplicationStatus statusAtCall;
        private volatile boolean transactionActiveAtCall;

        OutboundBoundaryHook(EventReader reader) {
            this.reader = reader;
        }

        void reset() {
            observedEventId = null;
            statusAtCall = null;
            transactionActiveAtCall = true;
        }

        void observeEvent(String eventId) {
            observedEventId = eventId;
        }

        ApplicationStatus statusAtCall() {
            return statusAtCall;
        }

        boolean transactionActiveAtCall() {
            return transactionActiveAtCall;
        }

        @Override
        public ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution) throws IOException {
            transactionActiveAtCall = TransactionSynchronizationManager.isActualTransactionActive();
            if (observedEventId != null) {
                statusAtCall = reader.find(observedEventId).map(StoredEvent::applicationStatus).orElse(null);
            }
            return execution.execute(request, body);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventRepository events;

    @Autowired
    private EventWriter eventWriter;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private OutboundBoundaryHook outboundHook;

    @Autowired
    private JsonMapper jsonMapper;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        account.resetAll();
        events.deleteAll();
        clock.setInstant(RECEIVED_AT);
        outboundHook.reset();
    }

    private String eventBody(String eventId, String amount, String currency) {
        return """
                {"eventId":"%s","accountId":"acct-1","type":"CREDIT","amount":%s,
                 "currency":"%s","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"batch"}}"""
                .formatted(eventId, amount, currency);
    }

    private String accountBody(String eventId, String accountId, String type,
                               String amount, String currency, String eventTimestamp, String appliedAt) {
        String appliedAtField = appliedAt == null
                ? ""
                : ",\"appliedAt\":\"%s\"".formatted(appliedAt);
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,
                 "currency":"%s","eventTimestamp":"%s"%s}"""
                .formatted(eventId, accountId, type, amount, currency, eventTimestamp, appliedAtField);
    }

    private void stubAccount(int status, String body) {
        account.stubFor(post(urlEqualTo(ACCOUNT_PATH)).willReturn(
                aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    private StoredEventEntity row(String eventId) {
        return events.findById(eventId).orElseThrow();
    }

    private int accountRequestCount() {
        return account.countRequestsMatching(postRequestedFor(urlEqualTo(ACCOUNT_PATH)).build())
                .getCount();
    }

    @Test
    void submitEvent_shouldReturn201AndApplied_whenAccountCreatesTransaction() throws Exception {
        stubAccount(201, accountBody("evt-1", "acct-1", "CREDIT", "150.00", "USD",
                EVENT_TIME.toString(), ACCOUNT_APPLIED_AT.toString()));

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/events/evt-1");
        JsonNode body = json(response.body());
        assertThat(body.path("applicationStatus").asString()).isEqualTo("APPLIED");
        assertThat(body.has("appliedAt")).isTrue();

        StoredEventEntity stored = row("evt-1");
        assertThat(events.count()).isEqualTo(1);
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(stored.getAppliedAt()).isNotNull();
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(stored.getLastFailureCode()).isNull();
        assertThat(stored.getLastFailureMessage()).isNull();

        List<LoggedRequest> requests = account.findAll(postRequestedFor(urlEqualTo(ACCOUNT_PATH)));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getHeader("Content-Type")).contains("application/json");
        JsonNode sent = json(requests.get(0).getBodyAsString());
        assertThat(sent.path("eventId").asString()).isEqualTo("evt-1");
        assertThat(sent.path("type").asString()).isEqualTo("CREDIT");
        assertThat(new BigDecimal(sent.path("amount").asString())).isEqualByComparingTo("150.00");
        assertThat(sent.path("currency").asString()).isEqualTo("USD");
        assertThat(sent.path("eventTimestamp").asString()).isEqualTo(EVENT_TIME.toString());
        assertThat(sent.has("accountId")).isFalse();
        assertThat(sent.has("metadata")).isFalse();
    }

    @Test
    void submitEvent_shouldReturn201_whenNewGatewayRowFindsAccountReplay() throws Exception {
        stubAccount(200, accountBody("evt-2", "acct-1", "CREDIT", "150.00", "USD",
                EVENT_TIME.toString(), ACCOUNT_APPLIED_AT.toString()));

        HttpResponse<String> response = submit(eventBody("evt-2", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response.body()).path("applicationStatus").asString()).isEqualTo("APPLIED");
        assertThat(row("evt-2").getApplicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(accountRequestCount()).isEqualTo(1);
    }

    @Test
    void submitEvent_shouldReturn409AndStoreTerminalFailure_whenAccountReturnsCurrencyConflict() throws Exception {
        stubAccount(409, "{\"type\":\"urn:event-ledger:problem:currency-conflict\",\"status\":409}");

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:currency-conflict");
        StoredEventEntity stored = row("evt-1");
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.TERMINAL_CONFLICT);
        assertThat(stored.getLastFailureMessage()).isEqualTo("currency-conflict");
        assertThat(stored.getAppliedAt()).isNull();
        assertThat(stored.getAmount()).isEqualByComparingTo("150.00");
        assertThat(accountRequestCount()).isEqualTo(1);
    }

    @Test
    void submitEvent_shouldReturn409AndStoreTerminalFailure_whenAccountReturnsIdempotencyConflict() throws Exception {
        stubAccount(409, "{\"type\":\"urn:event-ledger:problem:idempotency-conflict\",\"status\":409}");

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:idempotency-conflict");
        StoredEventEntity stored = row("evt-1");
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.TERMINAL_CONFLICT);
        assertThat(stored.getLastFailureMessage()).isEqualTo("idempotency-conflict");
    }

    @Test
    void submitEvent_shouldReturn502AndStoreContractFailure_whenAccountReturns400() throws Exception {
        stubAccount(400, "{\"type\":\"urn:event-ledger:problem:validation\",\"detail\":\"secret-internal-detail\"}");

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(response.body()).doesNotContain("secret-internal-detail");
        StoredEventEntity stored = row("evt-1");
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.CONTRACT_ERROR);
        assertThat(stored.getLastFailureMessage()).doesNotContain("secret-internal-detail");
    }

    @Test
    void submitEvent_shouldReturn502_whenSuccessBodyIsMissing() throws Exception {
        account.stubFor(post(urlEqualTo(ACCOUNT_PATH)).willReturn(aResponse().withStatus(201)));

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(row("evt-1").getLastFailureCode()).isEqualTo(LastFailureCode.CONTRACT_ERROR);
    }

    @Test
    void submitEvent_shouldReturn502_whenSuccessBodyIsMalformed() throws Exception {
        stubAccount(201, "{not-json");

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(row("evt-1").getLastFailureCode()).isEqualTo(LastFailureCode.CONTRACT_ERROR);
    }

    @Test
    void submitEvent_shouldReturn502_whenConflictBodyIsUnrecognized() throws Exception {
        stubAccount(409, "{\"type\":\"urn:unknown-conflict\",\"detail\":\"private-detail\"}");

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).doesNotContain("private-detail");
        assertThat(row("evt-1").getLastFailureCode()).isEqualTo(LastFailureCode.CONTRACT_ERROR);
        assertThat(accountRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "mismatch={0}")
    @ValueSource(strings = {"eventId", "accountId", "type", "amount", "currency", "eventTimestamp", "appliedAt"})
    void submitEvent_shouldReturn502_whenSuccessBodyDoesNotMatchRequest(String mismatch) throws Exception {
        stubAccount(201, mismatchedBody(mismatch));

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(row("evt-1").getLastFailureCode()).isEqualTo(LastFailureCode.CONTRACT_ERROR);
    }

    private String mismatchedBody(String mismatch) {
        String eventId = mismatch.equals("eventId") ? "evt-OTHER" : "evt-1";
        String accountId = mismatch.equals("accountId") ? "acct-OTHER" : "acct-1";
        String type = mismatch.equals("type") ? "DEBIT" : "CREDIT";
        String amount = mismatch.equals("amount") ? "999.00" : "150.00";
        String currency = mismatch.equals("currency") ? "EUR" : "USD";
        String eventTimestamp = mismatch.equals("eventTimestamp")
                ? "2020-01-01T00:00:00Z" : EVENT_TIME.toString();
        String appliedAt = mismatch.equals("appliedAt") ? null : ACCOUNT_APPLIED_AT.toString();
        return accountBody(eventId, accountId, type, amount, currency, eventTimestamp, appliedAt);
    }

    @Test
    void submitEvent_shouldReturn503AndStoreRetryableFailure_whenAccountReturns5xx() throws Exception {
        stubAccount(503, "{\"type\":\"about:blank\"}");

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.path("detail").asString())
                .isEqualTo("Application of event evt-1 could not be confirmed. Retrying the same eventId is safe.");
        assertThat(body.path("applicationStatus").asString()).isEqualTo("APPLY_FAILED");
        StoredEventEntity stored = row("evt-1");
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertThat(stored.getAppliedAt()).isNull();
        assertThat(stored.getLastAttemptAt()).isEqualTo(RECEIVED_AT);
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(accountRequestCount()).isEqualTo(1);
    }

    @Test
    void submitEvent_shouldReturn503AndStoreRetryableFailure_whenConnectionIsLost() throws Exception {
        account.stubFor(post(urlEqualTo(ACCOUNT_PATH)).willReturn(
                aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(events.count()).isEqualTo(1);
        assertThat(row("evt-1").getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
    }

    @Test
    void submitEvent_shouldKeepGatewayRow_whenDownstreamFails() throws Exception {
        stubAccount(400, "{\"type\":\"urn:event-ledger:problem:validation\"}");

        submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(events.count()).isEqualTo(1);
        StoredEventEntity stored = row("evt-1");
        assertThat(stored.getAmount()).isEqualByComparingTo("150.00");
        assertThat(stored.getCurrency()).isEqualTo("USD");
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
    }

    @Test
    void submitEvent_shouldNotWriteOrCallAccount_whenRequestIsInvalid() throws Exception {
        HttpResponse<String> beanValidation = submit(eventBody("", "150.00", "USD"));
        assertThat(beanValidation.statusCode()).isEqualTo(400);

        HttpResponse<String> normalization = submit(eventBody("evt-1", "150.00", "ZZZ"));
        assertThat(normalization.statusCode()).isEqualTo(400);

        assertThat(events.count()).isZero();
        assertThat(accountRequestCount()).isZero();
    }

    @Test
    void submitEvent_shouldPersistReceivedBeforeCallingAccount() throws Exception {
        stubAccount(201, accountBody("evt-1", "acct-1", "CREDIT", "150.00", "USD",
                EVENT_TIME.toString(), ACCOUNT_APPLIED_AT.toString()));
        outboundHook.observeEvent("evt-1");

        submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(outboundHook.statusAtCall()).isEqualTo(ApplicationStatus.RECEIVED);
    }

    @Test
    void submitEvent_shouldCallAccountWithoutGatewayTransaction() throws Exception {
        stubAccount(201, accountBody("evt-1", "acct-1", "CREDIT", "150.00", "USD",
                EVENT_TIME.toString(), ACCOUNT_APPLIED_AT.toString()));

        submit(eventBody("evt-1", "150.00", "USD"));

        assertThat(outboundHook.transactionActiveAtCall()).isFalse();
    }

    @Test
    void getEvent_shouldMakeZeroAccountRequests_whenAccountClientExists() throws Exception {
        seedReceived("evt-1");

        HttpResponse<String> response = get("/events/evt-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(accountRequestCount()).isZero();
    }

    @Test
    void listEvents_shouldMakeZeroAccountRequests_whenAccountClientExists() throws Exception {
        seedReceived("evt-1");

        HttpResponse<String> response = get("/events?account=acct-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(accountRequestCount()).isZero();
    }
    private void seedReceived(String eventId) {
        eventWriter.insert(new NormalizedEvent(
                eventId, "acct-1", EventType.CREDIT,
                new BigDecimal("150.00"), "USD", EVENT_TIME, "{}"));
    }
}
