<!--
  Task queue table. Refetches whenever the stream reports a task change (live.taskTick).
  Clicking a row shows that task's event history.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { live } from '../lib/live.svelte'
  import { describeTask, ago, time } from '../lib/format'
  import type { QueuedTask, TaskEvent } from '../lib/types'

  const views = {
    live: 'status=pending,claimed,running,suspended',
    finished: 'status=completed,failed,cancelled&limit=100',
    all: 'limit=200',
  } as const
  let view = $state<keyof typeof views>('live')
  let tasks = $state<QueuedTask[]>([])
  let selected = $state<QueuedTask | null>(null)
  let events = $state<TaskEvent[]>([])
  let error = $state('')

  // $effect re-runs when `view` or `live.taskTick` change, because it reads both.
  $effect(() => {
    live.taskTick
    api.tasks(views[view]).then((t) => (tasks = t)).catch((e) => (error = e.message))
  })

  async function select(t: QueuedTask) {
    selected = t
    events = await api.taskEvents(t.id)
  }
</script>

<div class="row" style="margin-bottom:12px">
  {#each Object.keys(views) as v}
    <button class:primary={view === v} onclick={() => (view = v as keyof typeof views)}>{v}</button>
  {/each}
  <a href="#/new"><button>+ New task</button></a>
</div>
{#if error}<p class="error">{error}</p>{/if}

<div class="layout">
  <div class="panel">
    <table>
      <thead><tr><th>#</th><th>Task</th><th>Status</th><th>Prio</th><th>Source</th><th>Character</th><th>Updated</th><th></th></tr></thead>
      <tbody>
        {#each tasks as t (t.id)}
          <tr class:sel={selected?.id === t.id} onclick={() => select(t)}>
            <td class="muted">{t.id}</td>
            <td>{describeTask(t)}{#if t.lastError}<div class="error small">{t.lastError}</div>{/if}</td>
            <td><span class="badge {t.status}">{t.status}</span></td>
            <td>{t.priority}</td>
            <td class="muted">{t.source}</td>
            <td>{t.claimedBy ?? t.assignedCharacter ?? 'any'}</td>
            <td class="muted">{ago(t.updatedAtMillis)}</td>
            <td>
              {#if !['completed', 'failed', 'cancelled'].includes(t.status)}
                <!-- stopPropagation: don't also trigger the row's select() -->
                <button class="danger" onclick={(e) => { e.stopPropagation(); api.cancelTask(t.id) }}>Cancel</button>
              {/if}
            </td>
          </tr>
        {:else}
          <tr><td colspan="8" class="muted">No tasks.</td></tr>
        {/each}
      </tbody>
    </table>
  </div>

  {#if selected}
    <div class="panel">
      <h3>Task #{selected.id}</h3>
      <pre class="log">{JSON.stringify({ spec: selected.spec, stop: selected.stopCondition, checkpoint: selected.checkpoint }, null, 2)}</pre>
      <h4>History</h4>
      {#each events as ev}
        <div class="log"><span class="muted">{time(ev.createdAtMillis)}</span> {ev.kind} {ev.character ?? ''} {ev.message ?? ''}</div>
      {/each}
    </div>
  {/if}
</div>

<style>
  .layout { display: grid; grid-template-columns: 1fr; gap: 12px; }
  @media (min-width: 1100px) { .layout:has(> :nth-child(2)) { grid-template-columns: 2fr 1fr; } }
  tr { cursor: pointer; }
  tr.sel { background: #242a33; }
  .small { font-size: 12px; }
</style>
