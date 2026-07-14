# ADR-008: Trace, JSON log, metric, and health contracts

- Status: Proposed
- Date: 2026-07-14
- Requirements: NFR-010 through NFR-014

## Context

“Add observability” is too vague to test. Financial identifiers and metadata can also create metric cardinality or data-exposure problems.

## Decision

Implement four separate signals:

1. **Trace:** Boot 4.1's `spring-boot-starter-opentelemetry`; continue incoming W3C context and propagate `traceparent` through Spring's auto-configured `RestClient.Builder`. Trace tests use Boot 4's tracing test support rather than assuming `@SpringBootTest` enables reporting components.
2. **Log:** Spring Boot native structured JSON with timestamp, level, service, trace ID, and message. Do not log metadata or complete payloads.
3. **Metric:** Micrometer counter `ledger.events` with only `outcome=created|replayed|conflict|apply_failed`; an admitted Prometheus extension exposes it as `ledger_events_total`.
4. **Health:** local DB diagnostic plus last-observed Account/circuit state at Gateway. Do not issue a live Account request for every health call.

An optional `X-Trace-Id` response header helps the demo, but W3C context remains the propagation contract.

## Why

Each signal answers a different operational question and has a concrete proof. Bounded labels prevent a time-series per account/event.

## Consequences

- Account outage can make Gateway `DEGRADED` while local event reads remain useful.
- A closed circuit alone is not proof that Account is currently healthy; the dependency state may be `UNKNOWN`.
- Jaeger is a gated Compose profile, not required to prove propagation.

## Rejected alternatives

- Custom correlation header only: loses standard cross-tool propagation.
- Account/event IDs as metric labels: unbounded cardinality.
- Health that always pings Account: health traffic can worsen an outage and couples local availability to remote latency.
