# Event Ledger

Status: the Java 17 multi-module Maven scaffold is implemented and verified —
both applications start, and the current context tests pass on JDK 21 and on a
real JDK 17. Business endpoints, persistence models, resilience, observability
wiring, Docker, and the real cross-service acceptance behavior are not
implemented yet.

Event Ledger is built as two independently runnable Spring Boot services. The
behavior below is the specified target of the remaining work:

- Event Gateway accepts, validates, stores, and queries transaction events, then
  calls Account Service synchronously over HTTP.
- Account Service owns an immutable transaction journal, idempotent application,
  account currency, balances, and account queries.
- Each service owns a separate H2 database. No production module reads the other
  service's repository or tables.

The project targets Java 17 source/bytecode with Spring Boot 4.1. A local JDK 21
may run Maven while `maven.compiler.release=17` enforces compatibility; the final
gate also runs the suite on Java 17.

## Reviewer path

1. [Project in plain English](docs/START-HERE.md)
2. [Architecture](docs/spec/architecture.md)
3. [Requirements](docs/spec/requirements.md)
4. [API contract](docs/spec/api-contract.md)
5. [Architecture decisions](docs/decisions/README.md)
6. [Acceptance catalog](docs/validation/acceptance-test-catalog.md)

All nine ADRs were reviewed and accepted by the candidate at T-000 (2026-07-14).
In the spec and validation documents, commands and expected results describe
target behavior; only the build commands below are verified today.

## AI-assisted workflow

AI tools are used for bounded implementation and independent review. Their local
prompts, agent configuration, time estimates, and working notes are deliberately
not published as solution documentation. Every public design claim must still be
supported by code, tests, or repeatable runtime evidence.

Only the candidate stages, commits, and pushes. AI implementation and review
requests do not authorize Git history or remote changes.

## Build (verified)

```bash
./mvnw --version           # wrapper Maven 3.9.16; build JDKs 17–26 accepted, bytecode targets release 17
./mvnw test                # current scaffold suite: two application context tests
./mvnw verify
./mvnw -DskipTests package
```

Packaging produces, per service, a thin JAR (`account-service-<version>.jar`,
`event-gateway-<version>.jar`) that stays usable as an in-reactor test
dependency, plus the runnable Spring Boot archive attached with the `exec`
classifier (`*-exec.jar`) for `java -jar` and, later, Docker. The real
two-service integration suite does not exist yet; it is created and proven by
the acceptance-test task.
