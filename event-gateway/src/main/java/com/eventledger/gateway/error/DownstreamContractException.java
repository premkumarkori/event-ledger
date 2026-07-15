package com.eventledger.gateway.error;

public class DownstreamContractException extends RuntimeException {

    public DownstreamContractException() {
        super("the Account Service response did not satisfy the internal contract");
    }
}
