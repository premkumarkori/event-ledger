package com.eventledger.gateway.error;

public class AccountQueryUnavailableException extends RuntimeException {

    public AccountQueryUnavailableException() {
        super("the Account Service balance query could not be completed");
    }
}
