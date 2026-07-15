package com.eventledger.gateway;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.error.FieldValidationException;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.service.EventNormalizer;
import com.eventledger.gateway.support.MutableTestClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EventRequestValidationTest {

    private static final String ACCOUNT_PATH = "/accounts/acct-1/transactions";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final Instant ACCOUNT_APPLIED_AT = Instant.parse("2026-07-14T16:00:05Z");

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
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(RECEIVED_AT);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventNormalizer normalizer;

    @Autowired
    private EventRepository events;

    @Autowired
    private JsonMapper jsonMapper;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        account.resetAll();
        events.deleteAll();
    }

    private JsonNode json(String text) {
        return jsonMapper.readTree(text);
    }

    private EventRequest request(String eventId, String accountId, EventType type,
                                 String amount, String currency, Instant eventTime, JsonNode metadata) {
        return new EventRequest(
                eventId, accountId, type,
                amount == null ? null : new BigDecimal(amount),
                currency, eventTime, metadata);
    }

    private void assertFieldRejected(String field, ThrowingCallable action) {
        assertThatExceptionOfType(FieldValidationException.class)
                .isThrownBy(action)
                .satisfies(exception -> assertThat(exception.getField()).isEqualTo(field));
    }

    @Test
    void normalize_shouldRejectRequestBody_whenRequestIsNull() {
        assertFieldRejected("requestBody", () -> normalizer.normalize(null));
    }

    @ParameterizedTest(name = "eventId=[{0}]")
    @ValueSource(strings = {" ", "-leading", "a b", "a/b", "a?b", "a#b", "a%20b", "has/slash"})
    void normalize_shouldRejectEventId_whenBlankWhitespaceOrDelimiter(String eventId) {
        assertFieldRejected("eventId", () -> normalizer.normalize(
                request(eventId, "acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, json("{}"))));
    }

    @Test
    void normalize_shouldRejectEventId_whenNull() {
        assertFieldRejected("eventId", () -> normalizer.normalize(
                request(null, "acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, json("{}"))));
    }

    @ParameterizedTest(name = "accountId=[{0}]")
    @ValueSource(strings = {" ", "-bad", "a b", "acct/1"})
    void normalize_shouldRejectAccountId_whenInvalid(String accountId) {
        assertFieldRejected("accountId", () -> normalizer.normalize(
                request("evt-1", accountId, EventType.CREDIT, "150.00", "USD", EVENT_TIME, json("{}"))));
    }

    @Test
    void normalize_shouldRejectType_whenNull() {
        assertFieldRejected("type", () -> normalizer.normalize(
                request("evt-1", "acct-1", null, "150.00", "USD", EVENT_TIME, json("{}"))));
    }

    @ParameterizedTest(name = "amount=[{0}]")
    @ValueSource(strings = {"0", "-5", "0.00"})
    void normalize_shouldRejectAmount_whenZeroOrNegative(String amount) {
        assertFieldRejected("amount", () -> normalizer.normalize(
                request("evt-1", "acct-1", EventType.CREDIT, amount, "USD", EVENT_TIME, json("{}"))));
    }

    @Test
    void normalize_shouldRejectAmount_whenMoreThanTwentyIntegerDigits() {
        assertFieldRejected("amount", () -> normalizer.normalize(request(
                "evt-1", "acct-1", EventType.CREDIT, "123456789012345678901", "USD", EVENT_TIME, json("{}"))));
    }

    @Test
    void normalize_shouldRejectAmount_whenMoreThanEighteenFractionDigits() {
        assertFieldRejected("amount", () -> normalizer.normalize(request(
                "evt-1", "acct-1", EventType.CREDIT, "1.1234567890123456789", "USD", EVENT_TIME, json("{}"))));
    }

    @Test
    void normalize_shouldAcceptAmount_whenTrailingZerosStripToRepresentable() {
        NormalizedEvent normalized = normalizer.normalize(request(
                "evt-1", "acct-1", EventType.CREDIT, "0.100000000000000000", "USD", EVENT_TIME, json("{}")));

        assertThat(normalized.amount()).isEqualByComparingTo("0.1");
    }

    @ParameterizedTest(name = "currency=[{0}]")
    @ValueSource(strings = {"US", "USDX", "12A", "u$d", "US ", " US", "éur", "XZZ"})
    void normalize_shouldRejectCurrency_whenInvalidOrUnrecognized(String currency) {
        assertFieldRejected("currency", () -> normalizer.normalize(
                request("evt-1", "acct-1", EventType.CREDIT, "150.00", currency, EVENT_TIME, json("{}"))));
    }

    @Test
    void normalize_shouldUppercaseCurrency_whenLowercaseValid() {
        NormalizedEvent normalized = normalizer.normalize(request(
                "evt-1", "acct-1", EventType.CREDIT, "150.00", "usd", EVENT_TIME, json("{}")));

        assertThat(normalized.currency()).isEqualTo("USD");
    }

    @Test
    void normalize_shouldRejectTimestamp_whenNull() {
        assertFieldRejected("eventTimestamp", () -> normalizer.normalize(
                request("evt-1", "acct-1", EventType.CREDIT, "150.00", "USD", null, json("{}"))));
    }

    @ParameterizedTest(name = "metadata=[{0}]")
    @ValueSource(strings = {"[]", "[1,2]", "\"scalar\"", "5", "true"})
    void normalize_shouldRejectMetadata_whenNotAnObject(String metadata) {
        assertFieldRejected("metadata", () -> normalizer.normalize(
                request("evt-1", "acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, json(metadata))));
    }

    @Test
    void normalize_shouldDefaultMetadataToEmptyObject_whenNull() {
        NormalizedEvent fromNull = normalizer.normalize(
                request("evt-1", "acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, null));
        NormalizedEvent fromJsonNull = normalizer.normalize(
                request("evt-2", "acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, json("null")));

        assertThat(fromNull.metadata()).isEqualTo("{}");
        assertThat(fromJsonNull.metadata()).isEqualTo("{}");
    }

    @Test
    void normalize_shouldPreserveMetadataNumber_whenValueExceedsDoubleRange() {
        NormalizedEvent normalized = normalizer.normalize(request(
                "evt-1", "acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                json("{\"largeNumber\":1e400}")));

        JsonNode largeNumber = json(normalized.metadata()).path("largeNumber");
        assertThat(largeNumber.isNumber()).isTrue();
        assertThat(largeNumber.decimalValue()).isEqualByComparingTo("1e400");
    }

    @Test
    void normalize_shouldPreserveCaseSensitiveIds_whenValid() {
        NormalizedEvent normalized = normalizer.normalize(
                request("Evt-ABC", "Acct-XYZ", EventType.CREDIT, "150.00", "USD", EVENT_TIME, json("{}")));

        assertThat(normalized.eventId()).isEqualTo("Evt-ABC");
        assertThat(normalized.accountId()).isEqualTo("Acct-XYZ");
    }

    @Test
    void submitEvent_shouldReturn201_whenRequestValid() throws Exception {
        stubAccountApplied();

        HttpResponse<String> response = submit("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"batch"}}""");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(events.count()).isEqualTo(1);
        assertThat(accountRequestCount()).isEqualTo(1);
    }

    @Test
    void submitEvent_shouldReturn201_whenIdentifiersHaveMaximumLength() throws Exception {
        String eventId = "e".repeat(128);
        String accountId = "a".repeat(128);
        stubAccountApplied(eventId, accountId);

        HttpResponse<String> response = submit("""
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{}}"""
                .formatted(eventId, accountId));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(events.existsById(eventId)).isTrue();
        assertThat(anyAccountRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "field={0}")
    @ValueSource(strings = {"eventId", "accountId"})
    void submitEvent_shouldReturn400WithoutSideEffects_whenIdentifierExceedsMaximumLength(String field)
            throws Exception {
        String tooLong = "x".repeat(129);
        String eventId = "eventId".equals(field) ? tooLong : "evt-1";
        String accountId = "accountId".equals(field) ? tooLong : "acct-1";

        HttpResponse<String> response = submit("""
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{}}"""
                .formatted(eventId, accountId));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldNames(json(response.body()))).containsExactly(field);
        assertNoWriteOrCall();
    }

    @Test
    void submitEvent_shouldReturn400ProblemDetailNamingField_whenBeanValidationFails() throws Exception {
        HttpResponse<String> response = submit("""
                {"eventId":"","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("errors").isArray()).isTrue();
        assertThat(fieldNames(body)).contains("eventId");
        assertNoWriteOrCall();
    }

    @Test
    void submitEvent_shouldNotEchoRejectedValues_whenAmountOversizedAndMetadataSecret() throws Exception {
        HttpResponse<String> response = submit("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":123456789012345678901,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"secret":"TOP-SECRET-9999"}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(fieldNames(body)).contains("amount");
        assertThat(response.body()).doesNotContain("123456789012345678901");
        assertThat(response.body()).doesNotContain("TOP-SECRET-9999");
        assertNoWriteOrCall();
    }

    @Test
    void submitEvent_shouldReturn400ProblemDetailNamingType_whenTypeUnknown() throws Exception {
        HttpResponse<String> response = submit("""
                {"eventId":"evt-1","accountId":"acct-1","type":"TRANSFER","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(fieldNames(body)).containsExactly("type");
        assertNoWriteOrCall();
    }

    @Test
    void submitEvent_shouldReturn400ProblemDetailNamingTimestamp_whenTimestampInvalid() throws Exception {
        HttpResponse<String> response = submit("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"not-a-timestamp","metadata":{}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldNames(json(response.body()))).containsExactly("eventTimestamp");
        assertNoWriteOrCall();
    }

    @Test
    void submitEvent_shouldReturn400ProblemDetailNamingRequestBody_whenJsonMalformed() throws Exception {
        HttpResponse<String> response = submit("{ this is not json ");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(fieldNames(body)).containsExactly("requestBody");
        assertNoWriteOrCall();
    }

    @Test
    void submitEvent_shouldReturn400ProblemDetailNamingMetadata_whenMetadataIsArray() throws Exception {
        HttpResponse<String> response = submit("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":[1,2,3]}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldNames(json(response.body()))).contains("metadata");
        assertNoWriteOrCall();
    }

    private void assertNoWriteOrCall() {
        assertThat(events.count()).isZero();
        assertThat(anyAccountRequestCount()).isZero();
    }

    private void stubAccountApplied() {
        stubAccountApplied("evt-1", "acct-1");
    }

    private void stubAccountApplied(String eventId, String accountId) {
        String accountPath = "/accounts/" + accountId + "/transactions";
        account.stubFor(post(urlEqualTo(accountPath)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,
                         "currency":"USD","eventTimestamp":"%s","appliedAt":"%s"}"""
                        .formatted(eventId, accountId, EVENT_TIME, ACCOUNT_APPLIED_AT))));
    }

    private int accountRequestCount() {
        return account.countRequestsMatching(postRequestedFor(urlEqualTo(ACCOUNT_PATH)).build())
                .getCount();
    }

    private int anyAccountRequestCount() {
        return account.getAllServeEvents().size();
    }

    private List<String> fieldNames(JsonNode problem) {
        List<String> names = new ArrayList<>();
        problem.path("errors").forEach(error -> names.add(error.path("field").asString()));
        return names;
    }

    private HttpResponse<String> submit(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
