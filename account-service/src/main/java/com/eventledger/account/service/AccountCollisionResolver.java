package com.eventledger.account.service;

import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.error.AccountRaceDidNotConvergeException;
import com.eventledger.account.error.CurrencyConflictException;
import com.eventledger.account.error.IdempotencyConflictException;
import com.eventledger.account.persistence.AccountEntity;
import com.eventledger.account.persistence.AccountRepository;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class AccountCollisionResolver {

    private final AccountRepository accounts;
    private final AccountTransactionRepository transactions;

    public AccountCollisionResolver(
            AccountRepository accounts,
            AccountTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ApplyOutcome> resolveAfterFailedInsert(TransactionData transaction) {
        Optional<AccountTransactionEntity> existing =
                transactions.findById(transaction.eventId());
        if (existing.isPresent()) {
            return Optional.of(replayOrThrowConflict(existing.get(), transaction));
        }

        AccountEntity account = accounts.findById(transaction.accountId())
                .orElseThrow(AccountRaceDidNotConvergeException::new);
        throwIfCurrencyConflicts(account, transaction);
        return Optional.empty();
    }

    private ApplyOutcome replayOrThrowConflict(
            AccountTransactionEntity existing, TransactionData incoming) {
        if (TransactionEquality.hasSameBusinessValues(existing, incoming)) {
            return ApplyOutcome.replayedFrom(existing);
        }
        throw new IdempotencyConflictException(incoming.eventId());
    }

    private void throwIfCurrencyConflicts(
            AccountEntity account, TransactionData transaction) {
        if (!account.getCurrency().equals(transaction.currency())) {
            throw new CurrencyConflictException(
                    transaction.accountId(), account.getCurrency(), transaction.currency());
        }
    }
}
