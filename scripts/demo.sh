#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-event-ledger-proof-$(date +%s)}"
export COMPOSE_PROJECT_NAME
GATEWAY_HOST_PORT="${GATEWAY_HOST_PORT:-8080}"
export GATEWAY_HOST_PORT
GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:${GATEWAY_HOST_PORT}}"
EVIDENCE_DIR="${EVIDENCE_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/event-ledger-demo.XXXXXX")}"
export EVIDENCE_DIR

ACCOUNT_JAR="account-service/target/account-service-0.1.0-SNAPSHOT-exec.jar"
GATEWAY_JAR="event-gateway/target/event-gateway-0.1.0-SNAPSHOT-exec.jar"
ACCOUNT_ID="acct-demo"
EVENT_ONE="evt-001"
EVENT_TWO="evt-002"
READINESS_ATTEMPTS=60
RECOVERY_ATTEMPTS=30
REQUEST_MAX_SECONDS=5
OUTAGE_MAX_SECONDS=3

compose() {
  docker compose "$@"
}

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || {
    printf 'missing required command: %s\n' "$name" >&2
    exit 1
  }
}

preflight() {
  require_command docker
  require_command curl
  require_command jq
  docker compose version >/dev/null
  test -f "$ACCOUNT_JAR" || {
    printf 'missing %s — run ./mvnw verify first\n' "$ACCOUNT_JAR" >&2
    exit 1
  }
  test -f "$GATEWAY_JAR" || {
    printf 'missing %s — run ./mvnw verify first\n' "$GATEWAY_JAR" >&2
    exit 1
  }
  mkdir -p "$EVIDENCE_DIR"
  printf 'compose project=%s\nevidence=%s\ngateway=%s\n' \
    "$COMPOSE_PROJECT_NAME" "$EVIDENCE_DIR" "$GATEWAY_URL" \
    | tee "$EVIDENCE_DIR/run-identity.txt"
}

wait_for_http() {
  local url="$1"
  local out="$2"
  local attempt
  for attempt in $(seq 1 "$READINESS_ATTEMPTS"); do
    if curl -fsS --max-time 2 "$url" >"$out" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  printf 'timed out waiting for %s\n' "$url" >&2
  return 1
}

count_account_starts() {
  compose logs --no-color account-service 2>/dev/null \
    | grep -c 'Started AccountServiceApplication' || true
}

wait_for_new_account_start() {
  local baseline="$1"
  local attempt current
  # Do not probe Account through the Gateway client: refused connections during
  # startup would count against the circuit breaker before the demo begins.
  for attempt in $(seq 1 "$READINESS_ATTEMPTS"); do
    current="$(count_account_starts)"
    if [[ "$current" -gt "$baseline" ]]; then
      printf 'account ready starts=%s baseline=%s attempt=%s\n' \
        "$current" "$baseline" "$attempt" \
        | tee -a "$EVIDENCE_DIR/account-ready.txt" >/dev/null
      return 0
    fi
    sleep 1
  done
  printf 'timed out waiting for a new AccountServiceApplication startup log (baseline=%s)\n' \
    "$baseline" >&2
  return 1
}

post_event() {
  local body_file="$1"
  local headers_file="$2"
  local body_out="$3"
  local timing_file="${4:-}"
  local result
  result="$(curl -sS \
    --connect-timeout 2 \
    --max-time "$REQUEST_MAX_SECONDS" \
    -D "$headers_file" \
    -o "$body_out" \
    -w '%{http_code} %{time_total}' \
    -H 'Content-Type: application/json' \
    --data-binary @"$body_file" \
    "$GATEWAY_URL/events")"
  if [[ -n "$timing_file" ]]; then
    printf '%s\n' "${result#* }" >"$timing_file"
  fi
  printf '%s' "${result%% *}"
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf '%s: expected HTTP %s but got %s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_duration_below() {
  local timing_file="$1"
  local maximum="$2"
  local label="$3"
  jq -e --argjson maximum "$maximum" '. < $maximum' "$timing_file" >/dev/null || {
    printf '%s exceeded %s seconds; actual duration is %s\n' \
      "$label" "$maximum" "$(cat "$timing_file")" >&2
    exit 1
  }
}

assert_replay_header() {
  local headers_file="$1"
  grep -iE '^X-Idempotent-Replay:[[:space:]]*true[[:space:]]*$' "$headers_file" >/dev/null \
    || {
      printf 'missing X-Idempotent-Replay: true in %s\n' "$headers_file" >&2
      exit 1
    }
}

write_event_bodies() {
  jq -n \
    --arg eventId "$EVENT_ONE" \
    --arg accountId "$ACCOUNT_ID" \
    '{
      eventId: $eventId,
      accountId: $accountId,
      type: "CREDIT",
      amount: 150.00,
      currency: "USD",
      eventTimestamp: "2026-05-15T14:02:11Z",
      metadata: {source: "demo", batchId: "B-001"}
    }' >"$EVIDENCE_DIR/evt-001.json"

  jq -n \
    --arg eventId "$EVENT_TWO" \
    --arg accountId "$ACCOUNT_ID" \
    '{
      eventId: $eventId,
      accountId: $accountId,
      type: "CREDIT",
      amount: 25.00,
      currency: "USD",
      eventTimestamp: "2026-05-14T10:00:00Z",
      metadata: {source: "demo", batchId: "B-002"}
    }' >"$EVIDENCE_DIR/evt-002.json"
}

