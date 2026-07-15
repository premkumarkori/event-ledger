# Validation guide

This folder explains how to prove Event Ledger behavior: automated tests, the
Compose demo, and the final review checklist. A claim is only `PASS` after the
named command or demo step has been observed on the revision under review.

## Files

| File | Use it for |
|---|---|
| `test-strategy.md` | test levels, tools, isolation, command order, and stop rules |
| `acceptance-test-catalog.md` | requirement-to-test scenarios and expected evidence |
| `demo-runbook.md` | a repeatable human walkthrough, including Account outage |
| `final-review-checklist.md` | the last core, security, documentation, and Git review |

The requirement source is `../spec/requirements.md`. Use its IDs in test names
and review findings.

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

Run from the repository root:

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
- a separate compatibility run uses Java 17 (local JDK or CI);
- each Maven command exits `0` with zero test failures and errors;
- root verify discovers **431** tests and every reactor module is `SUCCESS`;
- `git diff --check` exits `0` without whitespace-error output;
- every line in `git status --short` is understood and intentional.

Module artifact IDs are `account-service`, `event-gateway`, and `integration-tests`.

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
