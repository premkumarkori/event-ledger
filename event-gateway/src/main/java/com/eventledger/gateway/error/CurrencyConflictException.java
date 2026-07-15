package com.eventledger.gateway.error;

public class CurrencyConflictException extends RuntimeException {

    public CurrencyConflictException(String eventId) {
        super("eventId " + eventId + " conflicts with the established account currency");
    }
}
