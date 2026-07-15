package com.eventledger.account.api;

import com.eventledger.account.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionSummaryResponse(
        String eventId,
        TransactionType type,
        BigDecimal amount,
        Instant eventTimestamp
) {
}
