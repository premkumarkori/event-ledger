package com.eventledger.gateway.support;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

public final class AccountCircuitTestSupport {

    private static final String ACCOUNT_SERVICE_CIRCUIT = "accountService";

    private AccountCircuitTestSupport() {
    }

    public static void resetAccountCircuit(CircuitBreakerRegistry circuitBreakerRegistry) {
        circuitBreakerRegistry.circuitBreaker(ACCOUNT_SERVICE_CIRCUIT).reset();
    }
}
