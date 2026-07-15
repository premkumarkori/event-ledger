package com.eventledger.gateway.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record NormalizedEvent(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        String metadata) {
}
