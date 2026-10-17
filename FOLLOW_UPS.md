I'l# Follow-ups

These are features, polish and technical improvements that fell outside the 9 milestones but are worth doing next.

## UI / UX

### Fight task form: per-drop cooking strategy
The fight task form lets you pick a monster and set the gear, but it doesn't let you set cooking strategies per drop (e.g. "always cook HP potions, skip MP potions").

**Proposal:** after the user picks the monster, show a list of its drops with checkboxes for cooking each one.

**Files to touch:**
- `web/src/pages/NewTask.svelte` – add dropdowns or toggles for each drop type.
- `server/.../api/ContentRoutes.kt` – the `/api/content` endpoint already lists all monsters and their drops; use that data.
- `domain/TaskSpec.kt` – add a `cookingStrategy` field to `FightTaskSpec` (or similar).

---

### Generate TypeScript types from Kotlin models
The TypeScript types in `web/src/lib/types.ts` are hand-written copies of the Kotlin data classes. When the backend model changes, they can fall out of sync, causing runtime errors.

**Proposal:** generate the TypeScript from the Kotlin using a Gradle plugin or a standalone script that reads the compiled Kotlin bytecode or source code.

**Files to touch:**
- `server/build.gradle.kts` – add a task that generates `web/src/lib/types.ts` from `domain/TaskSpec.kt` and other model classes.
- Or: create a standalone Kotlin script in `buildSrc/` or `scripts/` that uses KotlinPoet to emit TypeScript.

**References:**
- kotlinpoet: https://square.github.io/kotlinpoet/
- kotlin-ts-generator (third-party): https://github.com/ntrp/kotlin-ts-generator

---

## Backend Features

### Cron-style schedules
The schedule runner currently polls a hardcoded `schedules` table and reads rows with `enabled=true`. Schedules are not expressive: you set a fixed interval and start time, but can't say "every Monday at 9am" or "first of the month".

**Proposal:** replace the interval model with cron expressions, e.g. `0 9 * * 1` (9am every Monday). Use a cron parser like `java.util.cron` or `quartz-scheduler` to evaluate them.

**Files to touch:**
- `engine/.../ScheduleRunner.kt` – parse cron expressions and evaluate them each tick.
- `server/.../db/schema` – change the `schedules` table's `interval_*` columns to a single `cron_expression` text column.
- `web/src/pages/Configs.svelte` – add a cron editor or a time picker that generates cron.

**References:**
- Quartz CronExpression: https://www.quartz-scheduler.org/api/2.3.0/org/quartz/CronExpression.html
- cronutils: https://github.com/jmrozanec/cron-utils

---

### Boss group timeout (manual groups)
Event boss groups end when their event expires, and raid groups end when the raid closes. A boss group created manually from NewTask has no time limit. If a slot is never claimed ("Waiting for group: 2/3"), or a claimed member never reaches the tile ("Waiting for participants: X (not on tile)"), the group waits indefinitely.

**Proposal:** add an optional `formationTimeoutSeconds` to `TaskSpec.BossFight`. Start the clock when the group parent is first claimed, or when the initiator first finds a participant not ready. On timeout, do one of two things (configurable):
- **fight-with-present:** the initiator fights with the participants who are on the tile and ready, and the slots of the missing members are cancelled. This is now cheap, because the initiator already checks each participant through the API before every fight.
- **cancel:** cancel the group, so every member returns to its suspended or queued work.

**Files to touch:**
- `domain/.../task/TaskSpec.kt` – `formationTimeoutSeconds` and the timeout action on `BossFight`.
- `engine/.../worker/BossFightExecutor.kt` – track when waiting started (in task state, so it survives a restart) and apply the action. Today a member leaving cancels the whole group, so dropping a slot needs care.
- `engine/.../core/task/FightingExecutor.kt` – `executeBossStep` / `participantsNotReady`: let the initiator fight with just the ready subset once the timeout has passed.
- `web/src/pages/NewTask.svelte` – timeout and action inputs for group tasks.
- `web/src/pages/Dashboard.svelte` – a countdown on the group card while it waits.

---

## Technical Debt

### Reduce Postgres advisory lock lease time
The engine holds an advisory lock to ensure only one backend runs workers. On boot, it releases any old lock after a long timeout (currently ~15 seconds). If the backend crashes, workers are stuck for that duration.

