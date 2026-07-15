package com.eventledger.gateway;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.service.EventEquality;
import com.eventledger.gateway.service.EventNormalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventSemanticEqualityTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11.123456789Z");

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private final EventEquality equality = new EventEquality(jsonMapper);
    private final EventNormalizer normalizer = new EventNormalizer(jsonMapper);

    private StoredEvent stored(String accountId, EventType type, String amount,
                               String currency, Instant eventTime, String metadata) {
        return new StoredEvent("evt-1", accountId, type, new BigDecimal(amount), currency, eventTime, metadata,
                ApplicationStatus.APPLIED, EVENT_TIME, EVENT_TIME, EVENT_TIME, 1, null, null, 1);
    }

    private NormalizedEvent normalized(String accountId, EventType type, String amount,
                                       String currency, Instant eventTime, String metadata) {
        return new NormalizedEvent(
                "evt-1", accountId, type, new BigDecimal(amount), currency, eventTime, metadata);
    }

    @Test
    void sameBusinessInstruction_shouldMatch_whenTopLevelAmountScaleDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.0", "USD", EVENT_TIME, "{}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isTrue();
    }

    @Test
    void sameBusinessInstruction_shouldMatch_whenCurrencyNormalizedFromLowercase() {
        NormalizedEvent incoming = normalizer.normalize(new EventRequest(
                "evt-1", "acct-1", EventType.CREDIT, new BigDecimal("150.00"), "usd",
                EVENT_TIME, jsonMapper.readTree("{}")));
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");

        assertThat(incoming.currency()).isEqualTo("USD");
        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isTrue();
    }

    @Test
    void sameBusinessInstruction_shouldMatch_whenMetadataObjectKeysReordered() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"a\":1,\"b\":2}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"b\":2,\"a\":1}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isTrue();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenMetadataNumericRepresentationDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"ratio\":1}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"ratio\":1.0}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenMetadataArrayReordered() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"tags\":[1,2]}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"tags\":[2,1]}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenAccountIdDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");
        NormalizedEvent incoming = normalized("acct-2", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenTypeDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");
        NormalizedEvent incoming = normalized("acct-1", EventType.DEBIT, "150.00", "USD", EVENT_TIME, "{}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenAmountDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.01", "USD", EVENT_TIME, "{}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenCurrencyDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.00", "EUR", EVENT_TIME, "{}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenEventTimestampDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME, "{}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.00", "USD",
                EVENT_TIME.plusNanos(1), "{}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }

    @Test
    void sameBusinessInstruction_shouldConflict_whenMetadataValueDiffers() {
        StoredEvent storedEvent = stored("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"source\":\"batch\"}");
        NormalizedEvent incoming = normalized("acct-1", EventType.CREDIT, "150.00", "USD", EVENT_TIME,
                "{\"source\":\"stream\"}");

        assertThat(equality.sameBusinessInstruction(storedEvent, incoming)).isFalse();
    }
}
