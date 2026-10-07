# Repository guide

## Project shape

This is a headless ArtifactsMMO automation service with a Kotlin backend and a Svelte SPA.

- `client/`: typed ArtifactsMMO HTTP/WebSocket client.
- `domain/`: pure serializable queue and task models; no I/O.
- `engine/`: task planning, workers, executors, scheduling, and game automation.
- `server/`: Ktor API, auth, PostgreSQL persistence, Flyway migrations, and application wiring.
- `web/`: standalone Svelte 5/Vite frontend.
- `buildSrc/`: shared Kotlin/JVM 21 Gradle convention.
- `artifacts-docs`: API-reference git submodule; treat it as reference material unless a task explicitly updates it.

Gradle dependencies flow from `server` to `engine`, `domain`, and `client`; `engine` depends on `domain` and `client`. Keep `domain` free of HTTP, database, and orchestration code.

## Commands

Use the wrappers and versions exercised by CI (JDK 21 and Node 22):

```bash
./gradlew build --no-configuration-cache
./gradlew :client:test
./gradlew :domain:test
./gradlew :engine:test
./gradlew :server:test
./gradlew :server:run

cd web
npm ci
npm run check
npm run build
```

Run `:server:run` from the repository root; engine data files are resolved from the working directory. There is no configured Kotlin formatter/linter, frontend linter, or frontend test runner.

## Cross-module contracts

- `domain/.../TaskSpec.kt` is persisted as JSONB. Its `kind` discriminator, `TaskSpec.typeName`, and the database task `type` must agree.
- Kotlin API/domain JSON shapes are mirrored manually in `web/src/lib/types.ts`. Keep both sides synchronized when changing DTOs, queue models, task specs, enum values, or optional/default fields.
- Add behavior tests in the module that owns the behavior. The existing tests use `kotlin.test`, coroutine virtual time, Ktor test applications, Ktor `MockEngine`, and Testcontainers as appropriate.

## Repository data and generated files

- Root JSON files are tracked game-data snapshots or runtime seed/config files. Change them only when the task is specifically about game data or configuration.
- Never edit generated output under `.gradle/`, any `build/`, `web/node_modules/`, or `web/dist/`.
- Do not commit `.env`, tokens, database credentials, `logs/`, or other runtime state. Use `.env.example` only as the configuration template.
- `README_OLD.md` describes the retired desktop-era module layout; use current source, `settings.gradle.kts`, CI, and `deploy/VPS.md` as authoritative.
