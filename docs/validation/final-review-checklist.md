# Final review checklist

Use this on the exact revision intended for GitHub. Check a box only after seeing
current evidence. `Not run`, `not implemented`, and `assumed` are not passes.

## 1. Freeze the review target

```bash
date -u +%Y-%m-%dT%H:%M:%SZ
git rev-parse HEAD
git status --short
git diff --stat
git diff --check
```

- [ ] Revision and UTC review time are recorded.
- [ ] Every modified/untracked file is intentional.
- [ ] `git diff --check` exits `0` with no output.
- [ ] No code changes are made after evidence without rerunning affected checks.

## 2. Java 17 and clean build gate

```bash
java -version
./mvnw --version
./mvnw test
./mvnw verify
find . -path '*/target/surefire-reports/*.txt' | sort
```

- [ ] Normal local Java/Maven may report JDK 21; compiler release is 17, and a
  separate Java 17 compatibility run is recorded (`CON-002`).
- [ ] Root `./mvnw test` exits `0` and includes the real two-service test (`NFR-030`).
- [ ] Root `./mvnw verify` exits `0` (`NFR-030`).
- [ ] Every expected module is `SUCCESS`.
- [ ] Test totals show zero failures and errors.
- [ ] Every skipped test is understood and no mandatory case is skipped.
- [ ] Surefire discovered service-local `*IT` classes and
  `EventLedgerAcceptanceTest` during Maven `test`.

## 3. Mandatory functional gate

- [ ] Two independently runnable applications exist (`FR-001`).
- [ ] Each service owns a different H2 database and no production repository or
  in-process state crosses the boundary (`FR-002`).
- [ ] Gateway success waits for synchronous Account confirmation (`FR-003`).
- [ ] Validation rejects every `FR-010` partition with no write/Account call.
- [ ] Gateway persists before Account call and retains failed rows (`FR-011`).
- [ ] Event get/list and deterministic order pass (`FR-012`, `FR-013`).
- [ ] Gateway local reads work while Account is unavailable (`FR-014`).
- [ ] Gateway and Account database-backed idempotency pass (`FR-020`, `FR-022`).
- [ ] Semantic conflicts return `409` without overwrite/effect (`FR-021`, `FR-023`).
- [ ] Concurrent duplicates leave one row per database and one effect (`FR-024`).
- [ ] Account insert/replay is atomic (`FR-030`).
- [ ] Exact credit/debit balance, negative balance, and order independence pass
  (`FR-031`).
- [ ] One-currency rule passes, including first-write race (`FR-032`).
- [ ] Account details/recent order and unknown account behavior pass (`FR-033`,
  `FR-034`).
- [ ] Lifecycle uses only allowed states and never downgrades `APPLIED` (`FR-040`).
- [ ] Unavailable/timeout/`5xx`/open circuit produce bounded honest `503` (`FR-041`).
- [ ] Safe retry recovers an unconfirmed event without duplicate effect; terminal
  and contract failures are not blindly retried (`FR-042`).
- [ ] Public balance proxy maps known, unknown, and unavailable Account behavior
  without owning balance truth (`FR-043`).

Attach evidence IDs from `acceptance-test-catalog.md`; a checked box without an
evidence ID should be treated as unproven.

## 4. Mandatory non-functional gate

- [ ] Captured Account request contains valid continued W3C `traceparent`
  (`NFR-010`).
- [ ] Both services emit parseable JSON logs with required fields and no sensitive
  payload (`NFR-011`).
- [ ] Micrometer `ledger.events` uses only low-cardinality outcome tags; if
  EXT-001 is enabled, Prometheus exposes `ledger_events_total` (`NFR-012`).
- [ ] Local database health is truthful; if the enhanced Gateway dependency-state
  `SHOULD` is implemented, its degraded/unknown behavior is also proved
  (`NFR-013`).
- [ ] Account client has finite connect and response/read timeout (`NFR-020`).
- [ ] Circuit opens/fails fast/recovers and expected `4xx` does not count as
  infrastructure failure (`NFR-021`).
- [ ] One automated test uses real Gateway and Account applications (`NFR-031`).
- [ ] If NFR-035 is selected, runtime uses separate file-backed H2 paths/volumes,
  tests use isolated in-memory H2, and stop/start evidence is scoped honestly;
  otherwise the `SHOULD` is declared cut and no restart-durability claim remains.

## 5. API, data, and failure review

- [ ] Implemented requests/responses match `contracts/*.yaml` and README examples.
- [ ] Problem details use stable type/status/detail and do not expose stack traces.
- [ ] Money uses `BigDecimal`/SQL decimal, never `double`.
- [ ] Gateway status update is conditional/monotonic.
- [ ] Database constraints are present for both idempotency keys.
- [ ] First-account-currency race has a database-backed guard.
- [ ] No remote HTTP call is held inside a Gateway database transaction.
- [ ] Documentation never claims exactly-once delivery, global ordering,
  distributed atomicity, or restart durability without proof.

## 6. Runtime start and demo

```bash
docker compose config --quiet
```

