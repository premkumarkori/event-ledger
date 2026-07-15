# Event Ledger requirements

Status vocabulary in this file:

- **MUST**: required for submission.
- **SHOULD**: high-value interpretation or quality addition, but not a
  project-brief `MUST`; it may be explicitly deferred with a tested fallback or
  an honestly documented limitation.
- **MAY**: optional bonus after the mandatory gate.
- **OUT**: deliberately not implemented in the core.

## 1. System boundary

### FR-001 - Two services

The system MUST contain:

- an Event Gateway that owns event ingestion and event queries;
- an Account Service that owns applied transactions and account queries.

**AC-FR001-01:** each application can start from its own executable JAR.

**AC-FR001-02:** stopping one process does not stop the other.

### FR-002 - Separate data ownership

Each service MUST use its own H2 database and MUST NOT read the other service's tables or repositories.

**AC-FR002-01:** the JDBC URLs/database names differ.

**AC-FR002-02:** no shared runtime persistence module or in-process state passes data between services.

**AC-FR002-03:** the only production communication path is the documented HTTP contract.

### FR-003 - Synchronous service call

Gateway MUST call Account through synchronous REST for the core flow.

**AC-FR003-01:** a successful `POST /events` does not return success until Account confirms first application or a safe replay.

## 2. Event ingestion and querying

### FR-010 - Validate an event

Gateway MUST reject:

- a missing or blank `eventId`;
- a missing or blank `accountId`;
- a missing/unknown type other than `CREDIT` or `DEBIT`;
- a missing, zero, or negative amount;
- a missing or invalid three-letter currency;
- a missing or invalid ISO-8601 instant;
- non-object metadata when metadata is supplied.

Boundary rules are deliberately small and explicit:

- `eventId` and `accountId` are case-sensitive URL-safe path identifiers matching `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`; no whitespace, slash, query/fragment delimiter, or silent trimming;
- currency must be exactly three ASCII letters with no surrounding whitespace,
  normalize to uppercase, and be recognized by Java 17 `Currency.getInstance`;
- after removing insignificant trailing zeroes, amount must fit `decimal(38,18)` (at most 20 integer digits and 18 fractional digits); reject rather than round;
- absent or JSON `null` metadata normalizes to an empty object;
- metadata is parsed and stored as a JSON tree. After missing/`null` becomes
  `{}`, equality uses ordinary Jackson `JsonNode.equals`; object member order is
  not significant, while array order and JSON numeric node representation are.
  The project does not build a recursive numeric canonicalizer for metadata.

**AC-FR010-01:** the response is `400` and names the rejected field(s).

**AC-FR010-02:** validation failure writes no Gateway event and calls no Account endpoint.

**Note:** no two-decimal rule is invented. Amount stays `BigDecimal`; a generous database precision limit is documented separately.

### FR-011 - Store an event locally

Gateway MUST store the validated immutable business payload before it attempts the Account call.

**AC-FR011-01:** the row includes business time and ingestion time.

**AC-FR011-02:** a downstream failure does not delete the row.

### FR-012 - Get an event by ID

Gateway MUST return a stored event and its application status, or `404` when absent.

### FR-013 - List account events

Gateway MUST list events for the requested account in deterministic chronological order:

```text
eventTimestamp ascending, then eventId ascending
```

**AC-FR013-01:** submitting a later-timestamp event first does not change the returned order.

### FR-014 - Local reads during Account outage

Gateway event read/list endpoints MUST work from its local database while Account is stopped, slow, or rejected by the circuit breaker.

## 3. Idempotency and conflict behavior

### FR-020 - Gateway idempotency

One `eventId` MUST create at most one Gateway event row.

**AC-FR020-01:** identical replay of an already applied event returns the stored representation with `200` and no new Account call.

**AC-FR020-02:** the database has a unique/primary-key constraint on `event_id`; correctness does not depend only on check-then-insert.

### FR-021 - Identifier collision

If the same `eventId` is reused with a different normalized business payload, Gateway MUST return `409`, MUST NOT overwrite the event, and MUST NOT call Account.

Semantic comparison rules:

- identifiers are case-sensitive and must match the URL-safe pattern from FR-010;
- `BigDecimal` numeric comparison uses `compareTo`, so `150.0` equals `150.00`;
- currency is normalized to uppercase;
- absent/null metadata normalizes to `{}`; metadata uses `JsonNode.equals`, so
  object member order is irrelevant but `1` versus `1.0` in metadata may be a
  conflict. This rule does not affect top-level financial amount comparison;
- array order still matters;
- event instants are compared as instants, not original strings.

### FR-022 - Account idempotency

Account MUST independently enforce one transaction row per `eventId`.

**AC-FR022-01:** repeated identical Account requests return the stored transaction and do not add a second financial effect.