**Proposal:** reduce the timeout (e.g. to 5 seconds) so the next backend starts faster. Or use a heartbeat: the running backend periodically renews the lock while it's alive, and a dead backend's lock expires naturally.

**Files to touch:**
- `server/.../db/InstanceLock.kt` – change the timeout or add heartbeat logic.
- `server/.../Backend.kt` – if using a heartbeat, schedule a renewal task.

---

### Whole-account inventory allocator (Phase 1 done; Phases 2–4 open)
**Done (Phase 1):** in-memory bank reservation ledger (`core/task/BankReservations.kt`, owned by `BankState`).
- Event/raid producers reserve each queued plan's bank demand under `task:<id>` (group parent id for parties); claims are consumed by the WebSocket `withdraw_item` log, released when the task ends (`CharacterWorker.onTaskEnd`) or is cancelled by its producer, and expire after 30 min.
- `GearOptimizer` (all callers: fights, TaskMaster, events) and `CoopOptimizer` plan against `BankState.availableFor(...)` = bank minus other characters' claims.
- Solo monster events plan all candidates jointly (`SoloFightAllocator`): scarce items go to the characters that lose the most kills/hour without them; others get their best fallback or are dropped.

**Phase 2 – other plan members' items:** include inventory/equipped gear of characters in the same event plan as a pool, with a hand-off step (owner deposits → recipient withdraws) sequenced before the fight.

**Phase 3 – everything respects reservations:** crafting/restock withdrawals, `StatGearOptimizer`, TaskMaster item turn-ins, food/key withdrawals should read `availableFor` and reserve their own demand; also reserve for normal (non-event) fight tasks when queued.

**Phase 4 – re-validate and re-plan:** at task start, check that the planned items are still obtainable; on a missing item (478) or loss streak, release + re-plan that character against the current pool instead of failing.

**Known gaps:** manual play and non-reserving code paths, snapshot drift between full syncs, and a restart clearing all claims (planning just becomes less conservative).
---

### Consolidate error handling
The backend has error handling in multiple places: the HTTP gateway (retries, rate limits), the worker (cooldown recovery), and the task queue (suspend/resume on failure). The rules aren't always consistent.

**Proposal:** document the error policy in one place (e.g. `docs/ERROR_HANDLING.md`) and ensure all three layers follow it. Consider moving some logic to a shared `ErrorRecovery` class.

**Files to touch:**
- `client/.../ErrorPolicy.kt` – already has the rules for 5xx retries and 499 waits. Expand the comments.
- `engine/.../CharacterWorker.kt` – ensure it respects the same policy.
- Create `docs/ERROR_HANDLING.md` with a flowchart or table.

---

### Cleanup: remove legacy code
Several classes from the old Compose app are still in the codebase but not used:
- `engine/.../TaskManager.kt`
- `engine/.../CharacterTaskRunner.kt`
- `engine/.../EventDispatcher.kt`
- `engine/.../RaidScheduler.kt`
- `engine/.../TaskStore.kt`

**Proposal:** delete them once you're confident the new engine is stable. Keep them in git history if needed.

**Commands:**
```bash
git log --all --oneline -- engine/src/main/kotlin/com/artifactsmmo/core/task/TaskManager.kt | head -1
# to find the last commit that touched them, in case you need to resurrect anything
```

---

## Deployment & Operations

### Backup and restore procedures
The VPS setup doc mentions how to back up `backend-data`, but doesn't cover restoring it, or backing up the Supabase database.

**Proposal:** write a `scripts/backup.sh` that:
- Exports the Supabase database (via `pg_dump` over the session pooler connection).
- Tars the `backend-data` volume.
- Uploads both to S3 or a backup service.

And a `scripts/restore.sh` that reverses it.

**Files to touch:**
- Create `scripts/backup.sh` and `scripts/restore.sh`.
- Update `deploy/VPS.md` with instructions.

---

### Monitoring and alerting
The server has basic `/api/health` and logs, but no metrics or alerts. If the engine gets stuck, you won't know until you notice tasks aren't running.

**Proposal:** add Prometheus metrics (worker uptime, task latency, queue depth, error rates) and hook them to a free monitoring service like Grafana Cloud or Datadog.

**Files to touch:**
- `server/build.gradle.kts` – add `io.prometheus:simpleclient` and `io.prometheus:simpleclient_hotspot`.
- `server/.../api/Routes.kt` – add a `GET /metrics` endpoint.
- `engine/.../Engine.kt` – instrument the worker loop and queue.
- `.github/workflows/ci.yml` – optionally alert on deploy.