- [ ] If implemented, Compose config exits `0` and images use Java 17 (`NFR-032`).
- [ ] If Compose was deliberately cut, tested step-by-step local start commands
  for both services are present and the preferred item is declared deferred.
- [ ] Under Compose, Account is private by default; any host exposure is
  debug-only/documented.
- [ ] Gateway can start when Account is late or unavailable.
- [ ] `demo-runbook.md` was executed or every unexecuted step is marked unproven.
- [ ] Raw logs, H2 data, traces, and evidence output remain local.

## 7. Public README and docs

- [ ] README explains architecture and service/database ownership (`NFR-033`).
- [ ] Prerequisites name Java 17, Maven Wrapper, Docker/Compose, curl, and jq as
  actually required.
- [ ] Clean build, test, start, stop, and sample-call commands were copied and run.
- [ ] Resilience choice, timeout/circuit semantics, and honest `503` wording are clear.
- [ ] Assumptions and limitations state that file-backed H2 survives only with
  the same local file/volume and is not production HA/backup evidence.
- [ ] AI-use statement is short, truthful, and does not expose private tool logs.
- [ ] Requirement/test traceability is current.
- [ ] Every relative Markdown link resolves from its public file location; no
  copied link still points to local planning folders.
- [ ] Optional features are labeled implemented, deferred, or out of scope accurately.

Planning-path leak check after copying docs:

```bash
PLANNING_PATH_PATTERN='gpt-sol''-plan|\.\./(01-''spec|02-exe''cution|03-phase-''plans|04-valid''ation|05-git''hub|06-agent-work''flow)'
PRIVATE_PATH_PATTERN='/Us''ers/|Down''loads/|Assess''ment/'
rg -n "$PLANNING_PATH_PATTERN|$PRIVATE_PATH_PATTERN" \
  README.md docs
```

Expected: no matches in the public repository. The public planning layout is
`docs/{spec,execution,phases,decisions,validation}`; old source-workspace folder
names are not valid public targets. Also open/click the relative links in README
and `docs/`; these searches do not prove that an arbitrary renamed target exists.

## 8. Public repository hygiene

Run both tracked-file and working-tree checks; `git grep` alone misses untracked files:

```bash
git status --short
git ls-files | sort
find . -type f -size +1M -not -path './.git/*' -print | sort
PRIVATE_PATH_PATTERN='/Us''ers/|Down''loads/|Assess''ment/|[A-Za-z]:\\Us''ers\\'
rg -n --hidden -i \
  --glob '!.git/**' --glob '!**/target/**' \
  "$PRIVATE_PATH_PATTERN" .
rg -n --hidden -i \
  --glob '!.git/**' --glob '!**/target/**' \
  '(api[_-]?key|secret|token|password)\s*[:=]' .
find . -type f \( -name '*.pdf' -o -name '*.docx' \) \
  -not -path './.git/*' -print | sort
git check-ignore -v .env 2>/dev/null
```

- [ ] No original input or personal documents, private preparation notes, or
  local absolute path is tracked (`CON-003`).
- [ ] No real credential, token, private key, `.env`, or local AI configuration is tracked.
- [ ] Every search match was reviewed; expected nonzero `rg` exit on zero matches
  is not mistaken for a failed safety check.
- [ ] `target/`, H2 files, logs, raw evidence, IDE state, and OS files are ignored.
- [ ] Local AI prompts, agents, settings, and private instruction files are not tracked.

## 9. Git history and release decision

```bash
git log --oneline --decorate --reverse
git diff --cached --check
git remote -v
```

- [ ] History shows real green checkpoints, not artificial commits (`NFR-034`).
- [ ] Commit messages describe behavior and requirement scope.
- [ ] Staged diff contains only reviewed files.
- [ ] Remote target is intentionally the public repository and exposes no token.
- [ ] License choice is deliberate; no license was added by assumption.
- [ ] Human explicitly authorizes the final commit and push.

## 10. Bonus gate

Check only implemented and verified extensions:

- [ ] Prometheus endpoint (`EXT-001`).
- [ ] GitHub Actions Java 17 verification (`EXT-002`).
- [ ] OTel Collector/Jaeger profile (`EXT-003`).
- [ ] Finite exponential retry with jitter after idempotency proof (`EXT-004`).
- [ ] Static OpenAPI validation (`EXT-005`).
- [ ] Rate limiting after all submission gates (`EXT-006`).
- [ ] Async replay worker remains design-only in core (`EXT-007`).
- [ ] Pact consumer/provider contract verification (`EXT-008`).

An unchecked bonus does not fail the core. A bonus that breaks core verification does.

## 11. Sign-off

| Field | Value |
|---|---|
| Revision reviewed | `<SHA>` |
| Root verify evidence | `<record/artifact>` |
| Mandatory acceptance | `<passed>/<total>` |
| Failed or unproven mandatory IDs | `<none or IDs>` |
| Declared limitations | `<list>` |
| Reviewer verdict | `<READY / READY WITH DECLARED RISKS / NOT READY>` |
| Human push authorization | `<not granted / granted at UTC time>` |

Only `READY` is a clean submission gate. `READY WITH DECLARED RISKS` requires the
public README to state those risks, and it must not hide a failed mandatory requirement.
