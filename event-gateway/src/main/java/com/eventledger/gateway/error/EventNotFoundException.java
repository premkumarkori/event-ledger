package com.eventledger.gateway.error;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException() {
        super("no stored event for the requested identifier");
    }
}
