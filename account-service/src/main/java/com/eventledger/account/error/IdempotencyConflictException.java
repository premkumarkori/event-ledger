package com.eventledger.account.error;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String eventId) {
        super("eventId " + eventId + " already belongs to a different transaction");
    }
}