---

## Documentation

### Architecture decision record (ADR) for the redesign
Document why the system was split from a Compose desktop app to a headless backend + web UI, and what trade-offs were made.

**Files to touch:**
- Create `docs/adr/0001-headless-architecture.md` with:
  - Problem statement (remote control from anywhere, not just one machine).
  - Proposed solution (Ktor backend + Svelte web UI).
  - Trade-offs (complexity vs. flexibility; state in Supabase vs. in-memory).
  - Consequences (easier to scale, harder to debug, need SSH/TLS).

---

### Rate limiting and cooldown playbook
The rate limiter and cooldown tracker are complex. Document how they work and how to tune them.

**Files to touch:**
- Create `docs/RATE_LIMITING.md` with:
  - A diagram of the time windows and bucket logic.
  - Examples of what happens when you hit the limit.
  - How to read the logs to debug rate-limit issues.
  - How to adjust `GATEWAY_RATE_LIMIT_*` env vars safely.

---

## Known Limitations & Rough Edges

- Event/raid configs don't hot-reload; you must restart the backend to pick up changes.
- The web app doesn't auto-reconnect to SSE if the connection drops; you must refresh the page.
- Bank withdrawal doesn't check if the character has space in their inventory first.
- No audit log: you can't see who made changes to the configs.

---

## Navigation

### Seasonal map refresh
Navigation reads the static `all_maps.json` (full grid, blocked tiles included) instead of calling `/maps` on every boot. At the start of each season, re-fetch it:
```bash
# pages: check "pages" in the first response; the map is ~1428 tiles = 15 pages of 100
for p in $(seq 1 15); do curl -s "https://api.artifactsmmo.com/maps?size=100&page=$p"; done \
  | jq -s '{data: (map(.data) | add)}' > all_maps.json
```
Then commit it and redeploy. Existing VPS volumes keep the old seed copy, so also run
`docker compose exec backend rm /data/all_maps.json && docker compose restart backend`.
Possible improvement: a small admin endpoint/CLI task that does this.

### Split regions from observed 595s
If a "no path" (595/596) ever happens despite a planned route, the region graph merged tiles that aren't really walkable to each other. Log the pair and teach `RegionGraph` to split there.

### Event spawn pre-warming
Record the possible spawn tiles for event monsters/resources so routes can be planned the moment an event appears.

### Bank gold in gate checks
`ActionHelper.gateSatisfiable` assumes gold gates are payable because bank gold isn't cached. Cache bank gold in `BankState` so routes avoid gold gates the account can't afford.

### Refresh achievements while running
Achievements are read once at `Engine.start()`. Tiles/gates/potions unlocked mid-session (e.g. `secure_the_island` → Sandwhisper Isle bank) aren't used until a restart. Periodically (e.g. every 30 min, or after each task-master hand-in) re-fetch achievements and, if the set changed, re-run `contentCache.preWarmMaps` and update `helper.completedAchievements`.

### Cooking-speed gear priority
Next season is expected to add gear that reduces cooking cooldowns. When it does, add a `StatGearOptimizer.Priority.COOKING` (that stat first, wisdom as tie-breaker) and route cooking tasks to it instead of the shared `CRAFTING` priority — see the comment on `StatGearOptimizer.Priority`. Also worth checking then whether the same kind of gear exists for other gathering skills.

### Combat prospecting (deferred)
`GearOptimizer.GearScore`'s tie-break is currently `prospecting + wisdom` combined; for task-master monster / fight tasks we deferred doing anything more here. Two options if revisited:
- (a) cheap: change the tie-break to prospecting only (helps only when candidates already tie on win rate and fight length);
- (b) real: after combat optimization, try swapping each slot to its highest-prospecting item and keep the swap only if a fight simulation still clears the win-rate threshold (costs extra simulation calls, cached per monster).

### Boss/raid gear: consider all characters' equipped items
`CoopOptimizer` only considers a participant's own equipped gear, inventory, and the shared bank when planning boss/raid loadouts. It doesn't consider gear currently equipped on OTHER (non-participating) characters, since using it would require unequipping that character first — out of scope for this fix. If pursued: detect "best item is equipped elsewhere" candidates, then queue an unequip + bank-deposit task for that character before the group's provisioning step runs.
