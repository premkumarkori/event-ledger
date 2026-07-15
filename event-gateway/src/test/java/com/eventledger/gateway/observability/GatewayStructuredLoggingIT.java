package com.eventledger.gateway.observability;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class GatewayStructuredLoggingIT {

    private static final String OUTCOME_MESSAGE = "Event ingestion completed";
    private static final String ACCOUNT_SERVICE_CIRCUIT = "accountService";
    private static final String ACCOUNT_ID = "acct-log-1";
    private static final String EVENT_ID = "evt-gateway-structured-log";
    private static final String AMOUNT = "150.00";
    private static final String METADATA_MARKER = "meta-marker-not-for-logs";
    private static final String SECRET_MARKER = "secret-marker-not-for-logs";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");
    private static final String INCOMING_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String EXPECTED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String APPLY_PATH = "/accounts/" + ACCOUNT_ID + "/transactions";

    private static final WireMockServer account = new WireMockServer(options().dynamicPort());

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final JsonMapper json = JsonMapper.builder().build();

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
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetScenario() {
        account.resetAll();
        events.deleteAll();
        accountCircuit().reset();
    }

    @Test
    void submitEvent_shouldEmitStructuredOutcomeLog_whenTracedRequestSucceeds(CapturedOutput output)
            throws Exception {
        String accountBody = successfulAccountBody(AMOUNT);
        stubSuccessfulApply(accountBody);
        String requestBody = requestBody(AMOUNT);

        HttpResponse<String> response = submit(requestBody);

        assertThat(response.statusCode()).isEqualTo(201);

        List<String> lines = outcomeLines(output.getAll());
        assertThat(lines).hasSize(1);
        String line = lines.get(0);
        JsonNode node = json.readTree(line);

        assertThat(node.path("@timestamp").asText()).isNotBlank();
        assertThat(node.path("log").path("level").asText()).isEqualTo("INFO");
        assertThat(node.path("service").path("name").asText()).isEqualTo("event-gateway");
        assertThat(node.path("message").asText()).isEqualTo(OUTCOME_MESSAGE);
        assertThat(node.path("outcome").asText()).isEqualTo("created");
        assertThat(node.path("applicationStatus").asText()).isEqualTo("APPLIED");
        assertThat(node.path("traceId").asText()).isEqualTo(EXPECTED_TRACE_ID);

        assertThat(line)
                .doesNotContain(EVENT_ID)
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain(AMOUNT)
                .doesNotContain(METADATA_MARKER)
                .doesNotContain(SECRET_MARKER)
                .doesNotContain(requestBody)
                .doesNotContain(accountBody)
                .doesNotContain(response.body());
    }

    @Test
    void submitEvent_shouldLogCreatedAndReplayOnce_whenSameRequestIsRepeated(CapturedOutput output)
            throws Exception {
        stubSuccessfulApply(successfulAccountBody(AMOUNT));
        String body = requestBody(AMOUNT);

        HttpResponse<String> created = submit(body);
        HttpResponse<String> replayed = submit(body);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(outcomes(output.getAll())).containsExactly("created", "replayed");
        assertThat(accountRequestCount()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void submitEvent_shouldLogConflictWithStoredStatus_whenPayloadChanges(CapturedOutput output)
            throws Exception {
        stubSuccessfulApply(successfulAccountBody(AMOUNT));
        HttpResponse<String> created = submit(requestBody(AMOUNT));
        String conflictingBody = requestBody("151.00");

        HttpResponse<String> conflict = submit(conflictingBody);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(conflict.statusCode()).isEqualTo(409);
        List<String> lines = outcomeLines(output.getAll());
        assertThat(outcomes(lines)).containsExactly("created", "conflict");
        JsonNode conflictLog = json.readTree(lines.get(1));
        assertThat(conflictLog.path("applicationStatus").asText()).isEqualTo("APPLIED");
        assertThat(conflictLog.has("failureCode")).isFalse();
        assertThat(accountRequestCount()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        assertThat(lines.get(1))
                .doesNotContain(EVENT_ID)
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain("151.00")
                .doesNotContain(METADATA_MARKER)
                .doesNotContain(SECRET_MARKER)
                .doesNotContain(conflictingBody)
                .doesNotContain(conflict.body());
    }

    @Test
    void submitEvent_shouldLogRetryableFailureWithoutClaimingStoredStatus_whenAccountIsUnavailable(
            CapturedOutput output) throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"about:blank\",\"status\":503}")));
        String requestBody = requestBody(AMOUNT);

        HttpResponse<String> unavailable = submit(requestBody);

        assertThat(unavailable.statusCode()).isEqualTo(503);
        List<String> lines = outcomeLines(output.getAll());
        assertThat(lines).hasSize(1);
        JsonNode failureLog = json.readTree(lines.get(0));
        assertThat(failureLog.path("outcome").asText()).isEqualTo("apply_failed");
        assertThat(failureLog.path("failureCode").asText()).isEqualTo("RETRYABLE_UNCONFIRMED");
        assertThat(failureLog.has("applicationStatus")).isFalse();
        StoredEventEntity stored = events.findById(EVENT_ID).orElseThrow();
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertThat(accountRequestCount()).isEqualTo(1);
        assertThat(lines.get(0))
                .doesNotContain(EVENT_ID)
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain(AMOUNT)
                .doesNotContain(METADATA_MARKER)
                .doesNotContain(SECRET_MARKER)
                .doesNotContain(requestBody)
                .doesNotContain(unavailable.body());
    }

    private void stubSuccessfulApply(String accountBody) {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"" + EVENT_ID + "\""))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(accountBody)));
    }

    private String successfulAccountBody(String amount) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":%s,
                 "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                .formatted(EVENT_ID, ACCOUNT_ID, amount, EVENT_TIME, ACCOUNT_APPLIED_AT);
    }

    private String requestBody(String amount) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":%s,
                 "currency":"USD","eventTimestamp":"%s",
                 "metadata":{"source":"batch","note":"%s","token":"%s"}}"""
                .formatted(EVENT_ID, ACCOUNT_ID, amount, EVENT_TIME, METADATA_MARKER, SECRET_MARKER);
    }

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .header("traceparent", INCOMING_TRACEPARENT)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int accountRequestCount() {
        return account.countRequestsMatching(postRequestedFor(urlEqualTo(APPLY_PATH)).build())
                .getCount();
    }

    private List<String> outcomes(String captured) throws Exception {
        return outcomes(outcomeLines(captured));
    }

    private List<String> outcomes(List<String> lines) throws Exception {
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            values.add(json.readTree(line).path("outcome").asText());
        }
        return values;
    }

    private List<String> outcomeLines(String captured) {
        return captured.lines()
                .filter(line -> line.contains(OUTCOME_MESSAGE))
                .filter(line -> line.trim().startsWith("{"))
                .toList();
    }

    private CircuitBreaker accountCircuit() {
        return circuitBreakerRegistry.circuitBreaker(ACCOUNT_SERVICE_CIRCUIT);
    }
}
