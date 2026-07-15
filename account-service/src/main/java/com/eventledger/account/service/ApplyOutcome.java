package com.eventledger.account.service;

import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.persistence.AccountTransactionEntity;

import java.time.Instant;

public record ApplyOutcome(
        TransactionData transaction,
        Instant appliedAt,
        boolean newlyApplied) {

    static ApplyOutcome createdFrom(AccountTransactionEntity transaction) {
        return from(transaction, true);
    }

    static ApplyOutcome replayedFrom(AccountTransactionEntity transaction) {
        return from(transaction, false);
    }

    private static ApplyOutcome from(AccountTransactionEntity transaction, boolean newlyApplied) {
        TransactionData data = new TransactionData(
                transaction.getEventId(),
                transaction.getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getEventTimestamp());
        return new ApplyOutcome(data, transaction.getAppliedAt(), newlyApplied);
    }
}
