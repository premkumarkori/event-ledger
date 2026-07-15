package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountApplyOutcome;
import com.eventledger.gateway.client.AccountClientRequest;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.error.AccountUnavailableException;
import com.eventledger.gateway.error.CurrencyConflictException;
import com.eventledger.gateway.error.DownstreamContractException;
import com.eventledger.gateway.error.IdempotencyConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {

    private static final String IDEMPOTENCY_TOKEN = "idempotency-conflict";
    private static final String CURRENCY_TOKEN = "currency-conflict";

    private final EventWriter eventWriter;
    private final EventReader eventReader;
    private final EventStatusWriter statusWriter;
    private final EventEquality equality;
    private final AccountCallExecutor accountCallExecutor;

    public EventIngestionService(EventWriter eventWriter,
                                 EventReader eventReader,
                                 EventStatusWriter statusWriter,
                                 EventEquality equality,
                                 AccountCallExecutor accountCallExecutor) {
        this.eventWriter = eventWriter;
        this.eventReader = eventReader;
        this.statusWriter = statusWriter;
        this.equality = equality;
        this.accountCallExecutor = accountCallExecutor;
    }

    public EventSubmissionResult submit(NormalizedEvent event) {
        try {
            eventWriter.insert(event);
        } catch (DataIntegrityViolationException collision) {
            return resolveExistingEvent(event);
        }
        return applyThroughAccount(event, true);
    }

    private EventSubmissionResult resolveExistingEvent(NormalizedEvent event) {
        StoredEvent existing = eventReader.find(event.eventId()).orElseThrow(() ->
                new IllegalStateException("insert collision without a stored event"));
        if (!equality.sameBusinessInstruction(existing, event)) {
            throw new IdempotencyConflictException(event.eventId());
        }
        return switch (recoveryDecision(existing)) {
            case REPLAY_APPLIED -> new EventSubmissionResult(existing, false);
            case RECOVER -> applyThroughAccount(event, false);
            case RETAINED_TERMINAL_CONFLICT -> throw retainedConflict(existing);
            case RETAINED_CONTRACT_ERROR -> throw new DownstreamContractException();
        };
    }

    private RecoveryDecision recoveryDecision(StoredEvent existing) {
        return switch (existing.applicationStatus()) {
            case APPLIED -> RecoveryDecision.REPLAY_APPLIED;
            case RECEIVED -> RecoveryDecision.RECOVER;
            case APPLY_FAILED -> retainedFailureDecision(existing.lastFailureCode());
        };
    }

    private RecoveryDecision retainedFailureDecision(LastFailureCode code) {
        if (code == null) {
            return RecoveryDecision.RETAINED_CONTRACT_ERROR;
        }
        return switch (code) {
            case RETRYABLE_UNCONFIRMED -> RecoveryDecision.RECOVER;
            case TERMINAL_CONFLICT -> RecoveryDecision.RETAINED_TERMINAL_CONFLICT;
            case CONTRACT_ERROR -> RecoveryDecision.RETAINED_CONTRACT_ERROR;
        };
    }

    private EventSubmissionResult applyThroughAccount(NormalizedEvent event, boolean created) {
        AccountApplyOutcome outcome = accountCallExecutor.apply(event.accountId(), toAccountRequest(event));
        return switch (outcome.kind()) {
            case CONFIRMED -> confirm(event.eventId(), created);
            case TERMINAL_CONFLICT -> {
                statusWriter.markFailed(event.eventId(),
                        LastFailureCode.TERMINAL_CONFLICT, conflictToken(outcome));
                throw conflictFor(outcome.conflictType(), event.eventId());
            }
            case CONTRACT_ERROR -> {
                statusWriter.markFailed(event.eventId(),
                        LastFailureCode.CONTRACT_ERROR, "downstream contract error");
                throw new DownstreamContractException();
            }
            case RETRYABLE_UNCONFIRMED -> {
                statusWriter.markFailed(event.eventId(),
                        LastFailureCode.RETRYABLE_UNCONFIRMED, "outcome not confirmed");
                throw new AccountUnavailableException(event.eventId());
            }
        };
    }

    private EventSubmissionResult confirm(String eventId, boolean created) {
        statusWriter.markApplied(eventId);
        StoredEvent applied = eventReader.find(eventId).orElseThrow(() ->
                new IllegalStateException("applied event vanished before reload"));
        return new EventSubmissionResult(applied, created);
    }

    private AccountClientRequest toAccountRequest(NormalizedEvent event) {
        return new AccountClientRequest(
                event.eventId(), event.type(), event.amount(), event.currency(), event.eventTimestamp());
    }

    private String conflictToken(AccountApplyOutcome outcome) {
        return switch (outcome.conflictType()) {
            case IDEMPOTENCY -> IDEMPOTENCY_TOKEN;
            case CURRENCY -> CURRENCY_TOKEN;
        };
    }

    private RuntimeException conflictFor(AccountApplyOutcome.ConflictType conflictType, String eventId) {
        return switch (conflictType) {
            case IDEMPOTENCY -> new IdempotencyConflictException(eventId);
            case CURRENCY -> new CurrencyConflictException(eventId);
        };
    }

    private RuntimeException retainedConflict(StoredEvent existing) {
        if (CURRENCY_TOKEN.equals(existing.lastFailureMessage())) {
            return new CurrencyConflictException(existing.eventId());
        }
        if (IDEMPOTENCY_TOKEN.equals(existing.lastFailureMessage())) {
            return new IdempotencyConflictException(existing.eventId());
        }
        return new DownstreamContractException();
    }

    private enum RecoveryDecision {
        REPLAY_APPLIED,
        RECOVER,
        RETAINED_TERMINAL_CONFLICT,
        RETAINED_CONTRACT_ERROR
    }
}