**AC-FR022-02:** the Account database, not Gateway memory, is the final guard.

### FR-023 - Account collision

Account MUST return `409` if a used `eventId` is presented with different account/type/amount/currency/time values.

### FR-024 - Concurrent duplicates

Concurrent requests with the same `eventId` MUST leave one row in each database and one balance effect.

It is acceptable in the core for more than one Gateway request to reach Account during a race; Account idempotency must still make the observable financial result correct. Gateway status transitions MUST be monotonic so a late failure cannot downgrade `APPLIED`.

## 4. Account behavior

### FR-030 - Apply a transaction

Account MUST atomically insert a new immutable transaction or return the existing identical transaction.

### FR-031 - Balance calculation

For a known account:

```text
balance = sum(CREDIT amounts) - sum(DEBIT amounts)
```

Account MUST use `BigDecimal`/SQL decimal arithmetic, never `double`.

**AC-FR031-01:** arrival order does not change the balance.

**AC-FR031-02:** a debit may make the balance negative because no overdraft rule was supplied.

### FR-032 - Currency rule

The first applied transaction establishes the account currency. A later event using a different currency for that account MUST return `409` and MUST NOT be inserted.

This core rule prevents invalid cross-currency addition while preserving a scalar balance response.

### FR-033 - Account details

Account MUST return the account ID, established currency, current derived balance, and recent transactions. “Recent” means newest 20 by default, ordered by `eventTimestamp DESC, eventId DESC`.

### FR-034 - Unknown account

Balance and detail requests for an account with no applied transactions MUST return `404`.

## 5. Cross-service lifecycle and errors

### FR-040 - Gateway application state

Gateway MUST expose one of:

- `RECEIVED`: stored, application not yet confirmed;
- `APPLIED`: Account confirmed a new apply or identical replay;
- `APPLY_FAILED`: Account application was not confirmed successful. `lastFailureCode` distinguishes an uncertain/retryable outcome from a deterministic rejection.

`APPLIED` is terminal. A failure update MUST NOT overwrite it.

### FR-041 - Downstream unavailable

When Account cannot be reached, times out, returns a retryable 5xx, or its circuit is open, Gateway MUST return a bounded `503` response rather than hang or return an unexplained `500`.

The message MUST say the application **could not be confirmed**, not that it definitely did not happen.

### FR-042 - Safe client retry

An identical retry of a `RECEIVED` event, or an `APPLY_FAILED` event whose last
failure is uncertain/retryable, MUST attempt Account again. The retry is safe
because FR-022 is already enforced.

An `APPLY_FAILED` event with a deterministic terminal conflict (for example account-currency mismatch) MUST return the same conflict without a blind Account retry. The stored failure code drives this decision.

### FR-043 - Public balance proxy

Gateway MUST expose `GET /accounts/{accountId}/balance` because Account is
internal but the assignment discusses client balance queries and the outage demo
must give the client a balance-query failure surface.

If Account is unavailable, this endpoint returns the same clear `503` class as the write path.

## 6. Tracing, logs, metrics, and health

### NFR-010 - Distributed trace

Every incoming Gateway request MUST have a trace. The Gateway-to-Account call MUST propagate W3C `traceparent` using the auto-configured `RestClient.Builder`.

**AC-NFR010-01:** a test captures the Account request and proves a valid `traceparent` header was sent.

**AC-NFR010-02:** W3C `traceparent` is the propagation format. Trace IDs appear in
structured logs; a custom `X-Trace-Id` response header or ProblemDetail `traceId`
field is not part of the public contract.

### NFR-011 - Structured logs

Both services MUST write JSON log lines containing at least:

- timestamp;
- level;
- service name;
- trace ID during a traced request;
- message.

Logs MUST NOT include complete metadata, secrets, or entire financial payloads.

### NFR-012 - Custom metrics

Gateway MUST expose at least one low-cardinality custom metric. Use:

```text
Micrometer logical name: ledger.events
Prometheus exposition (if EXT-001): ledger_events_total{outcome="created|replayed|conflict|apply_failed"}
```

Let the Prometheus registry add the counter `_total` convention; do not name the
Micrometer counter `ledger_events_total` and accidentally produce a duplicated
suffix. Do not tag metrics with `eventId`, `accountId`, or trace ID.

### NFR-013 - Health

Both services MUST expose `GET /health` with local database diagnostics.

Gateway SHOULD also expose Account dependency/circuit state without making a live remote call on every health check.

Gateway local DB health controls Gateway availability. Account outage may produce `DEGRADED`, not an automatic full `DOWN`.

### NFR-014 - Prometheus

Gateway SHOULD expose `/actuator/prometheus` as a cheap, visible bonus.

## 7. Resilience

### NFR-020 - Finite HTTP timeout

