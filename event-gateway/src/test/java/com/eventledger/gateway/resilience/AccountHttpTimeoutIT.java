package com.eventledger.gateway.resilience;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientsProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountHttpTimeoutIT {

    private static final String ACCOUNT_ID = "acct-1";
    private static final String APPLY_PATH = "/accounts/" + ACCOUNT_ID + "/transactions";
    private static final String BALANCE_PATH = "/accounts/" + ACCOUNT_ID + "/balance";
    private static final Duration RESPONSE_CEILING = Duration.ofSeconds(3);
    private static final int ACCOUNT_RESPONSE_DELAY_MS = 2_000;

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
    private HttpClientSettings httpClientSettings;

    @Autowired
    private ClientHttpRequestFactory clientHttpRequestFactory;

    @Autowired
    private ImperativeHttpClientsProperties imperativeHttpClientsProperties;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        account.resetAll();
        events.deleteAll();
    }

    @Test
    void accountHttpClient_shouldUseBootManagedHttpComponentsWithConfiguredTimeouts() {
        assertThat(imperativeHttpClientsProperties.getFactory())
                .isEqualTo(ImperativeHttpClientsProperties.Factory.HTTP_COMPONENTS);
        assertThat(clientHttpRequestFactory)
                .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
        assertThat(httpClientSettings.connectTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(httpClientSettings.readTimeout()).isEqualTo(Duration.ofMillis(800));
        assertThat(httpClientSettings.redirects()).isEqualTo(HttpRedirects.DONT_FOLLOW);
    }

    @Test
    void submitEvent_shouldReturn503WithinBound_whenAccountResponseTimesOut() throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH)).willReturn(
                aResponse()
                        .withFixedDelay(ACCOUNT_RESPONSE_DELAY_MS)
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        long started = System.nanoTime();
        HttpResponse<String> response = submit(eventBody("evt-timeout"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(RESPONSE_CEILING);
        assertUnavailableApplyResponse(response, "evt-timeout");
        assertStoredRetryableFailure("evt-timeout");
        assertThat(applyRequestCount()).isEqualTo(1);
    }

    @Test
    void submitEvent_shouldStoreRetryableFailure_whenAccountReturns5xx() throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH)).willReturn(
                aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"about:blank\",\"status\":503,\"detail\":\"internal-5xx\"}")));

        long started = System.nanoTime();
        HttpResponse<String> response = submit(eventBody("evt-5xx"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(RESPONSE_CEILING);
        assertUnavailableApplyResponse(response, "evt-5xx");
        assertThat(response.body()).doesNotContain("internal-5xx");
        assertStoredRetryableFailure("evt-5xx");
        assertThat(applyRequestCount()).isEqualTo(1);
    }

    @Test
    void getBalance_shouldReturn503WithinBound_whenAccountResponseTimesOut() throws Exception {
        account.stubFor(get(urlEqualTo(BALANCE_PATH)).willReturn(
                aResponse()
                        .withFixedDelay(ACCOUNT_RESPONSE_DELAY_MS)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountId\":\"acct-1\",\"currency\":\"USD\","
                                + "\"balance\":10.00,\"asOf\":\"2026-07-14T16:05:00Z\"}")));

        long started = System.nanoTime();
        HttpResponse<String> response = getBalance();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(RESPONSE_CEILING);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.path("title").asString()).isEqualTo("Account Service unavailable");
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("detail").asString()).isEqualTo("Account balance is temporarily unavailable.");
        assertThat(body.has("eventId")).isFalse();
        assertThat(body.has("applicationStatus")).isFalse();
        assertThat(events.count()).isZero();
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    private void assertUnavailableApplyResponse(HttpResponse<String> response, String eventId) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.path("title").asString()).isEqualTo("Account Service unavailable");
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("detail").asString())
                .isEqualTo("Application of event " + eventId
                        + " could not be confirmed. Retrying the same eventId is safe.");
        assertThat(body.path("detail").asString()).doesNotContain("not applied");
        assertThat(body.path("eventId").asString()).isEqualTo(eventId);
        assertThat(body.path("applicationStatus").asString()).isEqualTo("APPLY_FAILED");
    }

    private void assertStoredRetryableFailure(String eventId) {
        assertThat(events.count()).isEqualTo(1);
        StoredEventEntity stored = events.findById(eventId).orElseThrow();
        assertThat(stored.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(stored.getLastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertThat(stored.getAppliedAt()).isNull();
        assertThat(stored.getAttemptCount()).isEqualTo(1);
    }

    private String eventBody(String eventId) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"batch"}}"""
                .formatted(eventId, ACCOUNT_ID);
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
