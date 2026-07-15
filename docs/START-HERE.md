# Event Ledger in plain English

**Read this first.** It explains what the system does, why the design looks this
way, and what the tests and demo prove. The system is implemented and verified:
two Spring Boot services, separate H2 databases, HTTP APIs, resilience,
observability, Docker Compose, and a real two-service acceptance suite.

## 1. What does this project do?

Two small Java 17/Spring Boot applications process financial transaction events.

```text
Client
  |
  | POST /events
  v
Event Gateway ----HTTP----> Account Service
  |                              |
  v                              v
Gateway H2                    Account H2
```

The Event Gateway accepts a credit or debit event. It saves the event in its own
database, then asks Account Service to apply the financial effect. Account
Service saves an immutable transaction in its own database and calculates the
account balance from all stored transactions.

The two databases are deliberately separate. Gateway cannot read Account's
tables. Account cannot read Gateway's tables. Production communication is HTTP
only.

Why H2 with Docker? The brief asks for separate H2 databases, and file-backed H2
volumes already survive a normal container stop/start. PostgreSQL would be a
reasonable production follow-up but adds setup, migrations, and different
concurrency behavior to prove. SQLite adds JPA dialect and locking friction.
Neither improves a required acceptance test, so the core stays with two
file-backed H2 databases.

## 2. One normal request

Assume the client sends:

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {"source": "batch"}
}
```

The happy path is:

1. Gateway validates and normalizes the request.
2. Gateway inserts `evt-001` with status `RECEIVED` and commits.
3. Gateway calls Account over HTTP. No Gateway database transaction is held open.
4. Account creates account currency `USD` if this is the first transaction.
5. Account inserts one immutable transaction keyed by `evt-001` and commits.
6. Account returns `201`.
7. Gateway changes its row to `APPLIED` and returns `201` to the client.
8. Account balance is `150.00 USD`.

The phrase “save before call” matters. If Account is down, Gateway still has the
event and Gateway read APIs can still return it.

## 3. Why is `eventId` so important?

`eventId` is the idempotency key. Think of it as the permanent identity of one
business instruction.

The same ID and the same normalized content means replay:

```text
first evt-001 -> apply once
same evt-001 again -> return the stored result; do not add another $150
```

The same ID with different content means collision:

```text
evt-001 CREDIT 150 USD
evt-001 DEBIT  150 USD  -> 409 Conflict
```

Both services enforce a database primary key on `event_id`. Gateway protection
alone is insufficient because Gateway can time out after Account has already
committed. Account must independently recognize the replay.

## 4. The hardest failure case

The most important scenario is not “Account is completely down.” It is this:

```text
Gateway sends evt-002
Account commits evt-002
Account's HTTP response is lost
Gateway times out
```

Gateway cannot know whether Account committed. A timeout does not mean rollback.
The honest response is:

```text
503: application could not be confirmed; retrying the same eventId is safe
```

Gateway keeps the row as `APPLY_FAILED/RETRYABLE_UNCONFIRMED`. When the client
submits the identical `evt-002` again:

1. Gateway calls Account again with the same ID.
2. Account finds its existing transaction.
3. Account returns an identical replay response without a second effect.
4. Gateway changes its row to `APPLIED`.

This is why the design can provide an effectively-once financial effect without
claiming exactly-once network delivery.

## 5. What do the Gateway statuses mean?

| Status | Plain meaning | Can it change? |
|---|---|---|
| `RECEIVED` | Gateway stored the event but has not confirmed Account | may become applied or failed |
| `APPLIED` | Account confirmed new application or safe replay | terminal; never moves backward |
| `APPLY_FAILED` | success was not confirmed or Account deterministically rejected it | retryable only when the failure code allows it |

`APPLY_FAILED` is not always terminal. Its failure code matters:

- `RETRYABLE_UNCONFIRMED`: timeout, connection error, open circuit, selected 5xx;
- `TERMINAL_CONFLICT`: reused ID or account-currency conflict;
- `CONTRACT_ERROR`: Account rejected an internal request or returned an invalid
  response; investigate instead of retrying blindly.

A late failing thread must never overwrite `APPLIED`. The database status update
therefore includes a condition such as `WHERE application_status <> 'APPLIED'`.

## 6. Why not keep a mutable balance row?

A mutable balance looks simple:

```text
read 100
add 25
write 125
```

It becomes dangerous when two requests read `100` together and one update is
lost. For this project, Account stores immutable transactions and derives:

```text
balance = SUM(credits) - SUM(debits)
```

Two concurrent inserts cannot overwrite each other's amount. The primary key
prevents the same event from being counted twice.

This is not a complete double-entry accounting ledger. It is a small transaction
journal and derived account balance. Say that honestly in a technical review.

## 7. How does currency work?

The API returns one scalar balance, so it must not add USD and EUR together.

The first successful transaction establishes the account currency. Account has a
small row like:

```text
accounts(acct-123, USD)
```

A later EUR transaction for `acct-123` returns `409` and is not inserted. The
account row and its first transaction commit in the same local transaction, so a
crash cannot leave an empty account created by this flow.

## 8. Which time controls ordering?

There are three different times:

- `eventTimestamp`: business time supplied by the event;
- `receivedAt`: when Gateway received it;
- `appliedAt`: when application was confirmed.

Gateway list order uses business time:

```text
eventTimestamp ASC, eventId ASC
```

The ID is the deterministic tie-break when two events have the same timestamp.
Arrival order must not change the returned chronology.

## 9. What happens when Account is down?

- A new write is saved by Gateway, then returns a bounded and clear `503` when
  Account cannot confirm it.
- `GET /events/{id}` still works from Gateway's database.
- `GET /events?account=...` still works and remains ordered.
- A selected balance proxy cannot work without Account, so it returns `503`.
- Gateway health is based first on its own database. Account outage may make it
  `DEGRADED`; it should not automatically kill local Gateway reads.

The HTTP client uses a finite timeout. The circuit breaker prevents repeated
calls to a known-failing dependency. Automatic HTTP retry is deferred; same-ID
client retry remains the recovery path for unconfirmed applies.

## 10. What do the tests prove?

The suite is behavior-focused, not line-coverage decoration. Root verification
runs 431 tests. The important proofs include:

1. invalid input writes nothing and calls nothing;
2. identical replay creates one Gateway row and one Account effect;
3. changed payload under the same ID returns `409`;
4. concurrent duplicates still create one effect;
5. credits minus debits uses exact decimal arithmetic;
6. different currencies for one account cannot mix;
7. events return in business-time order;
8. timeout/refusal/open circuit return bounded, honest `503` responses;
9. a valid W3C `traceparent` reaches Account;
10. one automated test runs the real Gateway and real Account over HTTP with
    different H2 databases.

WireMock is useful for controlled HTTP failures and request counts. It cannot
prove that the real Account database has one row. That proof comes from Account
H2 tests and the real two-service acceptance test.

## 11. The design explanation in six sentences

1. Gateway persists the normalized event before making the synchronous Account
   call, so local reads survive dependency failure.
2. Both databases independently enforce unique `eventId`, so a lost response and
   same-ID retry cannot create a second financial effect.
3. Account stores immutable transactions and derives the balance, avoiding a
   mutable-balance lost update.
4. The first transaction atomically establishes account currency, preventing
   invalid cross-currency addition.
5. A finite timeout and circuit breaker produce bounded, honest `503` behavior,
   while tracing/logs/metrics make the flow observable.
6. Every guarantee maps to an automated test or the Compose demo; optional
   extensions are listed honestly as implemented or deferred.
