---
name: sandbox-integration-tests
description: Run or write live integration tests against the ArtifactsMMO sandbox server (api.sandbox.artifactsmmo.com). Use when verifying optimizer/engine behavior against real game data, checking API contracts, or when asked about the integration test suite or sandbox cheats (give_item, give_xp, clear_cooldown, teleport, spawn_event).
---

# Sandbox integration tests

Live tests in `engine/src/integrationTest/kotlin/com/artifactsmmo/sandbox/`, run against
`https://api.sandbox.artifactsmmo.com` (websocket `wss://realtime.sandbox.artifactsmmo.com`).

## Running

```powershell
. "$env:USERPROFILE\.jabba\jabba.ps1"; jabba use temurin@21
.\gradlew.bat :engine:integrationTest                                   # whole suite
.\gradlew.bat :engine:integrationTest --tests "*GearOptimizerSandbox*"  # one class
```

- Local/manual only. `integrationTest` is NOT part of `test`/`check`/`build`, so CI never runs it.
- Token: `ARTIFACTS_TOKEN` env var, or `ARTIFACTS_TOKEN=` in the repo-root `.env` (same token as production).
- Tests **skip** (not fail) without a token or without an active membership (`GET /my/details` → `member`).
- Report: `engine/build/reports/tests/integrationTest/index.html`; failure messages are in
  `engine/build/test-results/integrationTest/*.xml`.

## Hard rules

- Never call `reset_account` (not exposed by `SandboxService` on purpose). Never delete characters.
  Bank, inventory, and items are fair game.
- `client.sandbox.*` throws unless the client's base URL host is `api.sandbox.*`. Always build clients via
  `SandboxFixture.client`.
- Every sandbox cheat costs a ~3s character cooldown and counts toward the action rate limit
  (`clear_cooldown` excepted). Give only what a test needs, top up instead of re-giving
  (`SandboxFixture.ensureInventory`), and reuse state from earlier runs.
- `give_item` takes one item code per call; `give_xp` is capped at 100,000 XP per call (loop it).
- Use `runBlocking` (+ `withTimeout`), never `runTest`: virtual time skips the real cooldown delays.
- Test methods must return `Unit` (`fun x(): Unit = runBlocking { ... }`), or JUnit won't discover them.
- Pass `KnownLoadoutStore` a temp file (`SandboxFixture.gearOptimizer`) so tests never write the real
  `known_loadouts.json`.
- Anything that needs the database must use Testcontainers Postgres (see `server/.../JdbcTaskQueueTest.kt`),
  never `DATABASE_URL` (that's production Supabase).

## Fixture (`SandboxFixture`)

| Helper | Purpose |
|---|---|
| `assumeSandboxAvailable()` | Call in `@BeforeEach`; skips without token/membership |
| `client` | Sandbox `ArtifactsMMOClient` (`client.sandbox` has the cheats) |
| `characters()`, `character(name)`, `strongestCharacter()` | Live character state |
| `ensureInventory(char, code, qty)` | Gives only the shortfall |
| `actionHelper()` | Real `ActionHelper` with a fresh bank snapshot and the map graph loaded (navigation/bank trips work) |
| `gearOptimizer(helper)` | `GearOptimizer` with a throwaway known-loadout store |

`SandboxService`: `giveItem`, `giveXp`, `giveGold`, `teleport(mapId)`, `clearCooldown` (works mid-cooldown;
use it after long travel/rest/crafting), `spawnEvent(code)` (564 = already active or 5 active; 566 = event on cooldown).

## Provisioning after a sandbox wipe

The account is expected to have 5 characters at level 50 in combat and every skill. If the sandbox
resets, run the opt-in provisioning test (creates missing characters, loops `give_xp`; slow):

```powershell
.\gradlew.bat :engine:integrationTest -Psandbox.provision=true --tests "*SandboxProvisioning*"
```

## Known API facts verified by the suite

- Simulation `character_results[i].final_hp` is present, in request order (`SimulationContractTest`).
- Multi-character fights/simulations only work against `boss` monsters (486 otherwise).
- Fights with participants require everyone on the same map (486 "All characters must be on the same map location").

## Writing new tests

Assert on behavior (which actions/loadouts are chosen, contracts), not on random fight outcomes. Pick
easy targets (e.g. `chicken`) for deterministic "should win" checks. Keep classes independent; the task
runs them serially (`maxParallelForks = 1`) because characters and cooldowns are shared.
