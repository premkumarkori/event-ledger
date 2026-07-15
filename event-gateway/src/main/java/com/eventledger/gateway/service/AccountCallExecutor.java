package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountApplyOutcome;
import com.eventledger.gateway.client.AccountBalanceResponse;
import com.eventledger.gateway.client.AccountClient;
import com.eventledger.gateway.client.AccountClientRequest;
import com.eventledger.gateway.client.AccountInfrastructureException;
import com.eventledger.gateway.error.AccountQueryUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

@Component
public class AccountCallExecutor {

    private static final String ACCOUNT_SERVICE_CIRCUIT = "accountService";

    private final AccountClient accountClient;
    private final CircuitBreaker accountCircuit;

    public AccountCallExecutor(AccountClient accountClient,
                               CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.accountClient = accountClient;
        this.accountCircuit = circuitBreakerFactory.create(ACCOUNT_SERVICE_CIRCUIT);
    }

    public AccountApplyOutcome apply(String accountId, AccountClientRequest request) {
        return accountCircuit.run(
                () -> accountClient.apply(accountId, request),
                this::mapApplyFailure);
    }

    public AccountBalanceResponse getBalance(String accountId) {
        return accountCircuit.run(
                () -> accountClient.getBalance(accountId),
                this::mapBalanceFailure);
    }

    private AccountApplyOutcome mapApplyFailure(Throwable failure) {
        if (isInfrastructureOrOpenCircuit(failure)) {
            return AccountApplyOutcome.retryableUnconfirmed();
        }
        throw propagate(failure);
    }

    private AccountBalanceResponse mapBalanceFailure(Throwable failure) {
        if (isInfrastructureOrOpenCircuit(failure)) {
            throw new AccountQueryUnavailableException();
        }
        throw propagate(failure);
    }

    private boolean isInfrastructureOrOpenCircuit(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AccountInfrastructureException
                    || current instanceof CallNotPermittedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Account circuit failed with a checked exception", failure);
    }
}
