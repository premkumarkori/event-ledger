package com.eventledger.gateway.error;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("the Account Service has no account for the requested identifier");
    }
}
