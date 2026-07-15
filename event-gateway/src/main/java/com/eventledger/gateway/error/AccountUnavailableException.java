package com.eventledger.gateway.error;

public class AccountUnavailableException extends RuntimeException {

    private final String eventId;

    public AccountUnavailableException(String eventId) {
        super("account application could not be confirmed");
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }
}
