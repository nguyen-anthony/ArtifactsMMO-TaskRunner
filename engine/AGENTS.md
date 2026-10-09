# Automation engine guide

## Architecture

- `com.artifactsmmo.engine.queue` defines the persistence-neutral task queue contract.
- `com.artifactsmmo.engine.worker` owns claiming, eligibility, preemption, checkpoints, execution, group fights, and worker status.
- `scheduler/` and `realtime/` produce raid, scheduled, and event tasks.
- `config/` accesses typed config documents.
- `com.artifactsmmo.core.task` is the retained, tuned game-automation layer: routing, loadouts, simulation, and per-action executors.

The two package families intentionally coexist. Persisted `domain.task.TaskSpec` values enter the worker layer, then `SpecMapper` and `LegacyExecutors` adapt them to the existing `core.task.TaskType` executors. Do not duplicate that mapping in producers or the server. `BossFight` is group-aware and uses the dedicated boss executor rather than the normal `SpecMapper.toLegacy` path.

## Worker and executor invariants

- One coroutine owns each character. Queue state and checkpoints, not in-memory previous-task state, provide suspend/resume behavior.
- Preemption suspends a task with its checkpoint and must not run completion cleanup; cancellation/completion does run executor stop/cleanup behavior.
- Keep cancellation exceptions fatal to the current coroutine when wrapping best-effort preparation or cleanup.
- Fatal account errors pause all workers; task-level failures must not stop unrelated characters.
- Preserve `RunState` checkpoint compatibility when adding progress fields or changing stop-condition behavior.
- Root data files such as `all_maps.json`, `all_monsters.json`, `all_teleport_potions.json`, and `known_loadouts.json` are loaded relative to the process working directory.

## Tests

Run `./gradlew :engine:test`. Worker and producer tests favor in-memory fakes and `kotlinx-coroutines-test` virtual time. Add focused tests for claim/preemption/checkpoint transitions when changing orchestration, and simulation/routing tests when changing deterministic game logic.
