# ADR-003: End-to-end, database-backed idempotency

- Status: Accepted (2026-07-14)
- Date: 2026-07-14
- Requirements: FR-020 through FR-024

## Context

A Gateway duplicate check cannot protect the Account Service from a timeout followed by retry, concurrent Gateway requests, or a future direct/internal caller. A check-then-insert performed only in Java also races.

## Decision

Use `eventId` as the idempotency key in both services and make it a primary/unique database key in both tables.

For the same ID:

- the same normalized semantic payload is a replay;
- a different payload is `409 Conflict`;
- an applied Gateway replay does not call Account;
- a `RECEIVED` or `APPLY_FAILED/RETRYABLE_UNCONFIRMED` Gateway replay calls
  Account again; terminal-conflict and contract-error failures return their
  retained error without a blind Account call;
- Account always independently inserts or loads and compares.

Normalize/compare using `BigDecimal.compareTo`, uppercase currency, instant
equality, and ordinary `JsonNode.equals` for Gateway metadata after null-to-`{}`
normalization. Account compares every field in its smaller internal contract.

Unique-constraint recovery must read in a fresh transaction. Do not catch an insert exception and query in the same rollback-only transaction.

## Why

This makes retry safe even when Account committed but Gateway never saw the response. The database constraint is the arbiter during concurrency.

## Consequences

- Two concurrent requests may both reach Account, but only one financial row/effect is possible.
- Tests must assert row count and final balance, not just HTTP status.
- Changing metadata at Gateway conflicts even though Account does not receive metadata; Gateway owns the full event identity.

## Rejected alternatives

- In-memory cache: lost at restart and unsafe across instances.
- Gateway-only uniqueness: cannot cover unknown remote outcomes.
- Blindly returning the original response without comparing payload: allows an ID to hide different financial intent.
