package com.eventledger.gateway;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import com.eventledger.gateway.persistence.StoredEventMapper;
import com.eventledger.gateway.service.EventStatusWriter;
import com.eventledger.gateway.service.EventWriter;
import com.eventledger.gateway.support.MutableTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class GatewayEventRepositoryIT {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final Instant APPLIED_AT = Instant.parse("2026-07-15T09:00:00.250Z");
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11.123456789Z");

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(RECEIVED_AT);
        }
    }

    @Autowired
    private EventWriter eventWriter;

    @Autowired
    private EventStatusWriter statusWriter;

    @Autowired
    private EventRepository events;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void resetDatabaseAndClock() {
        events.deleteAll();
        clock.setInstant(RECEIVED_AT);
    }

    private NormalizedEvent event(String eventId, String amount, String metadata) {
        return new NormalizedEvent(
                eventId, "acct-1", EventType.CREDIT, new BigDecimal(amount), "USD", EVENT_TIME, metadata);
    }

    private StoredEventEntity stored(String eventId) {
        return events.findById(eventId).orElseThrow();
    }

    @Test
    void datasource_shouldUseIsolatedGatewayInMemoryDatabase_whenTestProfileActive() throws Exception {
        String url = jdbcUrl();
        assertThat(url).startsWith("jdbc:h2:mem:gateway-test-");
        assertThat(url).doesNotContain("runtime-data");
    }

    @Test
    void datasource_shouldDifferFromAccountDatabase_whenTestProfileActive() throws Exception {
        String url = jdbcUrl();
        assertThat(url).contains("gateway-test");
        assertThat(url).doesNotContain("account");
    }

    @Test
    void insert_shouldRejectDuplicateEventId_whenSameIdInsertedTwice() {
        eventWriter.insert(event("evt-dup", "10.00", "{}"));

        assertThatThrownBy(() -> eventWriter.insert(event("evt-dup", "10.00", "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void insert_shouldRoundTripHighPrecisionDecimal_whenAmountHasEighteenFractionDigits() {
        eventWriter.insert(event("evt-1", "123456789012345678.123456789012345678", "{}"));

        assertThat(stored("evt-1").getAmount())
                .isEqualByComparingTo("123456789012345678.123456789012345678");
    }

    @Test
    void insert_shouldRoundTripMetadataJson_whenMetadataExceedsLegacyVarcharSize() {
        String metadata = "{\"payload\":\"" + "x".repeat(5000) + "\"}";
        eventWriter.insert(event("evt-1", "10.00", metadata));

        assertThat(stored("evt-1").getMetadataJson()).isEqualTo(metadata);
    }

    @Test
    void insert_shouldStoreReceivedStateWithZeroVersion_whenFirstStored() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getApplicationStatus()).isEqualTo(ApplicationStatus.RECEIVED);
        assertThat(row.getVersion()).isZero();
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(row.getAppliedAt()).isNull();
        assertThat(row.getLastAttemptAt()).isNull();
        assertThat(row.getLastFailureCode()).isNull();
    }

    @Test
    void markApplied_shouldMoveReceivedToApplied_whenApplied() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));
        clock.setInstant(APPLIED_AT);

        statusWriter.markApplied("evt-1");

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(row.getAppliedAt()).isEqualTo(APPLIED_AT);
        assertThat(row.getLastAttemptAt()).isEqualTo(APPLIED_AT);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getVersion()).isEqualTo(1);
    }

    @Test
    void markApplied_shouldClearFailureFields_whenApplyingAfterFailure() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));
        statusWriter.markFailed("evt-1", LastFailureCode.RETRYABLE_UNCONFIRMED, "timeout");
        clock.setInstant(APPLIED_AT);

        statusWriter.markApplied("evt-1");

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(row.getLastFailureCode()).isNull();
        assertThat(row.getLastFailureMessage()).isNull();
        assertThat(row.getAttemptCount()).isEqualTo(2);
        assertThat(row.getVersion()).isEqualTo(2);
    }

    @Test
    void markFailed_shouldStoreBoundedCodeAndMessage_whenMessageExceedsColumnLength() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));
        String longMessage = "x".repeat(EventStatusWriter.MAX_FAILURE_MESSAGE_LENGTH + 50);

        statusWriter.markFailed("evt-1", LastFailureCode.CONTRACT_ERROR, longMessage);

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(row.getLastFailureCode()).isEqualTo(LastFailureCode.CONTRACT_ERROR);
        assertThat(row.getLastFailureMessage()).hasSize(EventStatusWriter.MAX_FAILURE_MESSAGE_LENGTH);
    }

    @Test
    void markFailed_shouldRejectMissingFailureCode_whenFailureIsRecorded() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));

        assertThatThrownBy(() -> statusWriter.markFailed("evt-1", null, "timeout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failure code is required");

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getApplicationStatus()).isEqualTo(ApplicationStatus.RECEIVED);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getVersion()).isZero();
    }

    @Test
    void markFailed_shouldNotDowngradeApplied_whenLateFailureArrives() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));
        clock.setInstant(APPLIED_AT);
        statusWriter.markApplied("evt-1");
        long versionAfterApply = stored("evt-1").getVersion();

        statusWriter.markFailed("evt-1", LastFailureCode.RETRYABLE_UNCONFIRMED, "late timeout");

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(row.getLastFailureCode()).isNull();
        assertThat(row.getVersion()).isEqualTo(versionAfterApply);
    }

    @Test
    void markFailed_shouldNotIncrementAttemptCountOrVersion_whenGuardBlocksAppliedRow() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));
        statusWriter.markApplied("evt-1");
        StoredEventEntity applied = stored("evt-1");
        int attemptsAfterApply = applied.getAttemptCount();
        long versionAfterApply = applied.getVersion();

        statusWriter.markFailed("evt-1", LastFailureCode.RETRYABLE_UNCONFIRMED, "ignored");

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getAttemptCount()).isEqualTo(attemptsAfterApply);
        assertThat(row.getVersion()).isEqualTo(versionAfterApply);
    }

    @Test
    void markFailed_shouldIncrementVersionExactlyOnce_whenFailingFromReceived() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));

        statusWriter.markFailed("evt-1", LastFailureCode.TERMINAL_CONFLICT, "conflict");

        StoredEventEntity row = stored("evt-1");
        assertThat(row.getApplicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void markApplied_shouldFailClearly_whenEventIdDoesNotExist() {
        assertThatThrownBy(() -> statusWriter.markApplied("evt-missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evt-missing");
    }

    @Test
    void schema_shouldExposeOrderedAccountTimeIndex_whenInspected() throws Exception {
        assertThat(indexColumns("IX_GATEWAY_EVENTS_ACCOUNT_TIME"))
                .containsExactly("ACCOUNT_ID", "EVENT_TIMESTAMP", "EVENT_ID");
    }

    @Test
    void entity_shouldMapVersionForOptimisticConcurrency_whenMetamodelInspected() {
        assertThat(entityManager.getMetamodel()
                .entity(StoredEventEntity.class)
                .getVersion(long.class)
                .getName())
                .isEqualTo("version");
    }

    @Test
    void schema_shouldNotContainAnyAccountTable_whenInspected() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_NAME IN ('ACCOUNTS', 'ACCOUNT_TRANSACTIONS')")) {
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isZero();
            }
        }
    }

    @Test
    void insert_shouldReturnCompleteDomainStoredEvent_whenFirstStored() {
        StoredEvent domain = eventWriter.insert(
                event("evt-1", "150.00", "{\"k\":\"v\"}"));

        assertThat(domain).isInstanceOf(StoredEvent.class);
        assertThat(domain.eventId()).isEqualTo("evt-1");
        assertThat(domain.applicationStatus()).isEqualTo(ApplicationStatus.RECEIVED);
        assertThat(domain.amount()).isEqualByComparingTo("150.00");
        assertThat(domain.metadata()).isEqualTo("{\"k\":\"v\"}");
        assertThat(domain.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(domain.attemptCount()).isZero();
        assertThat(domain.lastFailureCode()).isNull();
        assertThat(domain.version()).isZero();
    }

    @Test
    void mapper_shouldIncludeFailureDiagnostics_whenEventIsApplyFailed() {
        eventWriter.insert(event("evt-1", "150.00", "{}"));
        statusWriter.markFailed(
                "evt-1", LastFailureCode.RETRYABLE_UNCONFIRMED, "account timeout");

        StoredEvent domain = StoredEventMapper.toStoredEvent(stored("evt-1"));

        assertThat(domain.applicationStatus()).isEqualTo(ApplicationStatus.APPLY_FAILED);
        assertThat(domain.lastAttemptAt()).isEqualTo(RECEIVED_AT);
        assertThat(domain.attemptCount()).isEqualTo(1);
        assertThat(domain.lastFailureCode()).isEqualTo(LastFailureCode.RETRYABLE_UNCONFIRMED);
        assertThat(domain.lastFailureMessage()).isEqualTo("account timeout");
        assertThat(domain.version()).isEqualTo(1);
    }

    private String jdbcUrl() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }

    private List<String> indexColumns(String indexName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                             + "WHERE TABLE_NAME = 'GATEWAY_EVENTS' AND INDEX_NAME = ? "
                             + "ORDER BY ORDINAL_POSITION")) {
            statement.setString(1, indexName);
            try (ResultSet row = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (row.next()) {
                    columns.add(row.getString("COLUMN_NAME"));
                }
                return columns;
            }
        }
    }
}
