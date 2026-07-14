# Specification index

This is the implementation contract. If code and a spec disagree, stop and decide whether to fix the code or update the spec. Do not silently let them drift.

Read in this order:

1. [Requirements](requirements.md) — what must be true.
2. [Architecture](architecture.md) — service boundaries and request flow.
3. [Data model and state](data-model-and-state.md) — rows, constraints, states, and transitions.
4. [Failure model](failure-model.md) — crash points and uncertain outcomes.
5. [API contract](api-contract.md) — request/response behavior.
6. [Architecture decisions](../decisions/README.md) — why major choices were made and what they cost.
7. [Acceptance test catalog](../validation/acceptance-test-catalog.md) — how required behavior will be proved.

Requirement IDs use these prefixes:

- `FR`: functional behavior
- `NFR`: operational/quality behavior
- `CON`: project constraint
- `EXT`: optional extension
- `AC`: acceptance criterion