The Account client MUST configure finite connect and read/response timeouts. A circuit breaker without a timeout is not sufficient.

### NFR-021 - Circuit breaker

Gateway MUST apply a circuit breaker around the Account call and map open-circuit rejection to `503`.

Expected client/validation 4xx responses MUST NOT count as infrastructure failures. Timeouts, connection errors, and selected 5xx responses do.

### NFR-022 - Retry (gated extension)

After Account idempotency is proven, Gateway MAY retry retryable failures with a small maximum, exponential backoff, and jitter.

It MUST NOT retry validation/semantic `4xx`, Account `409`, or an already-open circuit indefinitely.

## 8. Testing and delivery

### NFR-030 - Standard build command

From a clean clone, using a supported JDK while emitting Java 17 bytecode:

```bash
./mvnw test
./mvnw verify
```

Both commands MUST build the required modules as appropriate and run the full
mandatory automated suite. The real two-service test must run in Maven's `test`
phase rather than only in Failsafe during `verify`.

### NFR-031 - Required automated coverage

The suite MUST prove:

- validation;
- Gateway and Account idempotency;
- conflicting reuse;
- out-of-order listing;
- credit/debit balance;
- Account failure -> bounded `503`;
- circuit-open/fail-fast behavior;
- trace propagation;
- one real Gateway-to-Account flow.

### NFR-032 - Compose

The repository SHOULD provide Docker Compose with Java 17 runtime images and a private Account service network path. The assignment labels Compose as preferred, not required; if it is deliberately cut, the README MUST give tested step-by-step local start instructions for both services.

Gateway must still start when Account starts late or is stopped; when Compose is selected, its dependency configuration must not defeat the degradation demo.

### NFR-033 - Documentation

The public README MUST contain architecture, prerequisites, exact start/test commands, sample calls, resilience choice, important assumptions, limitations, and a truthful short AI-use statement.

### NFR-034 - Commit history

Commits MUST represent real green checkpoints. No artificial commit count and no single squashed implementation commit.

### NFR-035 - Embedded restart behavior

Default local and Compose runtime profiles SHOULD use separate file-backed H2 databases so a normal process/container `stop` then `start` retains each service's rows. Automated tests MAY use isolated in-memory H2 databases.

The README and demo MUST scope this honestly: it proves only embedded local restart with the same files/volumes, not horizontal scaling, high availability, backup/disaster recovery, or production database portability. Deleting the Compose volumes deletes the data.

## 9. Constraints

### CON-001 - Scope discipline

Mandatory behavior and its evidence take priority over optional extensions.
Optional work stops whenever a core requirement is failed or unproven.

### CON-002 - Java

Source/API compatibility, compiler release, CI compatibility proof, and container
runtime are Java 17. The build uses `maven.compiler.release=17`. The normal local
Maven process may run on JDK 21 because Java 17 bytecode runs on later JDKs;
Java 21 APIs, preview features, and virtual threads are still not used.

### CON-003 - Public repository hygiene

No original input or personal documents, private preparation notes, credentials,
machine-local settings, or local absolute paths may be committed.

### CON-004 - Platform compatibility

Use the reviewed compatible baseline: Spring Boot 4.1.0, Spring Cloud 2025.1.2, and Spring Cloud CircuitBreaker 5.0.2 with its managed Java-17-compatible Resilience4j version. Do not override to Resilience4j 3.x.

### CON-005 - Evidence language

Plans may say “create,” “run,” or “expected.” README and public docs MUST NOT say a feature is implemented or a test passes until the named command has actually produced recorded evidence.

## 10. Optional extensions

| ID | Extension | Gate |
|---|---|---|
| EXT-001 | Prometheus endpoint | Core tests green |
| EXT-002 | GitHub Actions Java 17 verification | Clean local verify green |
| EXT-003 | OTel Collector + Jaeger Compose profile | Base propagation/logging green |
| EXT-004 | Exponential retry + jitter | Account idempotency and failure tests green |
| EXT-005 | Static OpenAPI contracts/contract checks | Core API frozen |
| EXT-006 | Rate limiting | All submission gates green |
| EXT-007 | Async replay worker | OUT of core; design only |
| EXT-008 | One Pact consumer/provider interaction | All core gates green and enough capacity remains to complete provider verification |

## 11. Explicitly out of core scope

- authentication/authorization implementation;
- Kafka/RabbitMQ in the mandatory path;
- distributed transactions/2PC;
- pagination;
- PostgreSQL/Testcontainers;
- Kubernetes/cloud deployment;
- Pact broker;
- async background recovery;
- multi-currency balance portfolios;
- overdraft or business-effective-date reversal rules;
- production retention/compliance policy.

These are discussion/roadmap topics, not hidden unfinished tasks.
