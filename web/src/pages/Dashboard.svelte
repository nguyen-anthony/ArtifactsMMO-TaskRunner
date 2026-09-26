<!--
  Dashboard: one card per character, updated live from the SSE stream (live.workers).
  The character list itself (and settings) is fetched once; statuses come from the stream.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { live } from '../lib/live.svelte'
  import { ago } from '../lib/format'
  import type { CharacterDto } from '../lib/types'

  let characters = $state<CharacterDto[]>([])
  let error = $state('')

  api.characters().then((cs) => {
    characters = cs
    // Seed statuses for characters the stream hasn't reported yet.
    for (const c of cs) if (c.status && !live.workers[c.name]) live.workers[c.name] = c.status
  }).catch((e) => (error = e.message))

  // Re-render "x seconds ago" labels every few seconds.
  let now = $state(Date.now())
  $effect(() => {
    const t = setInterval(() => (now = Date.now()), 5000)
    return () => clearInterval(t) // cleanup when the component is removed
  })

  async function cancel(taskId: number) { await api.cancelTask(taskId) }
</script>

{#if error}<p class="error">{error}</p>{/if}

<div class="grid">
  {#each characters as c (c.name)}
    <!-- `(c.name)` is a key: Svelte reuses the same card when the list order changes -->
    {@const s = live.workers[c.name]}
    <div class="panel card">
      <div class="head">
        <strong>{c.name}</strong>
        <span class="badge {s?.state}">{s?.state ?? 'unknown'}</span>
      </div>
      {#if s?.taskId}
        <div>{s.taskType} <span class="muted">#{s.taskId}</span></div>
      {/if}
      <div class="msg">{s?.message ?? '—'}</div>
      {#if s?.taskId}
        <div class="muted small">
          gathers {s.progress.gathers} · wins {s.progress.fightsWon} · losses {s.progress.fightsLost}
          · crafted {s.progress.crafted} · bank trips {s.progress.bankTrips}
        </div>
      {/if}
      {#if s?.lastError}<div class="error small">{s.lastError}</div>{/if}
      <div class="foot">
        <span class="muted small">{s ? ago(s.updatedAtMillis, now) : ''}</span>
        {#if s?.taskId}<button class="danger" onclick={() => cancel(s.taskId!)}>Cancel task</button>{/if}
      </div>
    </div>
  {/each}
</div>

<style>
  .card { display: flex; flex-direction: column; gap: 6px; }
  .head, .foot { display: flex; justify-content: space-between; align-items: center; }
  .msg { min-height: 2.5em; }
  .small { font-size: 12px; }
</style>
