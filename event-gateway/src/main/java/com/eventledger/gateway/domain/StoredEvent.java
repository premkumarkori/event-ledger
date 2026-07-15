package com.eventledger.gateway.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record StoredEvent(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        String metadata,
        ApplicationStatus applicationStatus,
        Instant receivedAt,
        Instant lastAttemptAt,
        Instant appliedAt,
        int attemptCount,
        LastFailureCode lastFailureCode,
        String lastFailureMessage,
        long version) {
}
