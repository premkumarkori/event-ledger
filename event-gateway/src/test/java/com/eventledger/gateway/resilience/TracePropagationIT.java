package com.eventledger.gateway.resilience;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.persistence.EventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Boot 4.1: export=false also disables W3C propagators; keep export on and disable OTLP in yml.
@AutoConfigureTracing
@ActiveProfiles("test")
class TracePropagationIT {

    private static final String ACCOUNT_SERVICE_CIRCUIT = "accountService";
    private static final String ACCOUNT_ID = "acct-1";
    private static final String APPLY_PATH = "/accounts/" + ACCOUNT_ID + "/transactions";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");
    private static final String INCOMING_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INCOMING_PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

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
    private CircuitBreakerRegistry circuitBreakerRegistry;

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
    void submitEvent_shouldPropagateGeneratedTrace_whenRequestHasNoTraceparent() throws Exception {
        stubSuccessfulApply("evt-trace-generated");

        HttpResponse<String> response = submit("evt-trace-generated");

        assertThat(response.statusCode()).isEqualTo(201);
        LoggedRequest accountRequest = singleAccountApplyRequest();
        W3cTraceparent outbound = parseTraceparent(accountRequest.getHeader("traceparent"));
        assertThat(outbound.version()).isEqualTo("00");
        assertThat(outbound.traceId()).doesNotMatch("^0{32}$");
        assertThat(outbound.parentSpanId()).doesNotMatch("^0{16}$");
        assertThat(outbound.flags()).matches("[0-9a-f]{2}");
        assertThat(events.findById("evt-trace-generated").orElseThrow().getApplicationStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    void submitEvent_shouldContinueIncomingTrace_whenRequestHasValidTraceparent() throws Exception {
        stubSuccessfulApply("evt-trace-continued");

        HttpResponse<String> response = submit("evt-trace-continued", INCOMING_TRACEPARENT);

        assertThat(response.statusCode()).isEqualTo(201);
        LoggedRequest accountRequest = singleAccountApplyRequest();
        W3cTraceparent outbound = parseTraceparent(accountRequest.getHeader("traceparent"));
        assertThat(outbound.traceId()).isEqualTo(INCOMING_TRACE_ID);
        assertThat(outbound.parentSpanId())
                .isNotEqualTo(INCOMING_PARENT_SPAN_ID)
                .doesNotMatch("^0{16}$");
        assertThat(outbound.version()).isEqualTo("00");
        assertThat(outbound.flags()).matches("[0-9a-f]{2}");
        assertThat(events.findById("evt-trace-continued").orElseThrow().getApplicationStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
        assertThat(applyRequestCount()).isEqualTo(1);
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

    private HttpResponse<String> submit(String eventId, String traceparent) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(eventBody(eventId)));
        if (traceparent != null) {
            builder.header("traceparent", traceparent);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> submit(String eventId) throws Exception {
        return submit(eventId, null);
    }

    private LoggedRequest singleAccountApplyRequest() {
        List<LoggedRequest> requests = account.findAll(postRequestedFor(urlEqualTo(APPLY_PATH)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private int applyRequestCount() {
        return account.countRequestsMatching(postRequestedFor(urlEqualTo(APPLY_PATH)).build())
                .getCount();
    }

    private CircuitBreaker accountCircuit() {
        return circuitBreakerRegistry.circuitBreaker(ACCOUNT_SERVICE_CIRCUIT);
    }

    private W3cTraceparent parseTraceparent(String header) {
        assertThat(header).isNotBlank();
        assertThat(header).matches(TRACEPARENT_PATTERN);
        String[] parts = header.split("-", 4);
        assertThat(parts).hasSize(4);
        return new W3cTraceparent(parts[0], parts[1], parts[2], parts[3]);
    }

    private record W3cTraceparent(String version, String traceId, String parentSpanId, String flags) {
    }
}
