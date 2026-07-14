# Test strategy

## 1. Goal

Prove the required behavior with the smallest reliable suite that fits the
implementation scope. Tests should catch financial duplication,
incorrect conflict handling, false distributed guarantees, and outage behavior.
They should not spend core time proving optional infrastructure.

This strategy implements `NFR-030` and `NFR-031` and supplies evidence for the
functional and non-functional IDs in `../spec/requirements.md`.

## 2. Fixed test environment

- Java 17 source/bytecode and container runtime (`CON-002`). The normal local
  Maven JVM may be JDK 21; final compatibility is also run on Java 17.
- Spring Boot 4.1 and JUnit Jupiter.
- H2 at repository/integration boundaries: isolated in-memory databases for
  tests; the selected `NFR-035` target uses separate file-backed paths/volumes
  for local and Compose runtime. If that `SHOULD` is cut, document the actual
  embedded mode and make no restart-durability claim.
- AssertJ for readable assertions if included in the build.
- WireMock for controlled Account responses, delays, request counts, and captured
  `traceparent` at the Gateway HTTP boundary.
- A separate `integration-tests` Maven module for at least one real two-service
  flow. That module may start both applications for tests; production modules
  must still communicate only over HTTP.

PostgreSQL and Testcontainers are outside the current core. Do not add them
until the required H2 suite is green (`CON-001`, explicit out-of-core scope).

## 3. Test layers

### Domain and application unit tests

Use plain JUnit without Spring for:

- event normalization and semantic equality (`FR-020`, `FR-021`);
- credit/debit arithmetic with `BigDecimal` (`FR-031`);
- status-transition rules, especially terminal `APPLIED` (`FR-040`);
- retry/failure classification if retry is implemented (`NFR-022`).

Inject `Clock` or fixed instants. Do not assert against the live wall clock.

### Service integration tests with real H2

Use Spring Boot test slices or service-level integration tests for:

- primary/unique constraints and transaction boundaries (`FR-020`, `FR-022`,
  `FR-024`, `FR-030`);
- one-currency-per-account under normal and concurrent writes (`FR-032`);
- deterministic event/recent-transaction ordering (`FR-013`, `FR-033`);
- derived balance and negative balance (`FR-031`);
- local health database diagnostics (`NFR-013`).

Use a fresh database name or reset schema per test context. Never let one test
depend on data inserted by another.

### HTTP/controller tests

Use MockMvc or a started random port to prove:

- validation status, problem details, and field names (`FR-010`);
- `404`, `409`, and `503` contracts;
- response ordering and status representation;
- no stack trace or metadata leak in errors.

Do not stop at controller tests for transaction or resilience claims.

### Gateway client/resilience tests with WireMock

Control the Account boundary to prove:

- Gateway stores before calling Account (`FR-011`);
- identical applied replay causes no second Account call (`FR-020`);
- connection error, timeout, selected `5xx`, and open circuit map to bounded
  `503` with “could not be confirmed” wording (`FR-041`);
- Account validation/conflict `4xx` does not count as an infrastructure failure
  (`NFR-021`);
- a valid W3C `traceparent` arrives at Account (`NFR-010`);
- optional retry has a finite attempt count and never retries non-retryable
  responses (`NFR-022`).

Use a generous upper bound for timeout tests. Also assert request count and state;
latency alone is weak and can be flaky on CI.

### Real two-service acceptance test

Start both actual applications on random or reserved test ports with separate H2
databases. Submit one event through Gateway and query the resulting state. Prove:

- the call crosses HTTP rather than an in-process service reference (`FR-002`,
  `FR-003`);
- one Gateway event and one Account transaction are observable;
- replay does not change the balance (`FR-020`, `FR-022`).

This in-process-context test does not prove the executable-JAR packaging or
separate-PID claim. AT-002 and AT-003 supply that packaging/runtime evidence.

Keep this test focused. Detailed failure permutations belong in the faster
WireMock/service tests.

### Compose and human demo

Use `demo-runbook.md` to verify late/stopped Account behavior, local Gateway reads,
recovery by safe retry, health, logs, and metrics. Compose is a `SHOULD` under
`NFR-032`; it does not replace automated `NFR-031` coverage.

When `NFR-035` remains selected, the runtime demo also proves its narrow claim:
`stop` then `start` with the same Account volume retains a committed row. It does
not prove production durability, replication, failover, or restore.

## 4. Proposed test organization

Names below are targets, not claims that files exist:

