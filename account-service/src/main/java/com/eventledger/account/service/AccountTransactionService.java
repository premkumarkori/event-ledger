package com.eventledger.account.service;

import com.eventledger.account.api.ApplyTransactionRequest;
import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.error.AccountRaceDidNotConvergeException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AccountTransactionService {

    private final AccountTransactionRequestValidator requestValidator;
    private final AccountTransactionWriter writer;
    private final AccountCollisionResolver collisionResolver;

    public AccountTransactionService(
            AccountTransactionRequestValidator requestValidator,
            AccountTransactionWriter writer,
            AccountCollisionResolver collisionResolver) {
        this.requestValidator = requestValidator;
        this.writer = writer;
        this.collisionResolver = collisionResolver;
    }

    public ApplyOutcome applyTransaction(String accountId, ApplyTransactionRequest request) {
        TransactionData transaction = requestValidator.validateAndNormalize(accountId, request);
        return applyOrRecover(transaction);
    }

    private ApplyOutcome applyOrRecover(TransactionData transaction) {
        try {
            return writer.applyOnce(transaction);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException firstFailure) {
            return recoverAfterFailedInsert(transaction);
        }
    }

    private ApplyOutcome recoverAfterFailedInsert(TransactionData transaction) {
        Optional<ApplyOutcome> resolved =
                collisionResolver.resolveAfterFailedInsert(transaction);
        return resolved.orElseGet(() -> retryOnce(transaction));
    }

    private ApplyOutcome retryOnce(TransactionData transaction) {
        try {
            return writer.applyOnce(transaction);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException secondFailure) {
            throw new AccountRaceDidNotConvergeException(secondFailure);
        }
    }
}
