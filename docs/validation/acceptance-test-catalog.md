# Acceptance test catalog

This catalog maps requirements to proof scenarios. A row is not `PASS` until the
stated result has been observed on the current revision.

## Build and service boundaries

| Test ID | Requirement | Level | Executable scenario | Pass evidence |
|---|---|---|---|---|
| AT-001 | `CON-002`, `NFR-030` | Build | From a clean clone, run `java -version`, `./mvnw --version`, `./mvnw test`, and `./mvnw verify` on the normal JDK 21; repeat test/verify once on a local Java 17 JDK or Java 17 CI. | Compiler release is 17; both lifecycle commands exit `0`; the mandatory two-service test is discovered during `test`; Java 17 compatibility run is green; all reactor modules succeed with zero failures/errors. |
| AT-002 | `FR-001` | Packaging | Build, confirm each service retains a thin main JAR and attaches one `*-exec.jar`, then start each executable `exec` JAR independently on its configured port. | Two separate executable JARs start and reach local health without sharing a JVM; the thin artifacts remain usable by the integration-test module. |
| AT-003 | `FR-001` | Runtime | Start both JARs, stop Gateway, then query Account health; repeat with Account stopped and query Gateway local health/read endpoint. | Stopping one PID does not stop the other process. |
| AT-004 | `FR-002` | Config + integration | Inspect resolved JDBC URLs and repository packages; run real flow with separate H2 databases. | Different database names/URLs; no production repository/entity shared across modules; communication observed over HTTP only. |
| AT-005 | `FR-003` | Real integration | Delay Account response during `POST /events`. | Gateway does not return success before Account confirms new apply or identical replay. |

## Gateway validation, storage, and reads

| Test ID | Requirement | Level | Executable scenario | Pass evidence |
|---|---|---|---|---|
| AT-010 | `FR-010` | HTTP parameterized | Submit missing/blank/over-128/whitespace/slash/query-delimiter IDs; missing/unknown type; missing/zero/negative or non-representable amount; missing/invalid/whitespace currency; missing/invalid instant; and non-object metadata. | Every case returns `400` ProblemDetail naming the rejected field; H2 precision never leaks as `500`. |
| AT-011 | `AC-FR010-02` | Integration | For every invalid request, inspect Gateway row count and WireMock Account request count. | Gateway count unchanged and Account request count `0`. |
| AT-012 | `FR-011` | Gateway + WireMock | Submit a valid event while Account returns retryable failure. Query Gateway afterward. | Gateway row exists with business time, ingestion time, and non-`APPLIED` state; downstream failure did not delete it. |
| AT-013 | `FR-012` | HTTP | Query one stored and one unknown event ID. | Stored event returns `200` with status; unknown ID returns `404` ProblemDetail. |
| AT-014 | `FR-013` | Repository + HTTP | Insert later timestamp first, then earlier timestamp; include equal timestamps with reversed IDs. | List order is `eventTimestamp ASC, eventId ASC`, independent of arrival order. |
| AT-015 | `FR-014` | Integration/demo | Stop or stub-fail Account and query stored Gateway event/list endpoints. | Local reads still return `200` without an Account request. |

## Idempotency and conflict behavior

| Test ID | Requirement | Level | Executable scenario | Pass evidence |
|---|---|---|---|---|
| AT-020 | `FR-020` | Gateway + WireMock + H2 | Submit a valid event to successful apply, then submit the identical payload again. | First response `201`, replay `200`, one Gateway row, and Account request count remains `1` after applied replay. |
| AT-021 | `AC-FR020-02` | Schema integration | Attempt duplicate Gateway `event_id` through a competing insert path. | Database primary/unique constraint rejects a second row; no Java-only check is the final guard. |
| AT-022 | `FR-021` | Parameterized | Replay with top-level amount `150.0`/`150.00`, lowercase/uppercase currency, absent/null/empty metadata, and reordered object keys. Then vary account, type, numeric amount, currency, instant, metadata value/array order, and metadata numeric representation (`1` vs `1.0`) one at a time. | Documented equivalents return replay behavior; ordinary `JsonNode.equals` differences return `409`, leave the original row unchanged, and cause no new Account call. |
| AT-023 | `FR-022`, `FR-030` | Account H2 integration | Call Account twice with one identical event. | `201` then `200`; one transaction row; one financial effect; database constraint present. |
| AT-024 | `FR-023` | Account HTTP integration | Reuse Account `eventId` with a changed account/type/amount/currency/time. | `409`; original transaction unchanged; no second effect. |
| AT-025 | `FR-024` | Concurrent integration | Release concurrent identical Gateway requests with one event ID. | One Gateway row, one Account transaction, one balance effect; responses are within documented allowed classes. |
| AT-026 | `FR-024`, `FR-040` | Concurrent fault test | Let one same-ID request succeed and a second fail late. | Final Gateway state remains `APPLIED`; late failure cannot downgrade it. |

