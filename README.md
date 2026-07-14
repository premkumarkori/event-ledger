# Event Ledger

Status: specification ready; implementation has not started.

Event Ledger will contain two independently runnable Spring Boot services:

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

All ADRs currently remain `Proposed`; T-000 records the candidate's accepted
baseline before implementation begins. Commands and expected results in these
documents are plans, not claims that code exists or tests pass.

## AI-assisted workflow

AI tools are used for bounded implementation and independent review. Their local
prompts, agent configuration, time estimates, and working notes are deliberately
not published as solution documentation. Every public design claim must still be
supported by code, tests, or repeatable runtime evidence.

Only the candidate stages, commits, and pushes. AI implementation and review
requests do not authorize Git history or remote changes.

Build and runtime commands will be added and marked verified only after the Maven
scaffold and services exist.
