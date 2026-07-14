# Architecture decisions

These ADRs capture recommended decisions most likely to cause rework or reviewer
questions. They describe the intended implementation; they do not claim code
exists. During T-000, the candidate records approval or an explicit replacement
before coding depends on them.

| ADR | Decision |
|---|---|
| [ADR-001](ADR-001-java17-and-platform.md) | Java 17 and compatible platform versions |
| [ADR-002](ADR-002-service-and-module-boundaries.md) | Two deployable services and no shared runtime business model |
| [ADR-003](ADR-003-end-to-end-idempotency.md) | Database-backed idempotency in both services |
| [ADR-004](ADR-004-gateway-lifecycle.md) | Persist-before-call Gateway lifecycle |
| [ADR-005](ADR-005-immutable-journal-derived-balance.md) | Immutable Account journal and derived balance |
| [ADR-006](ADR-006-currency-policy.md) | One currency per account in the core |
| [ADR-007](ADR-007-timeout-circuit-retry.md) | Timeout and circuit core; retry gated |
| [ADR-008](ADR-008-observability.md) | Trace, JSON log, metric, and health contracts |
| [ADR-009](ADR-009-runtime-h2-mode.md) | File-backed runtime H2 and in-memory test isolation |

Status meanings:

- **Proposed**: reviewed recommendation awaiting candidate approval at T-000.
- **Accepted**: candidate approved it; use it unless new evidence justifies a replacement.
- **Superseded**: retain the ADR, link the replacement, and explain why it changed.
