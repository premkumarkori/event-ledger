# Event Ledger

Event Ledger accepts credit and debit events through an Event Gateway, applies
one financial effect per event ID in an Account Service, and returns an exact
derived balance. Both services are independently runnable Spring Boot
applications on Java 17.

This repository is a finished take-home implementation: HTTP APIs, separate H2
databases, timeouts and a circuit breaker, tracing/logging/metrics, Docker
Compose, and a real two-service acceptance suite are implemented and verified.

## Architecture

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

- **Event Gateway** owns intake, validation, storage, lifecycle status, local
  event queries, and a balance proxy.
- **Account Service** owns the immutable transaction journal, account currency,
  and derived balances.
- Production communication is **HTTP only**. Neither service reads the other
  service's tables or repositories.
- Each service owns a **separate database**.

## Idempotency

`eventId` is the end-to-end idempotency key.

- Same ID + same normalized payload → safe replay (one financial effect).
- Same ID + different payload → `409` conflict.
- Both services enforce a primary key on `event_id`, so a lost Account response
  plus a same-ID retry cannot create a second effect.

## Gateway lifecycle

| Status | Meaning |
|---|---|
| `RECEIVED` | Stored locally; Account confirmation not yet known |
| `APPLIED` | Account confirmed a new apply or safe replay (terminal) |
| `APPLY_FAILED` | Confirmation failed or Account rejected the request |

Failure codes on `APPLY_FAILED`:

- `RETRYABLE_UNCONFIRMED` — timeout, connection refusal, open circuit, selected
  Account `5xx`. Same-ID client retry is safe.
- `TERMINAL_CONFLICT` — idempotency or currency conflict. Do not retry as a new apply.
- `CONTRACT_ERROR` — Account rejected the internal request or returned an invalid
  body. Investigate; do not blind-retry.

A late failing thread never overwrites `APPLIED`.

## Prerequisites

- Java 17 (local Maven may also run on JDK 21; bytecode target stays 17)
- Maven Wrapper (`./mvnw`)
- Docker and Docker Compose (for the demo)
- `curl` and `jq` (for the demo script)

## Build and test

```bash
./mvnw test
./mvnw verify
./mvnw -pl :integration-tests -am test
```

Root verification discovers **431** tests across Account (237), Gateway (192),
and the real two-service integration module (2), with zero failures or errors.

Java 17 CI runs `./mvnw --batch-mode verify` on push and pull request. The
[GitHub Actions run for commit `72538a5`](https://github.com/premkumarkori/event-ledger/actions/runs/29443328779)
completed successfully.

That workflow proves Maven verify on Temurin 17 only; it does not run Docker or
Compose.

## Local two-process start

```bash
./mvnw -DskipTests package
```

Start each service in its own terminal:

```bash
# Terminal 1
java -jar account-service/target/account-service-0.1.0-SNAPSHOT-exec.jar
```

```bash
# Terminal 2
java -jar event-gateway/target/event-gateway-0.1.0-SNAPSHOT-exec.jar
```

Use the classified `*-exec.jar` names above. Do not pass a bare `*.jar` wildcard
that could select the thin JAR used by the integration-test module.

Default ports: Gateway `8080`, Account `8081`.

## Docker Compose

Images copy the already-built executable JARs, so verify first:

```bash
./mvnw verify
docker compose up --build -d
```

Only Gateway is published to the host (`8080` by default). Override with
`GATEWAY_HOST_PORT` if needed:

```bash
GATEWAY_HOST_PORT=18080 docker compose up --build -d
```

Account stays on the Compose network at `http://account-service:8081`. Named
volumes keep each service's H2 files separate.

## Demo

```bash
./mvnw verify
bash scripts/demo.sh
```

`scripts/demo.sh` expects the classified `*-exec.jar` artifacts (run Maven
verify or package first). It then builds Compose images, starts a unique project,
exercises happy path / replay / Account outage / recovery / restart retention,
and leaves containers, named volumes, and a temporary evidence directory for
review. It does **not** run `docker compose down -v`. When you are finished
reviewing that project, cleanup with `down -v` deletes that demo project's data.

## Sample calls

```bash
cat >/tmp/event-ledger-event.json <<'JSON'
{
  "eventId": "evt-001",
  "accountId": "acct-1",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {"source": "demo"}
}
JSON

# Submit the event
curl -sS -D - -H 'Content-Type: application/json' \
  --data-binary @/tmp/event-ledger-event.json \
  http://localhost:8080/events

# Identical replay (expect 200 and X-Idempotent-Replay: true)
curl -sS -D - -H 'Content-Type: application/json' \
  --data-binary @/tmp/event-ledger-event.json \
  http://localhost:8080/events

# Read stored event
curl -sS http://localhost:8080/events/evt-001

# List account events (eventTimestamp ASC, eventId ASC)
curl -sS 'http://localhost:8080/events?account=acct-1'

# Balance proxy
curl -sS http://localhost:8080/accounts/acct-1/balance

# Health
curl -sS http://localhost:8080/health
```

## Resilience

- Connect timeout **300ms**, read timeout **800ms**
- One named circuit breaker (`accountService`)
- No automatic HTTP retry in the core
- Same-ID client retry is safe only for `RETRYABLE_UNCONFIRMED` outcomes
- Write-path `503` responses include `Retry-After: 5`

## Observability

- W3C `traceparent` propagation Gateway → Account
- Spring Boot ECS structured JSON logs (no full sensitive payloads)
- Micrometer `ledger.events` with four bounded outcomes:
  `created`, `replayed`, `conflict`, `apply_failed`
- Prometheus scrape at Gateway `/actuator/prometheus`
- Public `/health` on both services (Gateway may report `DEGRADED` when Account
  is unavailable; local event reads still work)

## Storage

- Local and Compose runtime: file-backed H2 under separate paths/volumes
- Automated tests: isolated in-memory H2
- Container stop/start with the same named volume retains committed rows
- That is not a production HA, backup, or disaster-recovery claim

## Limitations

- No authentication or authorization
- No message broker, distributed transaction, or 2PC
- Not double-entry accounting
- Negative balances are allowed
- One currency per account
- Embedded H2 is not claimed as horizontally scalable production storage
- Deleting Compose volumes deletes that project's data

## Optional features

| Feature | Status |
|---|---|
| Prometheus (`EXT-001`) | Implemented |
| Java 17 CI (`EXT-002`) | Passing for `72538a5` at the Actions URL above |
| Static OpenAPI validation (`EXT-005`) | Implemented; both contracts pass semantic lint |
| Automatic HTTP retry (`EXT-004`) | Deferred |
| Jaeger / OTLP collector UI (`EXT-003`) | Deferred |
| Pact (`EXT-008`) | Deferred |
| Rate limiting (`EXT-006`) | Deferred |
| Reconciliation worker | Design-only / deferred |

## Reviewer path

1. [Project in plain English](docs/START-HERE.md)
2. [Architecture](docs/spec/architecture.md)
3. [Requirements](docs/spec/requirements.md)
4. [API contract](docs/spec/api-contract.md)
5. [OpenAPI](contracts/event-gateway-openapi.yaml) /
   [Account OpenAPI](contracts/account-service-openapi.yaml)
6. [Architecture decisions](docs/decisions/README.md)
7. [Demo runbook](docs/validation/demo-runbook.md)
8. [Acceptance catalog](docs/validation/acceptance-test-catalog.md)

AI tools assisted with bounded implementation and independent review. Public
claims are backed by code, tests, or a repeatable demo—not by unpublished
prompts or private notes.
