package com.eventledger.gateway.resilience;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CircuitBreakerTest {

    private static final String ACCOUNT_SERVICE_CIRCUIT = "accountService";
    private static final String ACCOUNT_ID = "acct-1";
    private static final String APPLY_PATH = "/accounts/" + ACCOUNT_ID + "/transactions";
    private static final String BALANCE_PATH = "/accounts/" + ACCOUNT_ID + "/balance";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");
    private static final Duration RESPONSE_CEILING = Duration.ofSeconds(3);

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

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventRepository events;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private Resilience4JConfigurationProperties circuitBreakerProperties;

    @Autowired
    private ApplicationContext applicationContext;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void resetScenario() {
        account.resetAll();
        events.deleteAll();
        accountCircuit().reset();
    }

    @Test
    void accountCircuit_shouldUseOnlyHttpTimeout_whenCallingAccount() {
        assertThat(circuitBreakerProperties.getDisableTimeLimiterMap())
                .containsEntry(ACCOUNT_SERVICE_CIRCUIT, true);
        assertThat(circuitBreakerProperties.isDisableThreadPool()).isTrue();
        assertThat(applicationContext.getBeansOfType(Resilience4jBulkheadProvider.class))
                .isEmpty();
    }

    @Test
    void submitEvent_shouldOpenCircuitAndFailFast_whenFourInfrastructureFailuresOccur() throws Exception {
        stubApplyInfrastructureFailure();

        for (int index = 1; index <= 4; index++) {
            HttpResponse<String> failure = submit(eventBody("evt-open-" + index));
            assertThat(failure.statusCode()).isEqualTo(503);
        }

        CircuitBreaker circuit = accountCircuit();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
        assertThat(applyRequestCount()).isEqualTo(4);

        long started = System.nanoTime();
        HttpResponse<String> failFast = submit(eventBody("evt-open-fast"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(RESPONSE_CEILING);
        assertThat(failFast.statusCode()).isEqualTo(503);
        JsonNode body = json(failFast.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.path("detail").asString())
                .isEqualTo("Application of event evt-open-fast could not be confirmed. "
                        + "Retrying the same eventId is safe.");
        assertThat(body.path("detail").asString()).doesNotContain("not applied");
        assertThat(body.path("eventId").asString()).isEqualTo("evt-open-fast");
        assertThat(body.path("applicationStatus").asString()).isEqualTo("APPLY_FAILED");

        StoredEventEntity stored = events.findById("evt-open-fast").orElseThrow();
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertThat(applyRequestCount()).isEqualTo(4);
        assertThat(circuit.getMetrics().getNumberOfNotPermittedCalls()).isGreaterThanOrEqualTo(1);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void getBalance_shouldReturn503WithoutAccountCall_whenCircuitIsOpen() throws Exception {
        openCircuitWithInfrastructureFailures();
        int applyCallsWhenOpen = applyRequestCount();
        events.deleteAll();

        long started = System.nanoTime();
        HttpResponse<String> response = getBalance();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(RESPONSE_CEILING);
        assertThat(response.statusCode()).isEqualTo(503);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.path("detail").asString()).isEqualTo("Account balance is temporarily unavailable.");
        assertThat(body.has("eventId")).isFalse();
        assertThat(body.has("applicationStatus")).isFalse();
        assertThat(events.count()).isZero();
        assertThat(balanceRequestCount()).isZero();
        assertThat(applyRequestCount()).isEqualTo(applyCallsWhenOpen);
        assertThat(accountCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(accountCircuit().getMetrics().getNumberOfNotPermittedCalls()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void accountCircuit_shouldClose_whenHalfOpenProbesSucceed() throws Exception {
        openCircuitWithInfrastructureFailures();
        assertThat(accountCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(accountCircuit().getState())
                        .isEqualTo(CircuitBreaker.State.HALF_OPEN));

        account.resetAll();
        stubSuccessfulApply("evt-probe-1");
        stubSuccessfulApply("evt-probe-2");

        assertThat(submit(eventBody("evt-probe-1")).statusCode()).isEqualTo(201);
        assertThat(submit(eventBody("evt-probe-2")).statusCode()).isEqualTo(201);

        assertThat(accountCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(applyRequestCount()).isEqualTo(2);
        assertThat(events.findById("evt-probe-1").orElseThrow().getApplicationStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
        assertThat(events.findById("evt-probe-2").orElseThrow().getApplicationStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    void accountCircuit_shouldReopen_whenHalfOpenProbesReachFailureThreshold() throws Exception {
        openCircuitWithInfrastructureFailures();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(accountCircuit().getState())
                        .isEqualTo(CircuitBreaker.State.HALF_OPEN));

        account.resetAll();
        stubSuccessfulApply("evt-probe-success");
        stubApplyInfrastructureFailure("evt-probe-failure");

        assertThat(submit(eventBody("evt-probe-success")).statusCode()).isEqualTo(201);
        assertThat(submit(eventBody("evt-probe-failure")).statusCode()).isEqualTo(503);

        assertThat(accountCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(applyRequestCount()).isEqualTo(2);
        StoredEventEntity failedProbe = events.findById("evt-probe-failure").orElseThrow();
        assertThat(failedProbe.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(failedProbe.getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
    }

    @Test
    void submitEvent_shouldKeepFailedCallCountZero_whenAccountReturns400Repeatedly() throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH)).willReturn(
                aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"urn:event-ledger:problem:validation\",\"detail\":\"internal\"}")));

        for (int index = 1; index <= 4; index++) {
            HttpResponse<String> response = submit(eventBody("evt-400-" + index));
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(json(response.body()).path("type").asString())
                    .isEqualTo("urn:event-ledger:problem:downstream-contract");
        }

        CircuitBreaker circuit = accountCircuit();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(applyRequestCount()).isEqualTo(4);
    }

    @Test
    void submitEvent_shouldKeepFailedCallCountZero_whenAccountReturnsRecognized409Repeatedly()
            throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH)).willReturn(
                aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"urn:event-ledger:problem:currency-conflict\","
                                + "\"status\":409,\"detail\":\"internal-conflict\"}")));

        for (int index = 1; index <= 4; index++) {
            HttpResponse<String> response = submit(eventBody("evt-409-" + index));
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json(response.body()).path("type").asString())
                    .isEqualTo("urn:event-ledger:problem:currency-conflict");
            assertThat(response.body()).doesNotContain("internal-conflict");
        }

        CircuitBreaker circuit = accountCircuit();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(applyRequestCount()).isEqualTo(4);
    }

    @Test
    void getBalance_shouldKeepFailedCallCountZero_whenAccountReturns404Repeatedly() throws Exception {
        account.stubFor(get(urlEqualTo(BALANCE_PATH)).willReturn(
                aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"urn:event-ledger:problem:not-found\","
                                + "\"detail\":\"internal-missing\"}")));

        for (int index = 0; index < 4; index++) {
            HttpResponse<String> response = getBalance();
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(json(response.body()).path("type").asString())
                    .isEqualTo("urn:event-ledger:problem:not-found");
            assertThat(response.body()).doesNotContain("internal-missing");
        }

        CircuitBreaker circuit = accountCircuit();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(balanceRequestCount()).isEqualTo(4);
        assertThat(events.count()).isZero();
    }

    private void openCircuitWithInfrastructureFailures() throws Exception {
        stubApplyInfrastructureFailure();
        for (int index = 1; index <= 4; index++) {
            assertThat(submit(eventBody("evt-seed-" + index)).statusCode()).isEqualTo(503);
        }
        assertThat(accountCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(accountCircuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
    }

    private void stubApplyInfrastructureFailure() {
        account.stubFor(post(urlEqualTo(APPLY_PATH)).willReturn(
                aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"about:blank\",\"status\":503}")));
    }

    private void stubApplyInfrastructureFailure(String eventId) {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"" + eventId + "\""))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"about:blank\",\"status\":503}")));
    }

    private void stubSuccessfulApply(String eventId) {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"" + eventId + "\""))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successBody(eventId))));
    }

    private String successBody(String eventId) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                .formatted(eventId, ACCOUNT_ID, EVENT_TIME, ACCOUNT_APPLIED_AT);
    }

    private String eventBody(String eventId) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s","metadata":{"source":"batch"}}"""
                .formatted(eventId, ACCOUNT_ID, EVENT_TIME);
    }

    private CircuitBreaker accountCircuit() {
        return circuitBreakerRegistry.circuitBreaker(ACCOUNT_SERVICE_CIRCUIT);
    }

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getBalance() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + BALANCE_PATH))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int applyRequestCount() {
        return account.countRequestsMatching(postRequestedFor(urlEqualTo(APPLY_PATH)).build())
                .getCount();
    }

    private int balanceRequestCount() {
        return account.countRequestsMatching(getRequestedFor(urlEqualTo(BALANCE_PATH)).build())
                .getCount();
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }
}
