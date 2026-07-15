# ADR-005: Immutable journal and derived balance

- Status: Accepted (2026-07-14)
- Date: 2026-07-14
- Requirements: FR-030, FR-031, FR-033

## Context

A mutable `balance` column updated by read-modify-write can lose one of two concurrent credits unless locking/versioning is designed and tested. It can also drift from transaction history.

## Decision

Account stores one immutable transaction row per event and derives current balance with exact SQL decimal aggregation:

```text
SUM(CREDIT) - SUM(DEBIT)
```

Use `BigDecimal` and `decimal(38,18)`. Do not silently round to two decimals. Debits may produce a negative balance because the assignment supplies no overdraft rule.

The small `accounts` table stores account identity and currency only. It does not store balance.

## Why

Insert-only transactions plus a unique event key make the correctness argument small: no duplicate row means no duplicate effect, and concurrent different inserts cannot overwrite one another.

## Consequences

- Query cost grows with history; acceptable for this scoped H2 implementation.
- A production design can add snapshots/materialized balance with locking and reconciliation while retaining the journal as truth.
- “Balance after this transaction” is not stored and must not be presented as a stable original replay field.

## Rejected alternatives

- Mutable balance as the only truth: susceptible to lost updates and audit drift.
- In-memory calculation from cached events: restart and multi-instance unsafe.
