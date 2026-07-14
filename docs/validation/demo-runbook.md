# Demo runbook

This is the preferred Compose walkthrough for the planned implementation. It does not
claim that Compose, endpoints, or responses already exist. Run the automated suite first.
Stop if actual API contracts differ from `../spec/api-contract.md`; update code,
contract, README, and this runbook together rather than improvising during demo.

If `NFR-032` is explicitly cut, do not report this Compose runbook as passed.
Publish and execute the project-brief-permitted two-process local start procedure,
then adapt the same outage/local-read steps. `FR-042` recovery and the `FR-043`
balance proxy remain required. Enhanced dependency health and `NFR-035` restart
proof are required only when those `SHOULD` targets remain selected; otherwise
state the cut and remove unsupported claims.

The runbook demonstrates `FR-003`, `FR-013`, `FR-014`, `FR-020`, `FR-040` through
`FR-043`, `NFR-010` through `NFR-013`, `NFR-020`, `NFR-021`, and `NFR-032`.

## 1. Prerequisites

Required commands:

```bash
java -version
./mvnw --version
docker --version
docker compose version
curl --version
jq --version
rg --version
```

Expected: Java/Maven use Java 17 and every command exits `0`.

Docker commands change local runtime state. A human must intentionally approve
start, stop, restart, and cleanup. This run uses a unique Compose project name so
it does not touch an existing Event Ledger environment.

## 2. Create local evidence space and verify the build

Run from repository root:

```bash
export GATEWAY_URL="http://localhost:8080"
export COMPOSE_PROJECT_NAME="event-ledger-demo-$(date +%s)"
export EVIDENCE_DIR="$(mktemp -d)"
printf 'compose project=%s\nevidence=%s\n' "$COMPOSE_PROJECT_NAME" "$EVIDENCE_DIR"

bash -o pipefail -c "./mvnw verify 2>&1 | tee '$EVIDENCE_DIR/mvn-verify.log'"
test "$?" -eq 0
docker compose config --quiet
```

Expected: root verification and Compose validation exit `0`. The temporary
evidence directory is outside Git.

## 3. Start both services

```bash
docker compose up --build -d
docker compose ps | tee "$EVIDENCE_DIR/compose-ps-start.txt"

for attempt in $(seq 1 60); do
  if curl -fsS "$GATEWAY_URL/health" > "$EVIDENCE_DIR/gateway-health-start.json"; then
    break
  fi
  sleep 1
done

jq -e '.service == "event-gateway" and (.status == "UP" or .status == "DEGRADED")' \
  "$EVIDENCE_DIR/gateway-health-start.json"
```

Expected: both containers are running; Gateway health returns valid JSON. Initial
Account dependency state may be `UNKNOWN` until traffic is observed, so do not
claim dependency health from a closed circuit alone.

## 4. Submit and replay one event

Create a deterministic request outside the repository:

```bash
jq -n '{
  eventId: "evt-001",
  accountId: "acct-demo",
  type: "CREDIT",
  amount: 150.00,
  currency: "USD",
  eventTimestamp: "2026-05-15T14:02:11Z",
  metadata: {source: "demo", batchId: "B-001"}
}' > "$EVIDENCE_DIR/evt-001.json"

export TRACE_ID="11111111111111111111111111111111"
export PARENT_SPAN_ID="2222222222222222"

STATUS=$(curl -sS \
  -D "$EVIDENCE_DIR/evt-001-first.headers" \
  -o "$EVIDENCE_DIR/evt-001-first.json" \
  -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "traceparent: 00-$TRACE_ID-$PARENT_SPAN_ID-01" \
  --data-binary @"$EVIDENCE_DIR/evt-001.json" \
  "$GATEWAY_URL/events")
test "$STATUS" = "201"
jq -e '.eventId == "evt-001" and .applicationStatus == "APPLIED"' \
  "$EVIDENCE_DIR/evt-001-first.json"

STATUS=$(curl -sS \
  -D "$EVIDENCE_DIR/evt-001-replay.headers" \
  -o "$EVIDENCE_DIR/evt-001-replay.json" \
  -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data-binary @"$EVIDENCE_DIR/evt-001.json" \
  "$GATEWAY_URL/events")
test "$STATUS" = "200"
jq -e '.eventId == "evt-001" and .applicationStatus == "APPLIED"' \
  "$EVIDENCE_DIR/evt-001-replay.json"

curl -fsS "$GATEWAY_URL/accounts/acct-demo/balance" \
  | tee "$EVIDENCE_DIR/balance-after-evt-001.json" \
  | jq -e '.accountId == "acct-demo" and .currency == "USD" and .balance == 150'
```

