package com.eventledger.gateway.observability;

import com.eventledger.gateway.persistence.EventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
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
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@ActiveProfiles("test")
class PrometheusExpositionIT {

    private static final String ACCOUNT_ID = "acct-prom-1";
    private static final String EVENT_ID = "evt-prom-created";
    private static final String APPLY_PATH = "/accounts/" + ACCOUNT_ID + "/transactions";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");
    private static final Pattern CREATED_OUTCOME_LINE = Pattern.compile(
            "(?m)^ledger_events_total\\{[^}]*outcome=\"created\"[^}]*}\\s+1(?:\\.0)?$");

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

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventRepository events;

    @BeforeEach
    void resetScenario() {
        account.resetAll();
        events.deleteAll();
    }

    @Test
    void scrapePrometheus_shouldExposeLedgerEventsTotal_whenEventOutcomeOccurs() throws Exception {
        account.stubFor(post(urlEqualTo(APPLY_PATH))
                .withRequestBody(containing("\"eventId\":\"" + EVENT_ID + "\""))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                                 "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                                .formatted(EVENT_ID, ACCOUNT_ID, EVENT_TIME, ACCOUNT_APPLIED_AT))));

        HttpResponse<String> submit = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                                 "currency":"USD","eventTimestamp":"%s"}"""
                                .formatted(EVENT_ID, ACCOUNT_ID, EVENT_TIME)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(submit.statusCode()).isEqualTo(201);

        HttpResponse<String> scrape = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("ledger_events_total");
        assertThat(CREATED_OUTCOME_LINE.matcher(scrape.body()).find()).isTrue();
        assertThat(scrape.body()).doesNotContain("eventId=");
        assertThat(scrape.body()).doesNotContain("accountId=");
        assertThat(scrape.body()).doesNotContain("traceId=");
    }
}
