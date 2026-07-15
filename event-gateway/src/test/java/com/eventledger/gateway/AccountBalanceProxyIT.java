package com.eventledger.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.eventledger.gateway.support.AccountCircuitTestSupport.resetAccountCircuit;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountBalanceProxyIT {

    private static final String BALANCE_PATH = "/accounts/acct-1/balance";
    private static final String EXACT_BALANCE = "-1234567890.123456789012345678";
    private static final String AS_OF = "2026-07-14T16:05:00Z";

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
    private JsonMapper jsonMapper;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        account.resetAll();
        resetAccountCircuit(circuitBreakerRegistry);
    }

    @Test
    void getBalance_shouldProxyExactAccountBalance_whenAccountReturns200() throws Exception {
        stubBalance(200, balanceBody("acct-1", EXACT_BALANCE));

        HttpResponse<String> response = getBalance("acct-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.path("accountId").asString()).isEqualTo("acct-1");
        assertThat(body.path("currency").asString()).isEqualTo("USD");
        assertThat(body.path("balance").decimalValue()).isEqualTo(new BigDecimal(EXACT_BALANCE));
        assertThat(body.path("asOf").asString()).isEqualTo(AS_OF);
        assertThat(body.size()).isEqualTo(4);
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    @Test
    void getBalance_shouldReturn404ProblemDetail_whenAccountDoesNotKnowAccount() throws Exception {
        stubBalance(404, "{\"type\":\"urn:event-ledger:problem:not-found\",\"status\":404,"
                + "\"detail\":\"internal-account-lookup-detail\"}");

        HttpResponse<String> response = getBalance("acct-1");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:not-found");
        assertThat(body.path("title").asString()).isEqualTo("Account not found");
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("detail").asString()).isEqualTo("The requested account does not exist");
        assertThat(body.path("instance").asString()).isEqualTo(BALANCE_PATH);
        assertThat(response.body()).doesNotContain("internal-account-lookup-detail");
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    @Test
    void getBalance_shouldReturn503ProblemDetail_whenAccountReturnsRetryableFailure() throws Exception {
        stubBalance(503, "{\"type\":\"about:blank\",\"status\":503,\"detail\":\"internal-outage-detail\"}");

        HttpResponse<String> response = getBalance("acct-1");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.path("title").asString()).isEqualTo("Account Service unavailable");
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("detail").asString()).isEqualTo("Account balance is temporarily unavailable.");
        assertThat(body.path("instance").asString()).isEqualTo(BALANCE_PATH);
        assertThat(body.has("eventId")).isFalse();
        assertThat(body.has("applicationStatus")).isFalse();
        assertThat(response.body()).doesNotContain("internal-outage-detail");
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    @Test
    void getBalance_shouldReturn503ProblemDetail_whenAccountConnectionIsLost() throws Exception {
        account.stubFor(get(urlEqualTo(BALANCE_PATH)).willReturn(
                aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = getBalance("acct-1");

        assertThat(response.statusCode()).isEqualTo(503);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:account-unavailable");
        assertThat(body.has("eventId")).isFalse();
        assertThat(body.has("applicationStatus")).isFalse();
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    @Test
    void getBalance_shouldReturn400WithoutAccountCall_whenAccountIdIsInvalid() throws Exception {
        HttpResponse<String> response = getBalance("-bad");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(body.path("errors").get(0).path("field").asString()).isEqualTo("accountId");
        assertThat(anyAccountRequestCount()).isZero();
    }

    @ParameterizedTest(name = "malformedBody=[{0}]")
    @ValueSource(strings = {
            "",
            "{secret-parse-detail not json",
            "{}",
            "{\"accountId\":\"acct-1\",\"balance\":10.00,\"asOf\":\"2026-07-14T16:05:00Z\"}",
            "{\"accountId\":\"acct-1\",\"currency\":\"USD\",\"balance\":10.00}",
            "{\"accountId\":\"acct-1\",\"currency\":\"USD\",\"asOf\":\"2026-07-14T16:05:00Z\"}"})
    void getBalance_shouldReturn502_whenAccountReturnsMalformedSuccess(String malformedBody) throws Exception {
        stubBalance(200, malformedBody);

        HttpResponse<String> response = getBalance("acct-1");

        assertThat(response.statusCode()).isEqualTo(502);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(response.body()).doesNotContain("secret-parse-detail");
        assertThat(response.body()).doesNotContain("acct-1\",\"currency");
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    @Test
    void getBalance_shouldReturn502_whenAccountResponseBelongsToDifferentAccount() throws Exception {
        stubBalance(200, balanceBody("acct-other", "10.00"));

        HttpResponse<String> response = getBalance("acct-1");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    @Test
    void getBalance_shouldReturn502_whenAccountReturnsUnexpectedClientError() throws Exception {
        stubBalance(400, "{\"type\":\"urn:event-ledger:problem:validation\",\"status\":400,"
                + "\"detail\":\"internal-validation-detail\"}");

        HttpResponse<String> response = getBalance("acct-1");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response.body()).path("type").asString())
                .isEqualTo("urn:event-ledger:problem:downstream-contract");
        assertThat(response.body()).doesNotContain("internal-validation-detail");
        assertThat(balanceRequestCount()).isEqualTo(1);
    }

    private String balanceBody(String accountId, String balance) {
        return """
                {"accountId":"%s","currency":"USD","balance":%s,"asOf":"%s"}"""
                .formatted(accountId, balance, AS_OF);
    }

    private void stubBalance(int status, String body) {
        account.stubFor(get(urlEqualTo(BALANCE_PATH)).willReturn(
                aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private int balanceRequestCount() {
        return account.countRequestsMatching(getRequestedFor(urlEqualTo(BALANCE_PATH)).build())
                .getCount();
    }

    private int anyAccountRequestCount() {
        return account.countRequestsMatching(getRequestedFor(urlMatching(".*")).build())
                .getCount();
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    private HttpResponse<String> getBalance(String accountId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/accounts/" + accountId + "/balance"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
