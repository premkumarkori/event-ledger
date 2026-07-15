# ADR-002: Service and module boundaries

- Status: Accepted (candidate, 2026-07-14)
- Date: 2026-07-14
- Requirements: FR-001, FR-002, FR-003

## Context

The assignment asks for an Event Gateway and Account Service, independent H2 persistence, and HTTP communication. A shared entity/DTO module or direct repository call would make the exercise look like two processes while preserving compile-time and data coupling.

## Decision

Create a Maven aggregator with these production modules:

- `event-gateway`: public event API and Account HTTP client;
- `account-service`: internal transaction/balance API;
- `integration-tests`: test-only orchestration;
- `contracts`: static OpenAPI documents, not a runtime Java dependency.

Each service owns its own request/response records, entities, migrations/schema, and database URL. The root project manages versions and common build plugins only. Production code communicates only through HTTP.

Each service retains a normal thin JAR as its main Maven artifact and attaches its independently runnable Spring Boot JAR with classifier `exec`. The integration module can therefore consume application classes as test dependencies without trying to load classes nested under `BOOT-INF`; Docker and manual `java -jar` commands use the attached `*-exec.jar`.

## Why

This keeps data and failure ownership visible. A small amount of duplicated transport code is acceptable in exchange for independent change and deployability.

## Consequences

- Contract drift is controlled by OpenAPI plus integration/contract tests, not a shared class.
- The integration module may start both services for tests; that does not authorize an in-process production call.
- Build, Docker, and README checks must distinguish the thin dependency artifact from the executable `exec` artifact.
- Account is private in Compose. Gateway is the public entry point and provides
  the required documented balance proxy.

## Rejected alternatives

- One Spring application with packages named “services”: violates independent process behavior.
- Shared JPA entities/repositories: violates data ownership.
- Shared runtime DTO JAR: saves typing but hides contract coupling and coordinated releases.