validate_compose() {
  compose config --quiet
  compose config >"$EVIDENCE_DIR/compose-resolved.yml"
}

build_images() {
  compose build
}

start_gateway_alone() {
  compose up -d event-gateway
  wait_for_http "$GATEWAY_URL/health" "$EVIDENCE_DIR/gateway-health-late-start.json"
  jq -e '
    .service == "event-gateway"
    and .status == "UP"
    and .checks.database == "UP"
    and .checks.accountService == "UNKNOWN"
    and .checks.circuitBreaker == "CLOSED"
  ' \
    "$EVIDENCE_DIR/gateway-health-late-start.json" >/dev/null
  if [[ -n "$(compose ps --status running -q account-service)" ]]; then
    printf 'Account must not start with the Gateway-only command\n' >&2
    exit 1
  fi
  compose ps >"$EVIDENCE_DIR/compose-ps-gateway-only.txt"
}

start_account() {
  local baseline
  baseline="$(count_account_starts)"
  compose up -d account-service
  wait_for_new_account_start "$baseline"
  compose ps >"$EVIDENCE_DIR/compose-ps-both.txt"
}

submit_and_replay_first_event() {
  local status
  status="$(post_event \
    "$EVIDENCE_DIR/evt-001.json" \
    "$EVIDENCE_DIR/evt-001-first.headers" \
    "$EVIDENCE_DIR/evt-001-first.json")"
  assert_status "$status" "201" "first submit"
  jq -e --arg id "$EVENT_ONE" \
    '.eventId == $id and .applicationStatus == "APPLIED"' \
    "$EVIDENCE_DIR/evt-001-first.json" >/dev/null

  status="$(post_event \
    "$EVIDENCE_DIR/evt-001.json" \
    "$EVIDENCE_DIR/evt-001-replay.headers" \
    "$EVIDENCE_DIR/evt-001-replay.json")"
  assert_status "$status" "200" "identical replay"
  assert_replay_header "$EVIDENCE_DIR/evt-001-replay.headers"
  jq -e --arg id "$EVENT_ONE" \
    '.eventId == $id and .applicationStatus == "APPLIED"' \
    "$EVIDENCE_DIR/evt-001-replay.json" >/dev/null

  curl -fsS "$GATEWAY_URL/accounts/${ACCOUNT_ID}/balance" \
    | tee "$EVIDENCE_DIR/balance-after-evt-001.json" \
    | jq -e --arg accountId "$ACCOUNT_ID" \
      '.accountId == $accountId and .currency == "USD" and (.balance | tonumber) == 150' >/dev/null
}

stop_account_and_prove_outage() {
  compose stop account-service

  local status
  status="$(post_event \
    "$EVIDENCE_DIR/evt-002.json" \
    "$EVIDENCE_DIR/evt-002-failed.headers" \
    "$EVIDENCE_DIR/evt-002-failed.json" \
    "$EVIDENCE_DIR/evt-002-failed-timing-seconds.txt")"
  assert_status "$status" "503" "outage submit"
  assert_duration_below \
    "$EVIDENCE_DIR/evt-002-failed-timing-seconds.txt" \
    "$OUTAGE_MAX_SECONDS" \
    "outage submit"
  jq -e --arg id "$EVENT_TWO" \
    '.status == 503
     and .eventId == $id
     and .applicationStatus == "APPLY_FAILED"
     and (.detail | contains("could not be confirmed"))' \
    "$EVIDENCE_DIR/evt-002-failed.json" >/dev/null

  curl -fsS "$GATEWAY_URL/events/${EVENT_TWO}" \
    | tee "$EVIDENCE_DIR/evt-002-local-read.json" \
    | jq -e --arg id "$EVENT_TWO" \
      '.eventId == $id and .applicationStatus == "APPLY_FAILED"' >/dev/null

  compose logs --no-color event-gateway \
    | tee "$EVIDENCE_DIR/gateway-logs-during-outage.txt" \
    | grep 'RETRYABLE_UNCONFIRMED' >/dev/null

  curl -fsS "$GATEWAY_URL/events?account=${ACCOUNT_ID}" \
    | tee "$EVIDENCE_DIR/acct-demo-events-during-outage.json" \
    | jq -e --arg first "$EVENT_TWO" --arg second "$EVENT_ONE" \
      '.[0].eventId == $first and .[1].eventId == $second' >/dev/null

  curl -fsS "$GATEWAY_URL/health" \
    | tee "$EVIDENCE_DIR/gateway-health-during-outage.json" \
    | jq -e '
      .service == "event-gateway"
      and .status == "DEGRADED"
      and .checks.database == "UP"
      and .checks.accountService == "UNAVAILABLE"
    ' >/dev/null
  compose ps event-gateway | tee "$EVIDENCE_DIR/compose-ps-during-outage.txt" >/dev/null
  if [[ -z "$(compose ps --status running -q event-gateway)" ]]; then
    printf 'Gateway stopped during the Account outage\n' >&2
    exit 1
  fi
}

