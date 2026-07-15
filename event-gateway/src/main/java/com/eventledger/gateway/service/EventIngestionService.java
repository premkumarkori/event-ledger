package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountApplyOutcome;
import com.eventledger.gateway.client.AccountClientRequest;
import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.error.AccountUnavailableException;
import com.eventledger.gateway.error.CurrencyConflictException;
import com.eventledger.gateway.error.DownstreamContractException;
import com.eventledger.gateway.error.IdempotencyConflictException;
import com.eventledger.gateway.metrics.EventOutcome;
import com.eventledger.gateway.metrics.EventOutcomeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);
    private static final String OUTCOME_MESSAGE = "Event ingestion completed";
    private static final String IDEMPOTENCY_TOKEN = "idempotency-conflict";
    private static final String CURRENCY_TOKEN = "currency-conflict";

    private final EventWriter eventWriter;
    private final EventReader eventReader;
    private final EventStatusWriter statusWriter;
    private final EventEquality equality;
    private final AccountCallExecutor accountCallExecutor;
    private final EventOutcomeMetrics outcomeMetrics;

    public EventIngestionService(EventWriter eventWriter,
                                 EventReader eventReader,
                                 EventStatusWriter statusWriter,
                                 EventEquality equality,
                                 AccountCallExecutor accountCallExecutor,
                                 EventOutcomeMetrics outcomeMetrics) {
        this.eventWriter = eventWriter;
        this.eventReader = eventReader;
        this.statusWriter = statusWriter;
        this.equality = equality;
        this.accountCallExecutor = accountCallExecutor;
        this.outcomeMetrics = outcomeMetrics;
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
            outcomeMetrics.increment(EventOutcome.CONFLICT);
            logOutcome("conflict", existing.applicationStatus());
            throw new IdempotencyConflictException(event.eventId());
        }
        return switch (recoveryDecision(existing)) {
            case REPLAY_APPLIED -> {
                outcomeMetrics.increment(EventOutcome.REPLAYED);
                logOutcome("replayed", existing.applicationStatus());
                yield new EventSubmissionResult(existing, false);
            }
            case RECOVER -> applyThroughAccount(event, false);
            case RETAINED_TERMINAL_CONFLICT -> {
                outcomeMetrics.increment(EventOutcome.CONFLICT);
                logStoredFailure("conflict", existing);
                throw retainedConflict(existing);
            }
            case RETAINED_CONTRACT_ERROR -> {
                outcomeMetrics.increment(EventOutcome.APPLY_FAILED);
                logStoredFailure("apply_failed", existing);
                throw new DownstreamContractException();
            }
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
                outcomeMetrics.increment(EventOutcome.CONFLICT);
                logFailure("conflict", LastFailureCode.TERMINAL_CONFLICT);
                throw conflictFor(outcome.conflictType(), event.eventId());
            }
            case CONTRACT_ERROR -> {
                statusWriter.markFailed(event.eventId(),
                        LastFailureCode.CONTRACT_ERROR, "downstream contract error");
                recordApplyFailedIfDurable(event.eventId(), LastFailureCode.CONTRACT_ERROR);
                throw new DownstreamContractException();
            }
            case RETRYABLE_UNCONFIRMED -> {
                statusWriter.markFailed(event.eventId(),
                        LastFailureCode.RETRYABLE_UNCONFIRMED, "outcome not confirmed");
                recordApplyFailedIfDurable(event.eventId(), LastFailureCode.RETRYABLE_UNCONFIRMED);
                throw new AccountUnavailableException(event.eventId());
            }
        };
    }

    private void recordApplyFailedIfDurable(String eventId, LastFailureCode failureCode) {
        StoredEvent stored = eventReader.find(eventId).orElseThrow(() ->
                new IllegalStateException("failed event vanished before reload"));
        logFailure("apply_failed", failureCode);
        if (stored.applicationStatus() == ApplicationStatus.APPLY_FAILED) {
            outcomeMetrics.increment(EventOutcome.APPLY_FAILED);
        }
    }

    private EventSubmissionResult confirm(String eventId, boolean created) {
        statusWriter.markApplied(eventId);
        StoredEvent applied = eventReader.find(eventId).orElseThrow(() ->
                new IllegalStateException("applied event vanished before reload"));
        outcomeMetrics.increment(created ? EventOutcome.CREATED : EventOutcome.REPLAYED);
        logOutcome(created ? "created" : "replayed", applied.applicationStatus());
        return new EventSubmissionResult(applied, created);
    }

    private void logOutcome(String outcome, ApplicationStatus status) {
        outcomeLog(outcome)
                .addKeyValue("applicationStatus", status.name())
                .log(OUTCOME_MESSAGE);
    }

    private void logFailure(String outcome, LastFailureCode failureCode) {
        outcomeLog(outcome)
                .addKeyValue("failureCode", failureCode.name())
                .log(OUTCOME_MESSAGE);
    }

    private void logStoredFailure(String outcome, StoredEvent event) {
        LoggingEventBuilder entry = outcomeLog(outcome)
                .addKeyValue("applicationStatus", event.applicationStatus().name());
        if (event.lastFailureCode() != null) {
            entry = entry.addKeyValue("failureCode", event.lastFailureCode().name());
        }
        entry.log(OUTCOME_MESSAGE);
    }

    private LoggingEventBuilder outcomeLog(String outcome) {
        return log.atInfo().addKeyValue("outcome", outcome);
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
