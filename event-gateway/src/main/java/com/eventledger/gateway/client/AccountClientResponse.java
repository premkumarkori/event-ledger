package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountClientResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant appliedAt
) {
}
