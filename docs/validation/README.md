# Validation guide

This folder defines how to prove the Event Ledger solution works. It is a plan,
not a test report. Nothing here means that code exists or that a test has passed.
Record results only after running the command against the exact revision being
reviewed.

## Files

| File | Use it for |
|---|---|
| `test-strategy.md` | test levels, tools, isolation, command order, and stop rules |
| `acceptance-test-catalog.md` | requirement-to-test scenarios and expected evidence |
| `demo-runbook.md` | a repeatable human walkthrough, including Account outage |
| `final-review-checklist.md` | the last core, security, documentation, and Git review |

The requirement source is `../spec/requirements.md`. Use its IDs in test
names, evidence records, progress updates, and review findings.

## What counts as evidence

Use the strongest available evidence:

1. an automated assertion against the real behavior being claimed;
2. an integration test against the real H2/HTTP/framework boundary;
3. a repeatable command with captured exit code and output;
4. a repeatable demo with saved request and response artifacts;
5. code inspection, only for facts that cannot reasonably be executed.

A comment, README sentence, mock setup, or AI summary is not proof on its own.
For example, a mock cannot prove a database unique constraint, transaction
rollback, W3C header propagation through the configured client, or a real
two-service flow.

## Standard verification sequence

Run from the repository root after the Maven scaffold exists:

```bash
java -version
./mvnw --version
./mvnw -pl :account-service -am test
./mvnw -pl :event-gateway -am test
./mvnw -pl :integration-tests -am test
./mvnw test
./mvnw verify
git diff --check
git status --short
```

Expected evidence:

- the normal local JVM may report JDK 21, while Maven compiles with release 17;
- a separate final compatibility run uses Java 17;
- each Maven command exits `0` with zero test failures and errors;
- root `verify` shows every reactor module as `SUCCESS`;
- `git diff --check` exits `0` without whitespace-error output;
- every line in `git status --short` is understood and intentional.

Artifact IDs in the `-pl` commands are the planned IDs. If implementation chooses
different IDs, update the public commands and all validation docs together.

## Core gate

Do not start an optional extension while any of these are red or unproven:

- `NFR-030`: root `./mvnw test` discovers the real two-service test, root
  `./mvnw verify` passes, and final Java 17 compatibility is recorded;
- `NFR-031`: required automated behavior coverage;
- `FR-020`, `FR-022`, `FR-024`: database-backed and concurrent idempotency;
- `FR-040`, `FR-041`: monotonic state and bounded, honest `503` behavior;
- `NFR-010`: valid `traceparent` reaches Account;
- one real Gateway-to-Account flow;
- `CON-003`: no private source material, secrets, or local paths in Git.

Prometheus, retry, CI, and tracing backends are valuable only after that gate.
Mark missing optional work as deferred; do not hide it inside a passing core result.

## Result vocabulary

- `PASS`: current evidence proves the expected result.
- `FAIL`: current evidence contradicts the expected result.
- `UNPROVEN`: the check was not run, could not run, or did not test the real claim.
- `NOT APPLICABLE`: allowed only with a requirement or approved decision explaining why.

A phase is complete only when every mandatory mapped check is `PASS`.
