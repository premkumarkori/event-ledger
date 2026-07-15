# Demo runbook

Repeatable Compose walkthrough for the implemented Event Ledger. Prefer
`bash scripts/demo.sh` from the repository root; this document describes what
that script (and an equivalent manual run) proves.

If actual responses disagree with `../spec/api-contract.md`, stop and fix code,
contracts, README, and this runbook together.

## What the demo proves

- Gateway starts without Account being available yet.
- First event → `201` / `APPLIED`.
- Identical replay → `200` with `X-Idempotent-Replay: true`; balance stays `150`.
- Account outage → bounded `503`, durable `APPLY_FAILED`, local Gateway reads still work.
- Same-ID recovery after Account returns → `APPLIED` and balance `175`.
- Second Account stop/start on the same named volume retains balance `175`.
- The script does **not** run `docker compose down -v`.
- `GATEWAY_HOST_PORT` may override host port `8080`.

## Prerequisites

```bash
java -version
./mvnw --version
docker --version
docker compose version
curl --version
jq --version
```

Docker changes local runtime state. Use a unique Compose project name so the
run does not touch another Event Ledger environment.

## Fast path

Build executable JARs first (the script does not invoke Maven):

```bash
./mvnw verify
bash scripts/demo.sh
```

The script:

1. requires the classified `*-exec.jar` artifacts and builds Compose images;
2. starts Compose under a unique project name;
3. exercises happy path, replay, outage, recovery, and restart retention;
4. leaves containers, named volumes, and a temporary evidence directory for review.

Cleanup is intentional and separate:

```bash
COMPOSE_PROJECT_NAME=event-ledger-proof-1784139464 docker compose down -v
```

Replace the example value with the exact project name printed by the script.
The command deletes that demo project's containers, volumes, and data.

## Manual outline (same expectations)

```bash
export GATEWAY_URL="http://localhost:${GATEWAY_HOST_PORT:-8080}"
export COMPOSE_PROJECT_NAME="event-ledger-demo-$(date +%s)"

./mvnw verify
docker compose config --quiet
docker compose build
docker compose up -d event-gateway
```

Then:

1. Wait until `GET $GATEWAY_URL/health` returns `UP` with
   `accountService: UNKNOWN`; this proves Gateway can start alone.
2. Run `docker compose up -d account-service`, then wait until
   `docker compose logs --no-color account-service` contains
   `Started AccountServiceApplication`.
3. `POST /events` with `evt-001` CREDIT `150` → `201` / `APPLIED`.
4. Replay the same body → `200` / `X-Idempotent-Replay: true`; balance `150`.
5. `docker compose stop account-service`.
6. `POST /events` with `evt-002` CREDIT `25` → `503` / `APPLY_FAILED`;
   `GET /events/evt-002` and list-by-account still work from Gateway.
7. `docker compose start account-service`, retry `evt-002` → `200` / `APPLIED`;
   balance `175`.
8. Stop and start Account again without deleting volumes; balance remains `175`.

Expected list order during outage uses business time:
`eventTimestamp ASC`, then `eventId ASC` (so earlier `evt-002` before `evt-001`
when timestamps dictate that order).

## Observability checks

```bash
docker compose logs --no-color | rg '"traceId":"[0-9a-f]{32}"'
curl -fsS "$GATEWAY_URL/health"
curl -fsS "$GATEWAY_URL/actuator/prometheus" | rg '^ledger_events_total'
```

Prometheus scrape is implemented (`EXT-001`). Automated tests remain the primary
proof of W3C `traceparent` propagation.

## Scope honesty

This demo proves stop/start retention on separate named volumes. It does not
prove production HA, backup, failover, or survival after `docker compose down -v`.
