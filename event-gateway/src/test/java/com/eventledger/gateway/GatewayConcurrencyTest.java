package com.eventledger.gateway;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.service.EventReader;
import com.eventledger.gateway.support.MutableTestClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayConcurrencyTest {

    private static final String ACCOUNT_PATH = "/accounts/acct-1/transactions";
    private static final String SCENARIO = "late-failure-after-success";
    private static final String APPLIED_STATE = "first-call-applied";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

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
    static class TestFixtureConfiguration {

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(RECEIVED_AT);
        }

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
        private final CountDownLatch firstCallAtBoundary = new CountDownLatch(1);
        private final CountDownLatch bothCallsAtBoundary = new CountDownLatch(2);
        private final CountDownLatch secondCallReleased = new CountDownLatch(1);

        @Override
        public ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution) throws IOException {
            int order = outboundCalls.incrementAndGet();
            bothCallsAtBoundary.countDown();
            if (order == 1) {
                firstCallAtBoundary.countDown();
                awaitOrFail(bothCallsAtBoundary, "both calls to reach the Account boundary");
            } else {
                awaitOrFail(secondCallReleased, "the test to release the late failure");
            }
            return execution.execute(request, body);
        }

        void awaitFirstCallAtBoundary() throws IOException {
            awaitOrFail(firstCallAtBoundary, "the first call to reach the Account boundary");
        }

        void releaseSecondCall() {
            secondCallReleased.countDown();
        }

        int outboundCallCount() {
            return outboundCalls.get();
        }

        private static void awaitOrFail(CountDownLatch latch, String what) throws IOException {
            try {
                if (!latch.await(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("timed out waiting for " + what);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for " + what, interrupted);
            }
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventRepository events;

    @Autowired
    private EventReader eventReader;

    @Autowired
    private LateFailureCoordinator coordinator;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        account.resetAll();
        events.deleteAll();
    }

    @Test
    void submitEvent_shouldKeepAppliedState_whenConcurrentFailureArrivesAfterSuccess() throws Exception {
        stubFirstApplyThenLateFailure();
        ExecutorService submissionPool = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<HttpResponse<String>> submissions =
                new ExecutorCompletionService<>(submissionPool);
        try {
            submissions.submit(() -> submit(baselineBody()));
            coordinator.awaitFirstCallAtBoundary();
            submissions.submit(() -> submit(baselineBody()));

            HttpResponse<String> firstCompletedResponse = takeCompletedResponse(submissions);
            assertThat(firstCompletedResponse.statusCode()).isEqualTo(201);
            StoredEvent appliedBeforeLateFailure = snapshot("evt-1");
            assertThat(appliedBeforeLateFailure.applicationStatus()).isEqualTo(ApplicationStatus.APPLIED);

            coordinator.releaseSecondCall();
            HttpResponse<String> lateFailureResponse = takeCompletedResponse(submissions);
            assertThat(lateFailureResponse.statusCode()).isEqualTo(503);

            StoredEvent finalEvent = snapshot("evt-1");
            assertThat(events.count()).isEqualTo(1);
            assertThat(coordinator.outboundCallCount()).isEqualTo(2);
            assertThat(accountRequestCount()).isEqualTo(2);
            assertThat(finalEvent.applicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
            assertThat(finalEvent).isEqualTo(appliedBeforeLateFailure);
            assertThat(finalEvent.attemptCount()).isEqualTo(1);
            assertThat(finalEvent.version()).isEqualTo(1);
            assertThat(finalEvent.appliedAt()).isEqualTo(RECEIVED_AT);
            assertThat(finalEvent.lastAttemptAt()).isEqualTo(RECEIVED_AT);
            assertThat(finalEvent.lastFailureCode()).isNull();
            assertThat(finalEvent.lastFailureMessage()).isNull();
        } finally {
            submissionPool.shutdownNow();
        }
    }

    private void stubFirstApplyThenLateFailure() {
        account.stubFor(post(urlEqualTo(ACCOUNT_PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(appliedBody()))
                .willSetStateTo(APPLIED_STATE));
        account.stubFor(post(urlEqualTo(ACCOUNT_PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(APPLIED_STATE)
                .willReturn(aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"about:blank\",\"status\":503}")));
    }

    private String baselineBody() {
        return """
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s","metadata":{"source":"batch"}}"""
                .formatted(EVENT_TIME);
    }

    private String appliedBody() {
        return """
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                .formatted(EVENT_TIME, ACCOUNT_APPLIED_AT);
    }

    private StoredEvent snapshot(String eventId) {
        return eventReader.find(eventId).orElseThrow();
    }

    private int accountRequestCount() {
        return account.countRequestsMatching(postRequestedFor(urlEqualTo(ACCOUNT_PATH)).build())
                .getCount();
    }

    private HttpResponse<String> takeCompletedResponse(
            ExecutorCompletionService<HttpResponse<String>> submissions) throws Exception {
        Future<HttpResponse<String>> completed =
                submissions.poll(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(completed).as("a Gateway request should complete within the timeout").isNotNull();
        return completed.get();
    }

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