Expected: first request is `201/APPLIED`, identical replay is `200/APPLIED`, and
balance remains exactly `150`. Automated AT-020/AT-023 must separately prove row
and Account request counts.

## 5. Stop Account and prove bounded failure plus local reads

```bash
docker compose stop account-service

jq -n '{
  eventId: "evt-002",
  accountId: "acct-demo",
  type: "CREDIT",
  amount: 25.00,
  currency: "USD",
  eventTimestamp: "2026-05-14T10:00:00Z",
  metadata: {source: "demo", batchId: "B-002"}
}' > "$EVIDENCE_DIR/evt-002.json"

RESULT=$(curl -sS \
  -D "$EVIDENCE_DIR/evt-002-failed.headers" \
  -o "$EVIDENCE_DIR/evt-002-failed.json" \
  -w '%{http_code} %{time_total}' \
  -H 'Content-Type: application/json' \
  --data-binary @"$EVIDENCE_DIR/evt-002.json" \
  "$GATEWAY_URL/events")
printf '%s\n' "$RESULT" | tee "$EVIDENCE_DIR/evt-002-failed-timing.txt"
STATUS="${RESULT%% *}"
test "$STATUS" = "503"
jq -e '.status == 503 and .eventId == "evt-002" and
  .applicationStatus == "APPLY_FAILED" and
  (.detail | contains("could not be confirmed"))' \
  "$EVIDENCE_DIR/evt-002-failed.json"

curl -fsS "$GATEWAY_URL/events/evt-002" \
  | tee "$EVIDENCE_DIR/evt-002-local-read.json" \
  | jq -e '.eventId == "evt-002" and .applicationStatus == "APPLY_FAILED"'

curl -fsS "$GATEWAY_URL/events?account=acct-demo" \
  | tee "$EVIDENCE_DIR/acct-demo-events-during-outage.json" \
  | jq -e '.[0].eventId == "evt-002" and .[1].eventId == "evt-001"'
```

Expected: the write returns `503` within the documented bound, wording does not
claim Account definitely failed, the event remains locally queryable, and list
order follows business time rather than arrival time.

## 6. Open the circuit and observe fail-fast behavior

The exact threshold must match documented configuration. This bounded loop uses
unique IDs and stops as soon as health reports `OPEN`:

```bash
CIRCUIT_STATE="UNKNOWN"
for n in $(seq 3 12); do
  SECOND=$(printf '%02d' "$n")
  jq -n --arg id "evt-fail-$n" --arg ts "2026-05-16T10:00:${SECOND}Z" '{
    eventId: $id,
    accountId: "acct-circuit",
    type: "CREDIT",
    amount: 1,
    currency: "USD",
    eventTimestamp: $ts,
    metadata: {source: "circuit-demo"}
  }' > "$EVIDENCE_DIR/circuit-request.json"

  curl -sS -o "$EVIDENCE_DIR/circuit-$n.json" -w '%{http_code} %{time_total}\n' \
    -H 'Content-Type: application/json' \
    --data-binary @"$EVIDENCE_DIR/circuit-request.json" \
    "$GATEWAY_URL/events" | tee -a "$EVIDENCE_DIR/circuit-timings.txt"

  curl -fsS "$GATEWAY_URL/health" > "$EVIDENCE_DIR/gateway-health-circuit.json"
  CIRCUIT_STATE=$(jq -r '.checks.circuitBreaker // "UNKNOWN"' \
    "$EVIDENCE_DIR/gateway-health-circuit.json")
  test "$CIRCUIT_STATE" = "OPEN" && break
done
test "$CIRCUIT_STATE" = "OPEN"

jq -n '{
  eventId: "evt-open-circuit",
  accountId: "acct-circuit",
  type: "CREDIT",
  amount: 1,
  currency: "USD",
  eventTimestamp: "2026-05-16T11:00:00Z",
  metadata: {source: "circuit-demo"}
}' > "$EVIDENCE_DIR/open-circuit-request.json"

curl -sS -o "$EVIDENCE_DIR/open-circuit-response.json" \
  -w '%{http_code} %{time_total}\n' \
  -H 'Content-Type: application/json' \
  --data-binary @"$EVIDENCE_DIR/open-circuit-request.json" \
  "$GATEWAY_URL/events" | tee "$EVIDENCE_DIR/open-circuit-timing.txt"
```

