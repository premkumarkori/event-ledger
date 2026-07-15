package com.eventledger.gateway.resilience;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.eventledger.gateway.support.AccountCircuitTestSupport.resetAccountCircuit;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountHttpConnectionRefusalIT {

    private static final Duration RESPONSE_CEILING = Duration.ofSeconds(3);

    private static final ServerSocket ACCOUNT_PORT_RESERVATION = reserveAccountPort();
    private static final int UNUSED_ACCOUNT_PORT = ACCOUNT_PORT_RESERVATION.getLocalPort();

    @DynamicPropertySource
    static void unusedAccountBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("clients.account.base-url",
                () -> "http://127.0.0.1:" + UNUSED_ACCOUNT_PORT);
    }

    @BeforeAll
    static void releaseAccountPortBeforeTest() throws IOException {
        ACCOUNT_PORT_RESERVATION.close();
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventRepository events;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        events.deleteAll();
        resetAccountCircuit(circuitBreakerRegistry);
    }

    @Test
    void submitEvent_shouldReturn503WithinBound_whenAccountConnectionIsRefused() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = submit(eventBody("evt-refused"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(RESPONSE_CEILING);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.path("detail").asString())
                .isEqualTo("Application of event evt-refused could not be confirmed. "
                        + "Retrying the same eventId is safe.");
        assertThat(body.path("detail").asString()).doesNotContain("not applied");
        assertThat(body.path("eventId").asString()).isEqualTo("evt-refused");
        assertThat(body.path("applicationStatus").asString()).isEqualTo("APPLY_FAILED");
        assertThat(body.path("detail").asString()).doesNotContain("127.0.0.1");
        assertThat(body.path("detail").asString()).doesNotContain(String.valueOf(UNUSED_ACCOUNT_PORT));

        assertThat(events.count()).isEqualTo(1);
        StoredEventEntity stored = events.findById("evt-refused").orElseThrow();
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertThat(stored.getAppliedAt()).isNull();
        assertThat(stored.getAttemptCount()).isEqualTo(1);
    }

    private static ServerSocket reserveAccountPort() {
        try {
            return new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "could not reserve a local port for the connection-refusal test", failure);
        }
    }

    private String eventBody(String eventId) {
        return """
                {"eventId":"%s","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"batch"}}"""
                .formatted(eventId);
    }

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }
}
