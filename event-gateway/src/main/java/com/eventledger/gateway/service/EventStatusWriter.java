package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class EventStatusWriter {

    public static final int MAX_FAILURE_MESSAGE_LENGTH = 300;

    private final EventRepository events;
    private final Clock clock;

    public EventStatusWriter(EventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markApplied(String eventId) {
        int updated = events.markApplied(eventId, clock.instant());
        if (updated == 0) {
            confirmTerminalApplied(eventId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String eventId, LastFailureCode code, String message) {
        LastFailureCode requiredCode = requireFailureCode(code);
        int updated = events.markFailed(
                eventId, requiredCode, bounded(message), clock.instant());
        if (updated == 0) {
            confirmTerminalApplied(eventId);
        }
    }

    private void confirmTerminalApplied(String eventId) {
        StoredEventEntity current = events.findById(eventId).orElseThrow(() ->
                new IllegalStateException("status update for unknown eventId " + eventId));
        if (current.getApplicationStatus() != ApplicationStatus.APPLIED) {
            throw new IllegalStateException(
                    "guarded status update changed nothing for eventId " + eventId
                            + " in state " + current.getApplicationStatus());
        }
    }

    private String bounded(String message) {
        if (message == null || message.length() <= MAX_FAILURE_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }

    private LastFailureCode requireFailureCode(LastFailureCode code) {
        if (code == null) {
            throw new IllegalArgumentException("failure code is required");
        }
        return code;
    }
}