Expected: health reports `OPEN`; the final call is a fast `503`. Use automated
AT-044 to prove no downstream request occurred and avoid a fragile demo-only
latency claim.

## 7. Restart Account and safely recover the failed event

```bash
docker compose start account-service

RECOVERY_STATUS="503"
for attempt in $(seq 1 30); do
  RECOVERY_STATUS=$(curl -sS \
    -o "$EVIDENCE_DIR/evt-002-recovery-$attempt.json" \
    -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    --data-binary @"$EVIDENCE_DIR/evt-002.json" \
    "$GATEWAY_URL/events")
  test "$RECOVERY_STATUS" = "200" && break
  sleep 1
done
test "$RECOVERY_STATUS" = "200"

curl -fsS "$GATEWAY_URL/events/evt-002" \
  | tee "$EVIDENCE_DIR/evt-002-recovered.json" \
  | jq -e '.eventId == "evt-002" and .applicationStatus == "APPLIED"'

curl -fsS "$GATEWAY_URL/accounts/acct-demo/balance" \
  | tee "$EVIDENCE_DIR/balance-before-final-replay.json" \
  | jq -e '.currency == "USD" and .balance == 175'

STATUS=$(curl -sS -o "$EVIDENCE_DIR/evt-002-final-replay.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data-binary @"$EVIDENCE_DIR/evt-002.json" \
  "$GATEWAY_URL/events")
test "$STATUS" = "200"

curl -fsS "$GATEWAY_URL/accounts/acct-demo/balance" \
  > "$EVIDENCE_DIR/balance-after-final-replay.json"

jq -s -e '.[0].accountId == "acct-demo" and .[0].balance == .[1].balance' \
  "$EVIDENCE_DIR/balance-before-final-replay.json" \
  "$EVIDENCE_DIR/balance-after-final-replay.json"
```

Expected: same-ID retry eventually returns `200`, Gateway becomes `APPLIED`, and
the balance is exactly `175` (`150 + 25`). That proves the earlier Account row
survived `stop`/`start` on the same file-backed volume and the recovered event has
one effect. It does not prove production durability, backup, failover, or survival
after `docker compose down -v`.

## 8. Inspect trace, logs, metrics, and health

```bash
docker compose logs --no-color > "$EVIDENCE_DIR/compose.log"
rg -n "$TRACE_ID" "$EVIDENCE_DIR/compose.log"

curl -fsS "$GATEWAY_URL/health" \
  | tee "$EVIDENCE_DIR/gateway-health-final.json" \
  | jq .

curl -fsS "$GATEWAY_URL/actuator/prometheus" \
  | tee "$EVIDENCE_DIR/gateway-prometheus.txt" \
  | rg '^ledger_events_total'
```

Expected core evidence: JSON logs from both services use the same upstream trace
ID for the successful flow and contain required structured fields without full
metadata/financial payloads; health is truthful. The Prometheus command is an
`NFR-014`/`EXT-001` bonus. If not implemented, mark only that extension deferred.
Automated AT-050 remains the primary proof of Account `traceparent` propagation.

## 9. Finish and clean up

Review the evidence before removing anything:

```bash
printf 'evidence directory: %s\n' "$EVIDENCE_DIR"
docker compose ps
git status --short
```

When the human is finished and explicitly approves deletion of the isolated demo
containers and volumes:

```bash
docker compose down -v
unset GATEWAY_URL COMPOSE_PROJECT_NAME TRACE_ID PARENT_SPAN_ID
```

Keep raw logs/responses local. Copy only a short sanitized evidence summary into
the repository if public evidence is useful.
