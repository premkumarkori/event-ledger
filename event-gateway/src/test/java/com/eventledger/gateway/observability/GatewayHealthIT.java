package com.eventledger.gateway.observability;

import com.eventledger.gateway.health.AccountDependencyState;
import com.eventledger.gateway.health.LocalDatabaseCheck;
import com.eventledger.gateway.persistence.EventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayHealthIT {

    private static final String ACCOUNT_SERVICE_CIRCUIT = "accountService";
    private static final String ACCOUNT_ID = "acct-health-1";
    private static final String APPLY_PATH = "/accounts/" + ACCOUNT_ID + "/transactions";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");

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
    private AccountDependencyState dependencyState;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoSpyBean
    private LocalDatabaseCheck databaseCheck;

    @BeforeEach
    void resetScenario() {
        account.resetAll();
        events.deleteAll();
        dependencyState.clearObservations();
        accountCircuit().reset();
        reset(databaseCheck);
    }

    @Test
    void getGatewayHealth_shouldReturnUpWithUnknownAccount_whenNoAccountCallObserved() throws Exception {
        int before = totalAccountRequestCount();

        HttpResponse<String> response = getHealth();
        JsonNode body = json.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("service").asText()).isEqualTo("event-gateway");
        assertThat(body.path("checks").path("database").asText()).isEqualTo("UP");
        assertThat(body.path("checks").path("accountService").asText()).isEqualTo("UNKNOWN");
        assertThat(body.path("checks").path("circuitBreaker").asText()).isEqualTo("CLOSED");
        assertThat(body.path("checks").properties()).hasSize(3);
        assertThat(totalAccountRequestCount()).isEqualTo(before);
    }

    @Test
    void getGatewayHealth_shouldNotCallAccount_whenHealthIsRequestedRepeatedly() throws Exception {
        int before = totalAccountRequestCount();

        getHealth();
        getHealth();
        getHealth();

        assertThat(totalAccountRequestCount()).isEqualTo(before);
    }

    @Test
    void getGatewayHealth_shouldReportAccountUp_whenRealAccountCallSucceeds() throws Exception {
        stubAccountSuccess("evt-health-success");
        assertThat(submit("evt-health-success").statusCode()).isEqualTo(201);

        HttpResponse<String> response = getHealth();
        JsonNode body = json.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("checks").path("accountService").asText()).isEqualTo("UP");
        assertThat(body.path("checks").path("circuitBreaker").asText()).isEqualTo("CLOSED");
    }

    @Test
    void getGatewayHealth_shouldReportAccountUp_whenBalanceCallReturns404() throws Exception {
        String missingAccountId = "acct-health-missing";
        account.stubFor(get(urlEqualTo("/accounts/" + missingAccountId + "/balance"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"type":"urn:event-ledger:problem:not-found",
                                 "title":"Account not found","status":404}""")));

        HttpResponse<String> balance = getBalance(missingAccountId);
        HttpResponse<String> health = getHealth();
        JsonNode body = json.readTree(health.body());

        assertThat(balance.statusCode()).isEqualTo(404);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("checks").path("accountService").asText()).isEqualTo("UP");
        assertThat(body.path("checks").path("circuitBreaker").asText()).isEqualTo("CLOSED");
    }

    @Test
    void getGatewayHealth_shouldReportDegraded_whenAccountReturns5xx() throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .willReturn(aResponse().withStatus(503)));
        assertThat(submit("evt-health-unavailable").statusCode()).isEqualTo(503);

        HttpResponse<String> response = getHealth();
        JsonNode body = json.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("DEGRADED");
        assertThat(body.path("checks").path("database").asText()).isEqualTo("UP");
        assertThat(body.path("checks").path("accountService").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void getGatewayHealth_shouldReportDegradedWithoutAccountCall_whenCircuitIsOpen() throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .willReturn(aResponse().withStatus(503)));
        for (int index = 1; index <= 4; index++) {
            assertThat(submit("evt-health-open-" + index).statusCode()).isEqualTo(503);
        }
        assertThat(accountCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        AccountDependencyState.Snapshot beforeRejectedCall = dependencyState.snapshot();
        int before = totalAccountRequestCount();

        assertThat(submit("evt-health-open-rejected").statusCode()).isEqualTo(503);
        assertThat(dependencyState.snapshot()).isEqualTo(beforeRejectedCall);
        assertThat(totalAccountRequestCount()).isEqualTo(before);

        HttpResponse<String> response = getHealth();
        JsonNode body = json.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("DEGRADED");
        assertThat(body.path("checks").path("circuitBreaker").asText()).isEqualTo("OPEN");
        assertThat(dependencyState.snapshot()).isEqualTo(beforeRejectedCall);
        assertThat(totalAccountRequestCount()).isEqualTo(before);
    }

    @Test
    void getGatewayHealth_shouldReturnDown_whenLocalDatabaseCheckFails() throws Exception {
        doReturn(false).when(databaseCheck).isUp();

        HttpResponse<String> response = getHealth();
        JsonNode body = json.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("service").asText()).isEqualTo("event-gateway");
        assertThat(body.path("checks").path("database").asText()).isEqualTo("DOWN");
    }

    @Test
    void getEvent_shouldRemainReadable_whenHealthIsDegraded() throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .willReturn(aResponse().withStatus(503)));
        assertThat(submit("evt-health-readable").statusCode()).isEqualTo(503);

        HttpResponse<String> health = getHealth();
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json.readTree(health.body()).path("status").asText()).isEqualTo("DEGRADED");

        HttpResponse<String> event = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events/evt-health-readable"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(event.statusCode()).isEqualTo(200);
        assertThat(json.readTree(event.body()).path("eventId").asText()).isEqualTo("evt-health-readable");
    }

    private void stubAccountSuccess(String eventId) {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"" + eventId + "\""))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                                 "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                                .formatted(eventId, ACCOUNT_ID, EVENT_TIME, ACCOUNT_APPLIED_AT))));
    }

    private HttpResponse<String> submit(String eventId) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s"}"""
                .formatted(eventId, ACCOUNT_ID, EVENT_TIME);
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getHealth() throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getBalance(String accountId) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/accounts/" + accountId + "/balance"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int totalAccountRequestCount() {
        return account.getAllServeEvents().size();
    }

    private CircuitBreaker accountCircuit() {
        return circuitBreakerRegistry.circuitBreaker(ACCOUNT_SERVICE_CIRCUIT);
    }
}
