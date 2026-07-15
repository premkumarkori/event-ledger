package com.eventledger.account.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionData(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {
}
