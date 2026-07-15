package com.eventledger.gateway.observability;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.metrics.EventOutcome;
import com.eventledger.gateway.metrics.EventOutcomeMetrics;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.eventledger.gateway.support.AccountCircuitTestSupport.resetAccountCircuit;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@ActiveProfiles("test")
class EventOutcomeMetricsTest {

    private static final String ACCOUNT_ID = "acct-metrics-1";
    private static final String APPLY_PATH = "/accounts/" + ACCOUNT_ID + "/transactions";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");
    private static final Set<String> ALLOWED_OUTCOMES = Set.of(
            "created", "replayed", "conflict", "apply_failed");

    private static final WireMockServer account = new WireMockServer(options().dynamicPort());
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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
    static class LateFailureConfiguration {

        @Bean
        LateFailureCoordinator lateFailureCoordinator() {
            return new LateFailureCoordinator();
        }

        @Bean
        RestClientCustomizer lateFailureCustomizer(LateFailureCoordinator coordinator) {
            return builder -> builder.requestInterceptor(coordinator);
        }
    }

    static final class LateFailureCoordinator implements ClientHttpRequestInterceptor {

        private final AtomicInteger outboundCalls = new AtomicInteger();
        private volatile CountDownLatch firstCallAtBoundary = new CountDownLatch(1);
        private volatile CountDownLatch bothCallsAtBoundary = new CountDownLatch(2);
        private volatile CountDownLatch secondCallReleased = new CountDownLatch(1);
        private volatile boolean armed;

        @Override
        public ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution) throws IOException {
            if (!armed || !request.getURI().getPath().endsWith("/transactions")) {
                return execution.execute(request, body);
            }
            int order = outboundCalls.incrementAndGet();
            bothCallsAtBoundary.countDown();
            if (order == 1) {
                firstCallAtBoundary.countDown();
                awaitLatch(bothCallsAtBoundary);
            } else {
                awaitLatch(secondCallReleased);
            }
            return execution.execute(request, body);
        }

        void arm() {
            armed = true;
            outboundCalls.set(0);
            firstCallAtBoundary = new CountDownLatch(1);
            bothCallsAtBoundary = new CountDownLatch(2);
            secondCallReleased = new CountDownLatch(1);
        }

        void disarm() {
            armed = false;
        }

        void awaitFirst() {
            awaitLatch(firstCallAtBoundary);
        }

        void releaseSecond() {
            secondCallReleased.countDown();
        }

        private static void awaitLatch(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for latch");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventRepository events;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private LateFailureCoordinator lateFailureCoordinator;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetScenario() {
        account.resetAll();
        events.deleteAll();
        lateFailureCoordinator.disarm();
        resetAccountCircuit(circuitBreakerRegistry);
    }

