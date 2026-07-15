package com.eventledger.account.observability;

import com.eventledger.account.persistence.AccountRepository;
import com.eventledger.account.persistence.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
@AutoConfigureTracing
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AccountStructuredLoggingIT {

    private static final String OUTCOME_MESSAGE = "Account transaction completed";
    private static final String ACCOUNT_ID = "acct-log-1";
    private static final String EVENT_ID = "evt-account-structured-log";
    private static final String AMOUNT = "150.00";
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final String INCOMING_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String EXPECTED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountTransactionRepository transactions;

    @BeforeEach
    void resetDatabase() {
        transactions.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void applyTransaction_shouldEmitStructuredOutcomeLog_whenTracedRequestSucceeds(CapturedOutput output)
            throws Exception {
        String requestBody = requestBody(EVENT_ID, AMOUNT, "USD");

        HttpResponse<String> response = postTransaction(requestBody);

        assertThat(response.statusCode()).isEqualTo(201);

        List<String> lines = outcomeLines(output.getAll());
        assertThat(lines).hasSize(1);
        String line = lines.get(0);
        JsonNode node = json.readTree(line);

        assertThat(node.path("@timestamp").asText()).isNotBlank();
        assertThat(node.path("log").path("level").asText()).isEqualTo("INFO");
        assertThat(node.path("service").path("name").asText()).isEqualTo("account-service");
        assertThat(node.path("message").asText()).isEqualTo(OUTCOME_MESSAGE);
        assertThat(node.path("outcome").asText()).isEqualTo("NEW");
        assertThat(node.path("traceId").asText()).isEqualTo(EXPECTED_TRACE_ID);

        assertThat(line)
                .doesNotContain(EVENT_ID)
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain(AMOUNT)
                .doesNotContain(requestBody)
                .doesNotContain(response.body());
    }

    @Test
    void applyTransaction_shouldLogNewAndReplayOnce_whenSameRequestIsRepeated(CapturedOutput output)
            throws Exception {
        String requestBody = requestBody(EVENT_ID, AMOUNT, "USD");

        HttpResponse<String> created = postTransaction(requestBody);
        HttpResponse<String> replayed = postTransaction(requestBody);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(outcomes(output.getAll())).containsExactly("NEW", "REPLAY");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void applyTransaction_shouldLogConflictOnce_whenEventIdHasDifferentAmount(CapturedOutput output)
            throws Exception {
        HttpResponse<String> created = postTransaction(requestBody(EVENT_ID, AMOUNT, "USD"));
        String conflictingBody = requestBody(EVENT_ID, "151.00", "USD");

        HttpResponse<String> conflict = postTransaction(conflictingBody);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(outcomes(output.getAll())).containsExactly("NEW", "CONFLICT");
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(outcomeLines(output.getAll()).get(1))
                .doesNotContain(EVENT_ID)
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain("151.00")
                .doesNotContain(conflictingBody)
                .doesNotContain(conflict.body());
    }

    @Test
    void applyTransaction_shouldLogRejectionOnce_whenCurrencyIsUnknown(CapturedOutput output)
            throws Exception {
        String rejectedBody = requestBody(EVENT_ID, AMOUNT, "ABC");

        HttpResponse<String> rejected = postTransaction(rejectedBody);

        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(outcomes(output.getAll())).containsExactly("REJECTED");
        assertThat(accounts.count()).isZero();
        assertThat(transactions.count()).isZero();
        assertThat(outcomeLines(output.getAll()).get(0))
                .doesNotContain(EVENT_ID)
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain(AMOUNT)
                .doesNotContain(rejectedBody)
                .doesNotContain(rejected.body());
    }

    private HttpResponse<String> postTransaction(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/accounts/" + ACCOUNT_ID + "/transactions"))
                .header("Content-Type", "application/json")
                .header("traceparent", INCOMING_TRACEPARENT)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String requestBody(String eventId, String amount, String currency) {
        return """
                {"eventId":"%s","type":"CREDIT","amount":%s,"currency":"%s","eventTimestamp":"%s"}"""
                .formatted(eventId, amount, currency, EVENT_TIME);
    }

    private List<String> outcomes(String captured) throws Exception {
        List<String> values = new ArrayList<>();
        for (String line : outcomeLines(captured)) {
            values.add(json.readTree(line).path("outcome").asText());
        }
        return values;
    }

    private List<String> outcomeLines(String captured) {
        return captured.lines()
                .filter(line -> line.contains(OUTCOME_MESSAGE))
                .filter(line -> line.trim().startsWith("{"))
                .toList();
    }
}
