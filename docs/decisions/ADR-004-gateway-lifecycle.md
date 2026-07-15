# ADR-004: Persist-before-call Gateway lifecycle

- Status: Accepted (candidate, 2026-07-14)
- Date: 2026-07-14
- Requirements: FR-011, FR-040 through FR-042

## Context

There is no distributed transaction across two H2 databases and an HTTP call. Holding a local transaction open across the network still cannot make the Account commit atomic with the Gateway commit.

## Decision

Gateway:

1. validates and normalizes;
2. inserts `RECEIVED` and commits;
3. calls Account with no Gateway DB transaction held;
4. updates status in a fresh local transaction;
5. returns `APPLIED` only after new/replay confirmation.

States are `RECEIVED`, `APPLIED`, and `APPLY_FAILED`. `APPLIED` is terminal. `markFailed` uses a conditional update that cannot overwrite `APPLIED`.

A timeout or connection failure means the outcome is unconfirmed. It maps to a bounded `503` and retains the event for client retry and inspection.

## Why

This design exposes unavoidable uncertainty instead of pretending HTTP and two databases are one transaction. Downstream idempotency supplies safe recovery.

## Consequences

- A stored event can be failed/unconfirmed while Account has actually committed; same-ID retry reconciles it.
- Gateway event reads remain available while Account is down.
- Core recovery is client-driven; no background worker is promised.
- `APPLY_FAILED` is not evidence of an Account rollback.

## Rejected alternatives

- Remote call before local insert: Account can commit with no Gateway audit record.
- One `@Transactional` orchestration method: keeps locks open and still cannot roll back Account.
- Delete Gateway event on downstream failure: loses audit/recovery information.
