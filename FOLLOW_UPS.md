I'l# Follow-ups

These are features, polish and technical improvements that fell outside the 9 milestones but are worth doing next.

## UI / UX

### Event and raid config editor
Currently, configs are edited as raw JSON in the web app. This is error-prone and unfriendly.

**Proposal:** build a form-based editor in the web UI that validates and generates the JSON, so users don't touch it directly. The backend already has the data model in `domain/TaskSpec.kt`; mirror that into Svelte components.

**Files to touch:**
- `web/src/pages/Configs.svelte` – replace the textarea with a structured form.
- `web/src/lib/types.ts` – ensure the TypeScript shapes match the backend's JSON schema.
- `server/.../TaskSpec.kt` – document the valid ranges and constraints.

---

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

### Boss group timeout
If a boss group is set up with 3 members but only 2 show up, the group's leader (the initiator) waits forever for the third slot to fill.

**Proposal:** add a `timeout_seconds` field to the group spec. If the group isn't full after that time, start the fight with the members on hand, or cancel and return them to idle. The UI should show a countdown.

**Files to touch:**
- `domain/TaskSpec.kt` – add `timeoutSeconds` to `GroupTaskSpec`.
- `engine/.../GroupCoordinator.kt` – implement the countdown and action.
- `web/src/pages/NewTask.svelte` – let users set the timeout when creating a group.
- `web/src/pages/Dashboard.svelte` – show the remaining time on the task card.

---

## Technical Debt

### Reduce Postgres advisory lock lease time
The engine holds an advisory lock to ensure only one backend runs workers. On boot, it releases any old lock after a long timeout (currently ~15 seconds). If the backend crashes, workers are stuck for that duration.

**Proposal:** reduce the timeout (e.g. to 5 seconds) so the next backend starts faster. Or use a heartbeat: the running backend periodically renews the lock while it's alive, and a dead backend's lock expires naturally.

**Files to touch:**
- `server/.../db/InstanceLock.kt` – change the timeout or add heartbeat logic.
- `server/.../Backend.kt` – if using a heartbeat, schedule a renewal task.

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
