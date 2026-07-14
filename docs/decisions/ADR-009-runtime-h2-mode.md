# ADR-009: File-backed runtime H2, isolated in-memory test H2

- Status: Proposed
- Date: 2026-07-14
- Requirements: FR-002, NFR-035

## Context

The project brief permits an embedded or in-memory database. The failure
walkthrough discusses process stop/restart. An in-memory database would erase
Account history at stop and make the recovery demonstration misleading.

## Decision

Use:

- a distinct file-backed H2 URL for each service in normal local runtime;
- a distinct named Compose volume and database path for each service;
- a unique in-memory H2 database per automated test context;
- no H2 console in the normal public runtime.

The service schema and behavior stay the same in both modes. `docker compose stop`/`start` reuses the volumes. `docker compose down -v` intentionally deletes them.

Do not substitute SQLite or PostgreSQL in the core implementation. Dockerizing
the services does not make a separate database container necessary: the project brief
explicitly asks for independent H2 persistence, and file-backed H2 already proves
the required restart scenario. SQLite is a poor fit for the selected JPA/Hibernate
path and adds dialect/locking uncertainty. PostgreSQL is a sensible production
evolution, but it adds container readiness, credentials, migrations, and a second
SQL/locking behavior to verify without improving a required acceptance test.

## Why

This costs little and makes the uncertain-outcome/restart demo real: Account can retain a committed transaction while Gateway retries the same ID after restart. Tests remain fast and isolated.

## Consequences

- Runtime data survives only while the same local file/volume is preserved.
- A single embedded file is not a high-availability or horizontally scalable database.
- Restart evidence does not prove backup, replication, failover, production SQL/locking, or database portability.
- A future PostgreSQL migration requires dialect, transaction, concurrency,
  migration, and operational tests; it is not a URL-only configuration change.
- Leak checks must ignore/exclude `*.mv.db` and `*.trace.db`; these files never enter Git.

## Verification

1. submit and apply an event;
2. stop/start Account without deleting its volume;
3. query the same balance/transaction through the service path;
4. confirm the row remains;
5. confirm Gateway and Account point to different paths/volumes.

Use a separate test to prove clean in-memory test isolation. Do not run a destructive volume-removal command as part of an automated proof.