## Account rules

| Test ID | Requirement | Level | Executable scenario | Pass evidence |
|---|---|---|---|---|
| AT-030 | `FR-030` | Transaction integration | Force insert failure/duplicate handling at the transaction boundary. | New transaction commits atomically or identical stored transaction is returned; no rollback-only follow-up error leaks. |
| AT-031 | `FR-031` | H2 integration | Apply credits and debits out of event-time and arrival order using decimal values. | Balance equals exact SQL/`BigDecimal` sum; arrival order has no effect; no `double` arithmetic. |
| AT-032 | `AC-FR031-02` | H2 integration | Apply a debit larger than current credits. | Request succeeds under core rules and balance is negative. |
| AT-033 | `FR-032` | H2 integration | Establish USD, then apply EUR to the same account. | `409`; no EUR transaction row; USD balance unchanged. |
| AT-034 | `FR-032` | Concurrent H2 integration | Concurrently establish two different first currencies for one account. | Exactly one account currency wins; only matching-currency financial effect persists; database-backed guard resolves race. |
| AT-035 | `FR-033` | HTTP + H2 | Create more than 20 transactions including equal timestamps, then get account details. | At most 20 transactions, ordered `eventTimestamp DESC, eventId DESC`; currency and derived balance are correct. |
| AT-036 | `FR-034` | HTTP | Query balance and details for an unknown account. | Both endpoints return `404` ProblemDetail. |

## Cross-service lifecycle and resilience

| Test ID | Requirement | Level | Executable scenario | Pass evidence |
|---|---|---|---|---|
| AT-040 | `FR-040` | State integration | Exercise new, confirmed, retryable-unconfirmed, recovered, terminal-conflict, and identical-applied paths. | Only `RECEIVED`, `APPLIED`, `APPLY_FAILED` appear; `APPLIED` is terminal; bounded failure code distinguishes retryable/terminal behavior; timestamps/attempt data match transitions. |
| AT-041 | `FR-041`, `NFR-020` | WireMock/process | Exercise connection refusal, delayed response beyond timeout, retryable `5xx`, and open circuit. | Each returns bounded `503`, never unexplained `500`; detail says outcome “could not be confirmed”; elapsed time stays under documented generous bound. |
| AT-042 | `FR-042` | Composite: Gateway WireMock + Account H2 | Gateway fixture simulates a lost/timed-out success followed by an identical Account replay response; Account AT-023 separately applies the same ID twice against its real database. | Gateway proof shows the second same-ID call and convergence to `APPLIED`; AT-023 proves Account's database-backed replay keeps one transaction/effect. Do not claim the WireMock itself proves an Account commit. |
| AT-042B | `FR-042` | WireMock scenario | Account returns deterministic currency/idempotency conflict, then the identical client request is repeated. | Gateway retains terminal `APPLY_FAILED` classification, returns the same `409`, and makes no blind second Account call. |
| AT-043 | `FR-043` | Gateway HTTP | Query balance through Gateway for known, unknown, and unavailable Account. | Results map to `200`, `404`, and clear bounded `503` respectively. |
| AT-044 | `NFR-021` | Circuit test | Feed retryable failures until threshold, then issue another call; advance/wait through half-open recovery. | Circuit opens, next call fails fast without downstream request, and configured recovery behavior is observed. |
| AT-045 | `NFR-021` | Circuit classification | Return Account `400` on apply, `404` for balance, and `409` repeatedly. | Expected semantic/client outcomes do not increase infrastructure failure count or open circuit; apply-contract `400` maps to stable `502`, balance `404` to `404`, and semantic `409` to `409`. |
| AT-046 | `NFR-022` | Optional retry test | If retry is enabled, return retryable error then success; separately return `400`, `409`, and open-circuit rejection. | Finite configured attempts/backoff for retryable error; no retry for listed non-retryable cases; total latency remains bounded. |

