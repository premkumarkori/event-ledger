package com.eventledger.gateway;

import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventMapper;
import com.eventledger.gateway.service.EventStatusWriter;
import com.eventledger.gateway.service.EventWriter;
import com.eventledger.gateway.support.MutableTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "clients.account.base-url=http://127.0.0.1:1")
class EventQueryTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final Instant APPLIED_AT = Instant.parse("2026-07-15T09:00:00.250Z");
    private static final Instant BASE_EVENT_TIME = Instant.parse("2026-05-15T14:00:00Z");

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
    private EventWriter eventWriter;

    @Autowired
    private EventStatusWriter statusWriter;

    @Autowired
    private EventRepository events;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private JsonMapper jsonMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void resetDatabaseAndClock() {
        events.deleteAll();
        clock.setInstant(RECEIVED_AT);
    }

    private void insert(String eventId, String accountId, Instant eventTime, String metadata) {
        eventWriter.insert(new NormalizedEvent(
                eventId, accountId, EventType.CREDIT, new BigDecimal("150.00"), "USD", eventTime, metadata));
    }

    @Test
    void getEvent_shouldReturnFrozenEventResponse_whenEventExists() throws Exception {
        insert("evt-1", "acct-1", BASE_EVENT_TIME, "{\"source\":\"batch\"}");

        HttpResponse<String> response = get("/events/evt-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.path("eventId").asString()).isEqualTo("evt-1");
        assertThat(body.path("accountId").asString()).isEqualTo("acct-1");
        assertThat(body.path("type").asString()).isEqualTo("CREDIT");
        assertThat(body.path("amount").isNumber()).isTrue();
        assertThat(new BigDecimal(body.path("amount").asString())).isEqualByComparingTo("150.00");
        assertThat(body.path("currency").asString()).isEqualTo("USD");
        assertThat(body.path("eventTimestamp").asString()).isEqualTo(BASE_EVENT_TIME.toString());
        assertThat(body.path("metadata").isObject()).isTrue();
        assertThat(body.path("metadata").path("source").asString()).isEqualTo("batch");
        assertThat(body.path("applicationStatus").asString()).isEqualTo("RECEIVED");
        assertThat(body.path("receivedAt").asString()).isEqualTo(RECEIVED_AT.toString());
        assertThat(body.size()).isEqualTo(9);
    }

    @Test
    void getEvent_shouldOmitAppliedAt_whenEventIsNotApplied() throws Exception {
        insert("evt-1", "acct-1", BASE_EVENT_TIME, "{}");

        HttpResponse<String> response = get("/events/evt-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.path("applicationStatus").asString()).isEqualTo("RECEIVED");
        assertThat(body.has("appliedAt")).isFalse();
    }

    @Test
    void getEvent_shouldReturnAppliedAt_whenEventIsApplied() throws Exception {
        insert("evt-1", "acct-1", BASE_EVENT_TIME, "{}");
        clock.setInstant(APPLIED_AT);
        statusWriter.markApplied("evt-1");

        HttpResponse<String> response = get("/events/evt-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.path("applicationStatus").asString()).isEqualTo("APPLIED");
        assertThat(body.path("appliedAt").asString()).isEqualTo(APPLIED_AT.toString());
    }

    @Test
    void getEvent_shouldReturn404ProblemDetail_whenEventDoesNotExist() throws Exception {
        HttpResponse<String> response = get("/events/evt-missing");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:not-found");
        assertThat(body.path("title").asString()).isEqualTo("Event not found");
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("detail").asString()).isEqualTo("The requested event does not exist");
        assertThat(body.path("instance").asString()).isEqualTo("/events/evt-missing");
        assertThat(response.body()).doesNotContain("Exception");
        assertThat(body.has("errors")).isFalse();
    }

    @Test
    void getEvent_shouldReturn400ValidationProblem_whenEventIdIsInvalid() throws Exception {
        HttpResponse<String> response = get("/events/-invalid");

        assertValidationProblem(response, "eventId");
    }

    @Test
    void listEvents_shouldOrderByTimestampThenEventId_whenArrivalOrderDiffers() throws Exception {
        insert("evt-late", "acct-1", BASE_EVENT_TIME.plusSeconds(200), "{}");
        insert("evt-early", "acct-1", BASE_EVENT_TIME.plusSeconds(100), "{}");
        insert("evt-z", "acct-1", BASE_EVENT_TIME.plusSeconds(150), "{}");
        insert("evt-y", "acct-1", BASE_EVENT_TIME.plusSeconds(150), "{}");
        insert("evt-x", "acct-1", BASE_EVENT_TIME.plusSeconds(150), "{}");

        HttpResponse<String> response = get("/events?account=acct-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(eventIds(body))
                .containsExactly("evt-early", "evt-x", "evt-y", "evt-z", "evt-late");
    }

    @Test
    void listEvents_shouldReturnOnlyFrozenListFields_whenEventsExist() throws Exception {
        insert("evt-1", "acct-1", BASE_EVENT_TIME, "{\"source\":\"batch\"}");

        HttpResponse<String> response = get("/events?account=acct-1");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.isArray()).isTrue();
        JsonNode item = body.get(0);
        assertThat(item.size()).isEqualTo(3);
        assertThat(item.has("eventId")).isTrue();
        assertThat(item.has("eventTimestamp")).isTrue();
        assertThat(item.has("applicationStatus")).isTrue();
    }

    @Test
    void listEvents_shouldReturnEmptyArray_whenAccountDoesNotExist() throws Exception {
        HttpResponse<String> response = get("/events?account=acct-none");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void listEvents_shouldReturn400ValidationProblem_whenAccountIsMissing() throws Exception {
        HttpResponse<String> response = get("/events");

        assertValidationProblem(response, "account");
    }

    @Test
    void listEvents_shouldReturn400ValidationProblem_whenAccountIsBlank() throws Exception {
        HttpResponse<String> response = get("/events?account=");

        assertValidationProblem(response, "account");
    }

    @Test
    void listEvents_shouldReturn400ValidationProblem_whenAccountIsInvalid() throws Exception {
        for (String invalidAccount : List.of("%20has%20space", "-bad", "a".repeat(129))) {
            HttpResponse<String> response = get("/events?account=" + invalidAccount);
            assertValidationProblem(response, "account");
        }
    }

    @Test
    void localReads_shouldWork_whenAccountServiceIsUnavailable() throws Exception {
        insert("evt-1", "acct-1", BASE_EVENT_TIME, "{}");

        assertThat(get("/events/evt-1").statusCode()).isEqualTo(200);
        HttpResponse<String> list = get("/events?account=acct-1");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(eventIds(json(list.body()))).containsExactly("evt-1");
    }

    @Test
    void getEvent_shouldNotChangeStoredState_whenReadSucceeds() throws Exception {
        insert("evt-1", "acct-1", BASE_EVENT_TIME, "{\"source\":\"batch\"}");
        StoredEvent before = snapshot("evt-1");

        HttpResponse<String> response = get("/events/evt-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(snapshot("evt-1")).isEqualTo(before);
    }

    @Test
    void listEvents_shouldNotChangeStoredRows_whenReadSucceeds() throws Exception {
        insert("evt-1", "acct-1", BASE_EVENT_TIME, "{}");
        insert("evt-2", "acct-1", BASE_EVENT_TIME.plusSeconds(10), "{}");
        long countBefore = events.count();
        List<StoredEvent> rowsBefore = List.of(snapshot("evt-1"), snapshot("evt-2"));

        HttpResponse<String> response = get("/events?account=acct-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(events.count()).isEqualTo(countBefore);
        assertThat(List.of(snapshot("evt-1"), snapshot("evt-2"))).isEqualTo(rowsBefore);
    }

    private StoredEvent snapshot(String eventId) {
        return StoredEventMapper.toStoredEvent(events.findById(eventId).orElseThrow());
    }

    private JsonNode json(String body) {
        return jsonMapper.readTree(body);
    }

    private List<String> eventIds(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.path("eventId").asString()));
        return ids;
    }

    private List<String> fieldNames(JsonNode problem) {
        List<String> names = new ArrayList<>();
        problem.path("errors").forEach(error -> names.add(error.path("field").asString()));
        return names;
    }

    private void assertValidationProblem(HttpResponse<String> response, String field) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/problem+json");
        JsonNode body = json(response.body());
        assertThat(body.path("type").asString()).isEqualTo("urn:event-ledger:problem:validation");
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(fieldNames(body)).containsExactly(field);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
