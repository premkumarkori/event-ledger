package com.eventledger.gateway;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.error.FieldValidationException;
import com.eventledger.gateway.service.EventNormalizer;
import jakarta.validation.Valid;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EventRequestValidationTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");

    @RestController
    static class ValidationProbeController {

        private final EventNormalizer normalizer;

        ValidationProbeController(EventNormalizer normalizer) {
            this.normalizer = normalizer;
        }

        @PostMapping("/test/validation/events")
        ResponseEntity<Void> validate(@Valid @RequestBody EventRequest request) {
            normalizer.normalize(request);
            return ResponseEntity.ok().build();
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        ValidationProbeController validationProbeController(EventNormalizer normalizer) {
            return new ValidationProbeController(normalizer);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private EventNormalizer normalizer;

    @Autowired
    private JsonMapper jsonMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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
    void validate_shouldReturn200_whenRequestValid() throws Exception {
        HttpResponse<String> response = post("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"batch"}}""");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void validate_shouldReturn400ProblemDetailNamingField_whenBeanValidationFails() throws Exception {
        HttpResponse<String> response = post("""
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
    }

    @Test
    void validate_shouldNotEchoRejectedValues_whenAmountOversizedAndMetadataSecret() throws Exception {
        HttpResponse<String> response = post("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":123456789012345678901,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"secret":"TOP-SECRET-9999"}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(fieldNames(body)).contains("amount");
        assertThat(response.body()).doesNotContain("123456789012345678901");
        assertThat(response.body()).doesNotContain("TOP-SECRET-9999");
    }

    @Test
    void validate_shouldReturn400ProblemDetailNamingType_whenTypeUnknown() throws Exception {
        HttpResponse<String> response = post("""
                {"eventId":"evt-1","accountId":"acct-1","type":"TRANSFER","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(fieldNames(body)).containsExactly("type");
    }

    @Test
    void validate_shouldReturn400ProblemDetailNamingTimestamp_whenTimestampInvalid() throws Exception {
        HttpResponse<String> response = post("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"not-a-timestamp","metadata":{}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldNames(json(response.body()))).containsExactly("eventTimestamp");
    }

    @Test
    void validate_shouldReturn400ProblemDetailNamingRequestBody_whenJsonMalformed() throws Exception {
        HttpResponse<String> response = post("{ this is not json ");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(fieldNames(body)).containsExactly("requestBody");
    }

    @Test
    void validate_shouldReturn400ProblemDetailNamingMetadata_whenMetadataIsArray() throws Exception {
        HttpResponse<String> response = post("""
                {"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":150.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":[1,2,3]}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(fieldNames(json(response.body()))).contains("metadata");
    }

    private List<String> fieldNames(JsonNode problem) {
        List<String> names = new ArrayList<>();
        problem.path("errors").forEach(error -> names.add(error.path("field").asString()));
        return names;
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/test/validation/events"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