## Tracing, logs, metrics, and health

| Test ID | Requirement | Level | Executable scenario | Pass evidence |
|---|---|---|---|---|
| AT-050 | `NFR-010` | Gateway + captured HTTP | Send a Gateway request with/without valid upstream trace context and capture Account request headers. | Account receives a valid `00-<32hex>-<16hex>-<2hex>` `traceparent`; valid upstream trace ID is continued. |
| AT-051 | `NFR-011` | Log capture | Execute one traced request in each service and parse every captured line used as evidence. | JSON contains timestamp, level, service, trace ID during request, and message; metadata/secrets/full payload are absent. |
| AT-052 | `NFR-012` | Meter registry | Produce created, replayed, conflict, and apply-failed outcomes; inspect meter and tags. | Micrometer `ledger.events` has allowed outcome values only; no event/account/trace identifier tags. |
| AT-053 | `NFR-013` | Health integration | Query both healthy services; fail local DB check; stop Account/open circuit and query Gateway health. | Local DB failure drives owning service `DOWN`; Account outage can show Gateway `DEGRADED`; health does not live-call Account on every request. |
| AT-054 | `NFR-014`, `EXT-001` | Optional HTTP | If Prometheus extension is implemented, query `/actuator/prometheus`. | `200` text exposition includes `ledger_events_total`; otherwise record extension as deferred, not failed core. |

## Delivery and repository checks

| Test ID | Requirement | Level | Executable scenario | Pass evidence |
|---|---|---|---|---|
| AT-060 | `NFR-031` | Real integration | Start actual Gateway and Account with separate H2 databases, submit and replay one event through Gateway, then query the event and balance through the selected Gateway proxy or actual Account listener. | Real Gateway-to-Account HTTP flow succeeds; one row per service and one financial effect are observed; no WireMock in this test. |
| AT-061 | `NFR-032` | Compose/demo | Validate Compose, start with Account late, stop Account while Gateway runs, and use Gateway local reads. | `docker compose config --quiet` exits `0`; Gateway starts independently; outage behavior matches `FR-014`/`FR-041`. |
| AT-062 | `NFR-033` | Clean-clone review | Follow public README on a clean machine/clone without private notes. | Prerequisites, build/start/test commands, sample calls, architecture, resilience, assumptions, limitations, and AI-use statement are accurate. |
| AT-063 | `NFR-034` | Git review | Inspect `git log --oneline --decorate --reverse`. | Commits correspond to real green checkpoints; no fabricated count and no single unexplained implementation dump. |
| AT-064 | `CON-003` | Repository hygiene | Run tracked-file and content leak checks from `final-review-checklist.md`. | No original input/personal documents, credentials, local absolute paths, private preparation notes, or machine-local tool settings tracked. |
| AT-065 | `NFR-035` | Compose/process integration | Apply an event, stop and start Account without deleting its named volume, then query/replay through the documented service path; also inspect both service DB paths/volumes. | Previously committed Account row/balance remains, replay has one effect, and Gateway/Account use different H2 files/volumes. Evidence makes no production HA/backup claim. |
| AT-066 | `EXT-002` | Optional CI | After local Java 17 verify passes, push a workflow that runs the same wrapper command on Java 17. | Actual GitHub Actions run URL is green for a named commit SHA; until observed, documentation says only “configured.” |
| AT-067 | `EXT-003` | Optional trace backend | Start the observability Compose profile and submit one request. | Jaeger displays Gateway and Account spans in one trace; automated AT-050 remains green with the profile off. |
| AT-068 | `EXT-005` | Optional contract check | Validate both OpenAPI files and compare operations/statuses with controller mappings. | Validator exits `0`; implemented operations and documented statuses match. |
| AT-069 | `EXT-006` | Optional rate limit | If admitted, exceed the documented key/window limit and then allow the window to recover. | Deterministic `429`/`Retry-After`, bounded metric labels, and no impact on local reads; otherwise extension is deferred. |
| AT-070 | `EXT-008` | Optional Pact | Verify one Gateway-consumer interaction against the actual Account provider. | Provider verification runs in Maven and exits `0`; a generated consumer file alone is not a pass. |

## Coverage closure

Every core row must be `PASS` on the revision under review. `SHOULD` and
extension rows may be `UNPROVEN` or deferred only when the public README states
the limitation plainly.
