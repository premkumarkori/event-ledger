package com.eventledger.account.service;

import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.error.CurrencyConflictException;
import com.eventledger.account.error.IdempotencyConflictException;
import com.eventledger.account.persistence.AccountEntity;
import com.eventledger.account.persistence.AccountRepository;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Component
public class AccountTransactionWriter {

    private final AccountRepository accounts;
    private final AccountTransactionRepository transactions;
    private final Clock clock;

    public AccountTransactionWriter(
            AccountRepository accounts,
            AccountTransactionRepository transactions,
            Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplyOutcome applyOnce(TransactionData transaction) {
        Optional<AccountTransactionEntity> existing =
                transactions.findById(transaction.eventId());
        if (existing.isPresent()) {
            return replayOrThrowConflict(existing.get(), transaction);
        }

        Instant appliedAt = clock.instant();
        createAccountOrCheckCurrency(transaction, appliedAt);
        AccountTransactionEntity saved = transactions.saveAndFlush(
                new AccountTransactionEntity(transaction, appliedAt));
        return ApplyOutcome.createdFrom(saved);
    }

    private ApplyOutcome replayOrThrowConflict(
            AccountTransactionEntity existing, TransactionData incoming) {
        if (TransactionEquality.hasSameBusinessValues(existing, incoming)) {
            return ApplyOutcome.replayedFrom(existing);
        }
        throw new IdempotencyConflictException(incoming.eventId());
    }

    private void createAccountOrCheckCurrency(
            TransactionData transaction, Instant createdAt) {
        Optional<AccountEntity> existing = accounts.findById(transaction.accountId());
        if (existing.isPresent()) {
            throwIfCurrencyConflicts(existing.get(), transaction);
            return;
        }

        accounts.save(new AccountEntity(
                transaction.accountId(), transaction.currency(), createdAt));
    }

    private void throwIfCurrencyConflicts(
            AccountEntity account, TransactionData transaction) {
        if (!account.getCurrency().equals(transaction.currency())) {
            throw new CurrencyConflictException(
                    transaction.accountId(), account.getCurrency(), transaction.currency());
        }
    }
}