recover_failed_event() {
  local baseline
  baseline="$(count_account_starts)"
  compose start account-service
  wait_for_new_account_start "$baseline"

  local attempt status="503"
  for attempt in $(seq 1 "$RECOVERY_ATTEMPTS"); do
    status="$(post_event \
      "$EVIDENCE_DIR/evt-002.json" \
      "$EVIDENCE_DIR/evt-002-recovery-${attempt}.headers" \
      "$EVIDENCE_DIR/evt-002-recovery-${attempt}.json")"
    if [[ "$status" == "200" ]]; then
      break
    fi
    sleep 1
  done
  assert_status "$status" "200" "recovery submit"
  assert_replay_header "$EVIDENCE_DIR/evt-002-recovery-${attempt}.headers"

  curl -fsS "$GATEWAY_URL/events/${EVENT_TWO}" \
    | tee "$EVIDENCE_DIR/evt-002-recovered.json" \
    | jq -e --arg id "$EVENT_TWO" \
      '.eventId == $id and .applicationStatus == "APPLIED"' >/dev/null

  curl -fsS "$GATEWAY_URL/accounts/${ACCOUNT_ID}/balance" \
    | tee "$EVIDENCE_DIR/balance-after-recovery.json" \
    | jq -e '.currency == "USD" and (.balance | tonumber) == 175' >/dev/null
}

restart_account_and_reprove() {
  local baseline
  compose stop account-service
  baseline="$(count_account_starts)"
  compose start account-service
  wait_for_new_account_start "$baseline"

  curl -fsS "$GATEWAY_URL/accounts/${ACCOUNT_ID}/balance" \
    | tee "$EVIDENCE_DIR/balance-after-account-restart.json" \
    | jq -e '.currency == "USD" and (.balance | tonumber) == 175' >/dev/null

  local status
  status="$(post_event \
    "$EVIDENCE_DIR/evt-001.json" \
    "$EVIDENCE_DIR/evt-001-after-restart.headers" \
    "$EVIDENCE_DIR/evt-001-after-restart.json")"
  assert_status "$status" "200" "replay after Account restart"
  assert_replay_header "$EVIDENCE_DIR/evt-001-after-restart.headers"

  curl -fsS "$GATEWAY_URL/accounts/${ACCOUNT_ID}/balance" \
    | tee "$EVIDENCE_DIR/balance-after-final-replay.json" \
    | jq -e '.currency == "USD" and (.balance | tonumber) == 175' >/dev/null
}

inspect_isolation() {
  compose config >"$EVIDENCE_DIR/compose-resolved-final.yml"

  jq -e '
    (.services["account-service"].ports // null) == null
    and (.services["event-gateway"].ports | length) == 1
    and (.services["account-service"].environment.SPRING_DATASOURCE_URL
         | contains("/data/account/account-service"))
    and (.services["event-gateway"].environment.SPRING_DATASOURCE_URL
         | contains("/data/gateway/event-gateway"))
    and .services["account-service"].volumes[0].source == "account-data"
    and .services["account-service"].volumes[0].target == "/data/account"
    and .services["event-gateway"].volumes[0].source == "gateway-data"
    and .services["event-gateway"].volumes[0].target == "/data/gateway"
    and (.volumes | keys | sort) == ["account-data","gateway-data"]
  ' <(compose config --format json) >/dev/null

  printf 'Account host ports absent: yes\n' | tee "$EVIDENCE_DIR/isolation-summary.txt"
  printf 'Distinct JDBC paths: /data/account/account-service vs /data/gateway/event-gateway\n' \
    | tee -a "$EVIDENCE_DIR/isolation-summary.txt"
  printf 'Distinct volumes: account-data vs gateway-data\n' \
    | tee -a "$EVIDENCE_DIR/isolation-summary.txt"
}

print_summary() {
  compose ps | tee "$EVIDENCE_DIR/compose-ps-final.txt"
  cat <<EOF | tee "$EVIDENCE_DIR/demo-summary.txt"

Demo complete.
Compose project: ${COMPOSE_PROJECT_NAME}
Evidence directory: ${EVIDENCE_DIR}
Gateway URL: ${GATEWAY_URL}

Containers and named volumes were left running for review.
Manual cleanup when finished (destructive to this project only):
  COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME} docker compose down -v

EOF
}

main() {
  preflight
  write_event_bodies
  validate_compose
  build_images
  start_gateway_alone
  start_account
  submit_and_replay_first_event
  stop_account_and_prove_outage
  recover_failed_event
  restart_account_and_reprove
  inspect_isolation
  print_summary
}

main "$@"