    @Test
    void submitEvent_shouldExposeExactlyFourOutcomeCounters_whenMetricsAreRegistered() {
        Set<String> outcomes = meterRegistry.find(EventOutcomeMetrics.METER_NAME).counters().stream()
                .map(counter -> counter.getId().getTag(EventOutcomeMetrics.OUTCOME_TAG))
                .collect(Collectors.toSet());
        assertThat(outcomes).containsExactlyInAnyOrderElementsOf(ALLOWED_OUTCOMES);
        for (Meter meter : meterRegistry.find(EventOutcomeMetrics.METER_NAME).meters()) {
            Set<String> tagKeys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey())
                    .collect(Collectors.toSet());
            assertThat(tagKeys).containsExactly(EventOutcomeMetrics.OUTCOME_TAG);
            assertThat(tagKeys).doesNotContain("eventId", "accountId", "traceId", "amount", "currency");
        }
    }

    @Test
    void submitEvent_shouldIncrementCreated_whenNewEventIsConfirmed() throws Exception {
        Map<EventOutcome, Double> baseline = snapshot();
        stubAccountSuccess("evt-metric-created", 201);

        HttpResponse<String> response = submit("evt-metric-created");

        assertThat(response.statusCode()).isEqualTo(201);
        assertDelta(baseline, EventOutcome.CREATED, 1);
    }

    @Test
    void submitEvent_shouldIncrementCreated_whenNewGatewayRowReceivesAccountReplay200() throws Exception {
        Map<EventOutcome, Double> baseline = snapshot();
        stubAccountSuccess("evt-metric-created-replay", 200);

        HttpResponse<String> response = submit("evt-metric-created-replay");

        assertThat(response.statusCode()).isEqualTo(201);
        assertDelta(baseline, EventOutcome.CREATED, 1);
    }

    @Test
    void submitEvent_shouldIncrementReplayed_whenExistingAppliedEventIsResubmitted() throws Exception {
        stubAccountSuccess("evt-metric-replayed", 201);
        assertThat(submit("evt-metric-replayed").statusCode()).isEqualTo(201);

        Map<EventOutcome, Double> baseline = snapshot();
        HttpResponse<String> response = submit("evt-metric-replayed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertDelta(baseline, EventOutcome.REPLAYED, 1);
    }

    @Test
    void submitEvent_shouldIncrementConflict_whenAccountReturnsRecognized409() throws Exception {
        Map<EventOutcome, Double> baseline = snapshot();
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"evt-metric-conflict\""))
                .willReturn(aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"type":"urn:event-ledger:problem:idempotency-conflict",
                                 "title":"Conflict","status":409}""")));

        HttpResponse<String> response = submit("evt-metric-conflict");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(events.findById("evt-metric-conflict").orElseThrow().getApplicationStatus())
                .isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertDelta(baseline, EventOutcome.CONFLICT, 1);
    }

    @Test
    void submitEvent_shouldIncrementApplyFailed_whenStoredStateRemainsApplyFailed() throws Exception {
        Map<EventOutcome, Double> baseline = snapshot();
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"evt-metric-failed\""))
                .willReturn(aResponse().withStatus(503)));

        HttpResponse<String> response = submit("evt-metric-failed");

        assertThat(response.statusCode()).isEqualTo(503);
        StoredEventEntity stored = events.findById("evt-metric-failed").orElseThrow();
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertDelta(baseline, EventOutcome.APPLY_FAILED, 1);
    }

    @Test
    void submitEvent_shouldNotIncrementAnyOutcome_whenValidationFailsBeforeStorage() throws Exception {
        Map<EventOutcome, Double> baseline = snapshot();
        String invalidBody = """
                {"eventId":"","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s"}"""
                .formatted(ACCOUNT_ID, EVENT_TIME);

        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(invalidBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(events.count()).isZero();
        assertNoDelta(baseline);
    }

    @Test
    void submitEvent_shouldNotIncrementApplyFailed_whenLateFailureLeavesAppliedState() throws Exception {
        String eventId = "evt-metric-late-failure";
        String scenario = "late-failure-metrics";
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(containing("\"eventId\":\"" + eventId + "\""))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successBody(eventId)))
                .willSetStateTo("applied"));
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .inScenario(scenario)
                .whenScenarioStateIs("applied")
                .withRequestBody(containing("\"eventId\":\"" + eventId + "\""))
                .willReturn(aResponse().withStatus(503)));

        lateFailureCoordinator.arm();
        Map<EventOutcome, Double> baseline = snapshot();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> submit(eventId));
            lateFailureCoordinator.awaitFirst();
            Future<HttpResponse<String>> second = pool.submit(() -> submit(eventId));

            assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(events.findById(eventId).orElseThrow().getApplicationStatus())
                    .isEqualTo(ApplicationStatus.APPLIED);

            lateFailureCoordinator.releaseSecond();
            assertThat(second.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        } finally {
            pool.shutdownNow();
            lateFailureCoordinator.disarm();
        }

        assertThat(events.findById(eventId).orElseThrow().getApplicationStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
        assertThat(delta(baseline, EventOutcome.APPLY_FAILED)).isZero();
        assertThat(delta(baseline, EventOutcome.CREATED)).isEqualTo(1.0);
    }

    private void stubAccountSuccess(String eventId, int status) {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"" + eventId + "\""))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successBody(eventId))));
    }

    private String successBody(String eventId) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                .formatted(eventId, ACCOUNT_ID, EVENT_TIME, ACCOUNT_APPLIED_AT);
    }

    private HttpResponse<String> submit(String eventId) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s","metadata":{"source":"metrics"}}"""
                .formatted(eventId, ACCOUNT_ID, EVENT_TIME);
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<EventOutcome, Double> snapshot() {
        Map<EventOutcome, Double> values = new EnumMap<>(EventOutcome.class);
        for (EventOutcome outcome : EventOutcome.values()) {
            values.put(outcome, counter(outcome).count());
        }
        return values;
    }

    private void assertDelta(Map<EventOutcome, Double> baseline, EventOutcome expected, double amount) {
        for (EventOutcome outcome : EventOutcome.values()) {
            double expectedDelta = outcome == expected ? amount : 0.0;
            assertThat(delta(baseline, outcome))
                    .as("delta for %s", outcome.tagValue())
                    .isEqualTo(expectedDelta);
        }
    }

    private void assertNoDelta(Map<EventOutcome, Double> baseline) {
        for (EventOutcome outcome : EventOutcome.values()) {
            assertThat(delta(baseline, outcome)).as(outcome.tagValue()).isZero();
        }
    }

    private double delta(Map<EventOutcome, Double> baseline, EventOutcome outcome) {
        return counter(outcome).count() - baseline.get(outcome);
    }

    private Counter counter(EventOutcome outcome) {
        Counter counter = meterRegistry.find(EventOutcomeMetrics.METER_NAME)
                .tag(EventOutcomeMetrics.OUTCOME_TAG, outcome.tagValue())
                .counter();
        assertThat(counter).as("counter %s", outcome.tagValue()).isNotNull();
        return counter;
    }
}
