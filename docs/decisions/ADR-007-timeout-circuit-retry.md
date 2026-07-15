# ADR-007: Timeout and circuit breaker are core; retry is gated

- Status: Accepted (2026-07-14)
- Date: 2026-07-14
- Requirements: FR-041, FR-042, NFR-020 through NFR-022

## Context

A circuit breaker does not stop one slow request, and retry can multiply load or
duplicate effects. The project asks for resilience, but correctness and a
reproducible core take priority over optional retry.

## Decision

Core behavior includes:

- finite connect and response/read timeouts;
- a Resilience4j circuit breaker through Spring Cloud CircuitBreaker;
- explicit classification so expected `4xx` does not trip the circuit;
- one clear `503` mapping for timeout, connection, retryable `5xx`, and open circuit;
- no automatic HTTP retry in the core.

Automatic retry remains deferred. If admitted later, keep it to two total
attempts with exponential backoff/jitter, never retry validation `4xx`, semantic
`409`, `404`, or an open circuit in a loop, and prove request counts plus breaker
accounting before keeping it.

## Why

Timeout bounds latency. The circuit stops repeated calls to a failing dependency. Downstream database idempotency, not the retry library, makes a financial retry safe.

## Consequences

- The core can be complete without retry; the client can safely retry the same event ID.
- Small demo thresholds are configuration for visibility, not claimed production tuning.
- Resilience4j's default annotation nesting places Retry outside CircuitBreaker.
  This extension deliberately wants CircuitBreaker outside Retry so one client
  submission is accounted as one logical breaker call. Use explicit functional
  composition and verify the resulting order with request-count and breaker-state
  tests; do not assume annotation order.

## Rejected alternatives

- Retry first: risks duplicate effects before downstream protection is proven.
- Circuit breaker without client timeout: a call can still hang until the underlying default.
- Retry all exceptions: amplifies bad requests and conflicts.
