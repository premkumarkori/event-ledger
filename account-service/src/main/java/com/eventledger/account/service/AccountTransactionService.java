package com.eventledger.account.service;

import com.eventledger.account.api.ApplyTransactionRequest;
import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.error.AccountRaceDidNotConvergeException;
import com.eventledger.account.error.AccountRequestValidationException;
import com.eventledger.account.error.CurrencyConflictException;
import com.eventledger.account.error.IdempotencyConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AccountTransactionService {

    private static final Logger log = LoggerFactory.getLogger(AccountTransactionService.class);
    private static final String OUTCOME_MESSAGE = "Account transaction completed";

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
        try {
            TransactionData transaction = requestValidator.validateAndNormalize(accountId, request);
            ApplyOutcome outcome = applyOrRecover(transaction);
            logOutcome(outcome.newlyApplied() ? "NEW" : "REPLAY");
            return outcome;
        } catch (AccountRequestValidationException rejection) {
            logOutcome("REJECTED");
            throw rejection;
        } catch (AccountRaceDidNotConvergeException failure) {
            logOutcome("FAILED");
            throw failure;
        } catch (IdempotencyConflictException | CurrencyConflictException conflict) {
            logOutcome("CONFLICT");
            throw conflict;
        }
    }

    private void logOutcome(String outcome) {
        log.atInfo()
                .addKeyValue("outcome", outcome)
                .log(OUTCOME_MESSAGE);
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
