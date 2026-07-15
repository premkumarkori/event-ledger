package com.eventledger.gateway.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GatewayHealthService {

    private static final String ACCOUNT_SERVICE_CIRCUIT = "accountService";
    private static final String SERVICE_NAME = "event-gateway";

    private final LocalDatabaseCheck databaseCheck;
    private final AccountDependencyState dependencyState;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public GatewayHealthService(LocalDatabaseCheck databaseCheck,
                                AccountDependencyState dependencyState,
                                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.databaseCheck = databaseCheck;
        this.dependencyState = dependencyState;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public GatewayHealthResponse currentHealth() {
        boolean databaseUp = databaseCheck.isUp();
        AccountDependencyState.Observation accountObservation = dependencyState.snapshot().observation();
        CircuitBreaker.State circuitState = accountCircuitState();

        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("database", databaseUp ? "UP" : "DOWN");
        checks.put("accountService", publicAccountState(accountObservation));
        checks.put("circuitBreaker", publicCircuitState(circuitState));

        if (!databaseUp) {
            return new GatewayHealthResponse("DOWN", SERVICE_NAME, checks);
        }
        return new GatewayHealthResponse(
                isDegraded(accountObservation, circuitState) ? "DEGRADED" : "UP",
                SERVICE_NAME,
                checks);
    }

    private String publicAccountState(AccountDependencyState.Observation observation) {
        return switch (observation) {
            case AVAILABLE -> "UP";
            case UNAVAILABLE -> "UNAVAILABLE";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private CircuitBreaker.State accountCircuitState() {
        return circuitBreakerRegistry
                .circuitBreaker(ACCOUNT_SERVICE_CIRCUIT)
                .getState();
    }

    private boolean isDegraded(AccountDependencyState.Observation accountObservation,
                               CircuitBreaker.State circuitState) {
        return accountObservation == AccountDependencyState.Observation.UNAVAILABLE
                || circuitState == CircuitBreaker.State.OPEN
                || circuitState == CircuitBreaker.State.FORCED_OPEN;
    }

    private String publicCircuitState(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> "CLOSED";
            case OPEN, FORCED_OPEN -> "OPEN";
            case HALF_OPEN -> "HALF_OPEN";
            case DISABLED, METRICS_ONLY -> "UNKNOWN";
        };
    }
}