```text
account-service/src/test/java/.../
  TransactionApplicationServiceTest.java
  AccountTransactionRepositoryIT.java
  AccountConcurrencyTest.java
  AccountQueryIT.java
  AccountControllerIT.java
  TraceReceptionIT.java

event-gateway/src/test/java/.../
  EventRequestValidationTest.java
  EventSemanticEqualityTest.java
  GatewayEventRepositoryIT.java
  EventApplicationServiceIT.java
  EventIdempotencyTest.java
  GatewayConcurrencyTest.java
  EventControllerIT.java
  AccountHttpTimeoutIT.java
  CircuitBreakerTest.java
  TracePropagationIT.java
  GatewayStructuredLoggingIT.java
  GatewayHealthIT.java
  EventOutcomeMetricsTest.java

integration-tests/src/test/java/.../
  EventLedgerAcceptanceTest.java
```

Service modules configure Surefire to include their local `*Test` and `*IT`
classes. The integration module names the required cross-service class
`EventLedgerAcceptanceTest`, so the ordinary Maven `test` phase discovers it. A
green build with zero discovered integration tests is not evidence.

## 5. Required data partitions

Validation must include null, blank, malformed, unknown enum, zero, negative,
URL-unsafe/over-128 identifiers, and amounts outside `decimal(38,18)` where
applicable. Equality/conflict cases must include:

- `150.0` versus `150.00` as equal;
- `usd` versus `USD` after normalization as equal;
- metadata object key order as equal;
- absent, null, and empty-object metadata as equal;
- metadata numeric representation follows ordinary `JsonNode.equals`; include
  `1` versus `1.0` as the documented conflict/simplification;
- metadata array order as significant;
- changed account, type, amount, currency, instant, or metadata value as conflict.

Use instants with deterministic ties to prove the `eventTimestamp, eventId`
ordering rules in `FR-013` and `FR-033`.

## 6. Concurrency approach

For `FR-024` and the first-currency race in `FR-032`:

1. prepare all requests before releasing them;
2. use a latch/barrier to start threads together;
3. apply a finite future/test timeout;
4. wait for every result;
5. query database row counts and derived balance;
6. assert final Gateway state is not downgraded from `APPLIED`;
7. repeat enough times to make the race credible without making the suite slow.

Do not use an arbitrary sleep as concurrency coordination.

## 7. Observability tests

- Capture an Account request and validate `traceparent` against
  `^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$` (`NFR-010`).
- On Boot 4.1, add the focused OpenTelemetry test support and use
  `@AutoConfigureTracing(export = false)` (or verified equivalent) because
  reporting trace components are not automatically active in ordinary
  `@SpringBootTest`; the propagation test must not require a live collector.
- Capture one log event from each service and parse it as JSON. Assert timestamp,
  level, service, trace ID during a request, and message (`NFR-011`).
- Assert logs do not contain metadata values, authorization headers, or complete
  financial request JSON.
- Read the meter registry and assert allowed outcomes only; inspect meter tags to
  ensure no event/account/trace IDs (`NFR-012`).
- Use Boot 4's focused metrics test support/`@AutoConfigureMetrics` when the test
  context would otherwise omit the registry being asserted.
- Test local DB health separately from dependency/circuit state (`NFR-013`).

## 8. Command ladder and evidence

Run the smallest command that can fail first:

```bash
./mvnw -pl :account-service -Dtest=AccountTransactionRepositoryIT test
./mvnw -pl :account-service -am test
./mvnw -pl :event-gateway -Dtest=AccountHttpTimeoutIT,CircuitBreakerTest test
./mvnw -pl :event-gateway -am test
./mvnw -pl :integration-tests -am test
./mvnw test
./mvnw verify
```

For every command record:

- UTC timestamp and Git revision/worktree state;
- exact command and working directory;
- exit code;
- tests run, failures, errors, and skips;
- relevant report path under `target/surefire-reports`; use Failsafe only for an
  explicitly optional test actually bound there;
- requirement IDs proved and remaining gaps.

## 9. Stop rules

Stop and fix the core when:

- Java is not 17;
- a mandatory test is skipped or not discovered;
- a race test can leave duplicate financial effect or downgrade `APPLIED`;
- a failure can hang beyond the documented bound;
- a test relies on service state from another test;
- root `./mvnw verify` is red;
- required evidence exists only as a mock or prose claim.

Do not trade these failures for optional dashboards, retries, or extra APIs.
