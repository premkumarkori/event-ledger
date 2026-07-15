package com.eventledger.gateway.domain;

public enum LastFailureCode {
    RETRYABLE_UNCONFIRMED,
    TERMINAL_CONFLICT,
    CONTRACT_ERROR
}
