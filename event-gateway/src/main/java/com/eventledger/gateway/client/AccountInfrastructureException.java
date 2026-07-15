package com.eventledger.gateway.client;

public class AccountInfrastructureException extends RuntimeException {

    public AccountInfrastructureException() {
        super("account infrastructure call failed");
    }

    public AccountInfrastructureException(Throwable cause) {
        super("account infrastructure call failed", cause);
    }
}
