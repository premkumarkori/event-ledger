# ADR-006: One currency per account in the core

- Status: Proposed
- Date: 2026-07-14
- Requirements: FR-032

## Context

The assignment includes currency but asks for a scalar account balance. Adding USD and EUR is invalid without conversion rules, and no exchange-rate source or base currency is provided.

## Decision

The first accepted transaction establishes an account's uppercase three-letter
currency recognized by Java 17 `Currency.getInstance`. Later transactions for
that account must use the same currency or receive `409 Conflict` without
insertion.

Enforce first-write concurrency with `accounts(account_id primary key, currency, created_at)` inside the same Account transaction that inserts the financial row. Do not rely on a “find current currency, then insert” Java pre-check alone.

On an account/event unique race, abandon the failed transaction and resolve in a fresh one: existing event wins replay/conflict resolution; otherwise a different stored account currency is `409`; otherwise retry the local apply once. This keeps account creation and the first financial row atomic and prevents an empty account from being left by a crash between two commits.

## Why

This is the smallest deterministic rule that makes the requested scalar balance meaningful and avoids inventing foreign-exchange behavior.

## Consequences

- Account details and balance always have one currency.
- Concurrent first transactions in different currencies result in one established currency and one conflict.
- A production multi-currency design would return balances per currency or use an explicit valuation service and timestamped rates.

## Rejected alternatives

- Ignore currency in the sum: financially incorrect.
- Convert implicitly: no source, spread, rounding, or effective-time rule exists.
- Balance per currency in the core: defensible but changes the scalar response beyond the assignment.
