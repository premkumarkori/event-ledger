package com.eventledger.account.service;

import com.eventledger.account.api.AccountDetailsResponse;
import com.eventledger.account.api.BalanceResponse;
import com.eventledger.account.api.TransactionSummaryResponse;
import com.eventledger.account.error.AccountNotFoundException;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import com.eventledger.account.persistence.BalanceProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class AccountQueryService {

    private final AccountTransactionRepository transactions;
    private final Clock clock;

    public AccountQueryService(AccountTransactionRepository transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        BalanceProjection balance = requireKnownAccount(accountId);
        return new BalanceResponse(
                balance.accountId(), balance.currency(), balance.balance(), clock.instant());
    }

    @Transactional(readOnly = true)
    public AccountDetailsResponse getDetails(String accountId) {
        BalanceProjection balance = requireKnownAccount(accountId);
        List<TransactionSummaryResponse> recent = transactions
                .findTop20ByAccountIdOrderByEventTimestampDescEventIdDesc(accountId)
                .stream()
                .map(this::toTransactionSummary)
                .toList();
        return new AccountDetailsResponse(
                balance.accountId(), balance.currency(), balance.balance(), recent);
    }

    private BalanceProjection requireKnownAccount(String accountId) {
        return transactions.findBalanceByAccountId(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private TransactionSummaryResponse toTransactionSummary(
            AccountTransactionEntity transaction) {
        return new TransactionSummaryResponse(
                transaction.getEventId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getEventTimestamp());
    }
}
