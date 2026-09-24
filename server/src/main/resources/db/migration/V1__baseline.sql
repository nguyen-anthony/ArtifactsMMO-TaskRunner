-- Baseline schema for the task queue. Flyway runs this inside the configured schema
-- (default `taskrunner`), so table names are unqualified.

-- ── Tasks ───────────────────────────────────────────────────────────────────
create table tasks (
    id                  bigint generated always as identity primary key,
    type                text        not null,               -- TaskSpec discriminator, e.g. 'gather'
    spec                jsonb       not null,               -- serialized TaskSpec
    priority            int         not null default 50,    -- higher runs first
    source              text        not null default 'manual'
                        check (source in ('raid','event','manual','schedule','filler','system')),
    status              text        not null default 'pending'
                        check (status in ('pending','claimed','running','suspended','completed','failed','cancelled')),

    assigned_character  text,                               -- null = any eligible character
    requirements        jsonb       not null default '{}'::jsonb,
    stop_condition      jsonb,                              -- null = run until cancelled

    -- Multi-character groups (boss fight / raid). A parent row has group_role='group' and
    -- group_id = its own id; slot rows ('initiator' / 'participant') point at the parent.
    group_id            bigint references tasks(id) on delete cascade,
    group_role          text        not null default 'solo'
                        check (group_role in ('solo','group','initiator','participant')),

    claimed_by          text,
    claimed_at          timestamptz,
    heartbeat_at        timestamptz,                        -- lease; stale => recovered on boot
    checkpoint          jsonb,                              -- executor progress for resume

    dedupe_key          text,                               -- e.g. 'raid:<code>:<start>'
    not_before          timestamptz,
    expires_at          timestamptz,

    attempts            int         not null default 0,
    last_error          text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    finished_at         timestamptz
);

-- Fast claim path: only claimable rows are indexed.
create index tasks_claimable_idx on tasks (priority desc, created_at)
    where status in ('pending', 'suspended') and group_role <> 'group';
create index tasks_claimed_by_idx on tasks (claimed_by) where status in ('claimed', 'running');
create index tasks_group_idx on tasks (group_id) where group_id is not null;
-- A dedupe key only blocks duplicates while the task is live.
create unique index tasks_dedupe_live_idx on tasks (dedupe_key)
    where dedupe_key is not null and status not in ('completed', 'failed', 'cancelled');

-- ── Task history (append-only) ──────────────────────────────────────────────
create table task_events (
    id          bigint generated always as identity primary key,
    task_id     bigint      not null references tasks(id) on delete cascade,
    character   text,
    kind        text        not null,   -- created|claimed|started|progress|suspended|resumed|error|completed|failed|cancelled
    message     text,
    data        jsonb,
    created_at  timestamptz not null default now()
);
create index task_events_task_idx on task_events (task_id, created_at);
create index task_events_created_idx on task_events (created_at);

-- ── Per-character settings ──────────────────────────────────────────────────
create table characters (
    name            text primary key,
    enabled         boolean     not null default true,   -- false = worker does not claim tasks
    allowed_types   text[],                              -- null = all task types
    filler_type     text,                                -- default task when the queue has nothing
    filler_spec     jsonb,
    updated_at      timestamptz not null default now()
);

-- ── Recurring / timed task templates ────────────────────────────────────────
create table schedules (
    id                  bigint generated always as identity primary key,
    name                text        not null,
    enabled             boolean     not null default true,
    cron                text,                             -- one of cron / interval_seconds
    interval_seconds    int,
    task_type           text        not null,
    task_spec           jsonb       not null,
    priority            int         not null default 30,
    assigned_character  text,
    requirements        jsonb       not null default '{}'::jsonb,
    last_run_at         timestamptz,
    next_run_at         timestamptz,
    created_at          timestamptz not null default now(),
    check (cron is not null or interval_seconds is not null)
);

-- ── Config documents (replace event_config.json, raid_config.json, etc.) ────
-- kind: 'event' | 'raid' | 'known_loadout' | 'monster_profile' | 'teleport_potion'
create table config_entries (
    kind        text        not null,
    key         text        not null,
    value       jsonb       not null,
    updated_at  timestamptz not null default now(),
    primary key (kind, key)
);

-- ── Wake idle workers immediately when claimable work appears ───────────────
create function notify_task_changed() returns trigger language plpgsql as $$
begin
    perform pg_notify('task_changed', json_build_object(
        'id', new.id, 'status', new.status, 'assigned', new.assigned_character,
        'priority', new.priority)::text);
    return new;
end $$;

create trigger tasks_notify
    after insert or update of status, priority, assigned_character on tasks
    for each row execute function notify_task_changed();

create function touch_updated_at() returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end $$;

create trigger tasks_touch before update on tasks
    for each row execute function touch_updated_at();
