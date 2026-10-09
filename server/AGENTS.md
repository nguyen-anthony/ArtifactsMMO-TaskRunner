# Server guide

## Responsibilities

- `Main.kt`, `Backend.kt`, and `Database.kt` configure the process and wire the engine to persistence.
- `api/` owns Ktor auth, REST routes, SSE events, and wire DTOs.
- `db/` implements engine stores with PostgreSQL/JDBC.
- `src/main/resources/db/migration/` is the Flyway schema history.

The HTTP API remains available while the engine is disabled or waiting for the PostgreSQL advisory lock. Queue/config operations must therefore tolerate `backend.engine == null`; routes that require live game state return the existing service-unavailable response.

## API and persistence invariants

- Keep login, logout, health, and session discovery public; protected routes belong under the installed session guard.
- Browser updates use the existing named SSE events (`worker`, `task`, `log`, `control`, and `bank`). Preserve their serialized shapes and throttling/coalescing expectations in the web client.
- `JdbcTaskQueue` enforces claim, one-task-per-character, deduplication, and group-slot concurrency in SQL. Do not replace these atomic predicates with read-then-write checks in Kotlin.
- JDBC store methods run blocking work on `Dispatchers.IO` and scope each public operation to a transaction.
- Put schema changes in versioned migrations under `server/src/main/resources/db/migration/`, then update JDBC mapping and integration coverage together.
- PostgreSQL LISTEN/NOTIFY requires the session-pooler connection documented in `.env.example`; the advisory lock ensures only one process runs workers.

## Tests and running

Run `./gradlew :server:test`. Ktor route tests use `testApplication`; database tests use `TEST_DATABASE_*` when supplied, otherwise Testcontainers PostgreSQL, and skip only when neither a configured database nor Docker is available.

Run `./gradlew :server:run` from the repository root so legacy import and engine data files resolve correctly.
