package com.eventledger.account.api;

import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.service.ApplyOutcome;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant appliedAt
) {

    public static TransactionResponse from(ApplyOutcome outcome) {
        return new TransactionResponse(
                outcome.transaction().eventId(),
                outcome.transaction().accountId(),
                outcome.transaction().type(),
                outcome.transaction().amount(),
                outcome.transaction().currency(),
                outcome.transaction().eventTimestamp(),
                outcome.appliedAt());
    }
}
