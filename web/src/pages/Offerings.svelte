<!--
  Season 9 Seasonal Altar. Today's rotation x character grid: see who has accepted/completed
  which offering, preview feasibility, and assign a specific offering to a character (a
  `daily_offering` task with `offeringId`). Also holds the automatic daily-run settings
  (config kind `offering`, one document per character).
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { describeSpec } from '../lib/format'
  import {
    OFFERING_CATEGORIES, type CharacterDto, type OfferingConfig, type OfferingOverview,
    type OfferingPreview, type QueuedTask, type SeasonalOffering,
  } from '../lib/types'

  type Row = OfferingOverview['characters'][number]

  let overview = $state<OfferingOverview | null>(null)
  let characters = $state<CharacterDto[]>([])
  let autoConfigs = $state<Record<string, OfferingConfig>>({})
  let tasks = $state<QueuedTask[]>([])
  let previews = $state<Record<string, OfferingPreview | 'loading'>>({})
  let only = $state('')
  let priority = $state(70)
  let loading = $state(false)
  let error = $state('')
  let notice = $state('')

  const LIVE = ['pending', 'claimed', 'running', 'suspended']
  const key = (character: string, offeringId: string) => `${character}|${offeringId}`
  const rotationStart = $derived(overview ? Date.parse(overview.expiresAt) - 86_400_000 : 0)
  const shown = $derived((overview?.characters ?? []).filter((c) => !only || c.name === only))
  const todaysTasks = $derived(tasks.filter((t) => t.type === 'daily_offering' && t.createdAtMillis >= rotationStart))

  async function load() {
    loading = true
    error = ''
    try {
      const [o, c, a, t] = await Promise.all([
        api.offerings(),
        api.characters(),
        api.configs('offering'),
        api.tasks('status=pending,claimed,running,suspended,completed,failed,cancelled&limit=300'),
      ])
      overview = o; characters = c; autoConfigs = a; tasks = t
    } catch (e: any) { error = e.message } finally { loading = false }
  }
  load()

  type Cell =
    | { kind: 'error' }
    | { kind: 'done'; xp: number }
    | { kind: 'done-other'; other: string }
    | { kind: 'accepted' }
    | { kind: 'blocked'; other: string }
    | { kind: 'open'; level: number }

  function cell(row: Row, o: SeasonalOffering): Cell {
    if (row.error) return { kind: 'error' }
    const sel = row.selected.find((s) => s.category === o.category)
    if (sel?.status === 'completed' || row.completedCategories.includes(o.category)) {
      return sel?.offeringId === o.id ? { kind: 'done', xp: sel.rewardXp } : { kind: 'done-other', other: sel?.offeringId ?? '?' }
    }
    if (sel) return sel.offeringId === o.id ? { kind: 'accepted' } : { kind: 'blocked', other: sel.offeringId }
    return { kind: 'open', level: row.levels[o.category] ?? 0 }
  }

  function liveTask(character: string, offeringId: string) {
    return tasks.find((t) => LIVE.includes(t.status) && t.assignedCharacter === character
      && t.spec.kind === 'daily_offering' && t.spec.offeringId === offeringId)
  }

  async function preview(character: string, offeringId: string) {
    previews[key(character, offeringId)] = 'loading'
    try {
      previews[key(character, offeringId)] = await api.offeringPreview(character, offeringId)
    } catch (e: any) {
      delete previews[key(character, offeringId)]
      error = e.message
    }
  }

  async function assign(character: string, o: SeasonalOffering) {
    if (!Number.isInteger(priority) || priority < 1 || priority > 100) {
      error = 'Priority must be an integer from 1 to 100'
      return
    }
    try {
      await api.createTask({
        spec: { kind: 'daily_offering', offeringId: o.id }, assignedCharacter: character, priority,
        dedupeKey: `offering:${character}:${o.id}`, expiresAtMillis: Date.parse(overview!.expiresAt),
      })
      notice = `Assigned ${o.id} to ${character}`
      error = ''
      tasks = await api.tasks('status=pending,claimed,running,suspended,completed,failed,cancelled&limit=300')
    } catch (e: any) { error = e.message }
  }

  function eta(seconds?: number | null) {
    if (seconds == null) return ''
    return seconds < 3600 ? `~${Math.ceil(seconds / 60)} min` : `~${(seconds / 3600).toFixed(1)} h`
  }

  // ── Automatic daily run settings ──
  const auto = (name: string): OfferingConfig => autoConfigs[name] ?? { character: name }

  async function saveAuto(name: string, patch: Partial<OfferingConfig>) {
    const next = { enabled: true, categories: [], priority: 70, ...auto(name), ...patch, character: name }
    if (!Number.isInteger(next.priority) || next.priority < 1 || next.priority > 100) {
      error = 'Offering priority must be an integer from 1 to 100'
      return
    }
    try {
      await api.saveConfig('offering', name, next)
      error = ''
      autoConfigs = await api.configs('offering')
    } catch (e: any) { error = e.message }
  }

  function toggleCategory(name: string, category: string, on: boolean) {
    const current = auto(name).categories ?? []
    saveAuto(name, { categories: on ? [...current, category] : current.filter((c) => c !== category) })
  }
</script>

{#if error}<p class="error">{error}</p>{/if}
{#if notice}<p class="muted">{notice}</p>{/if}

<div class="panel">
  <div class="row" style="align-items:center">
    <h3 style="margin:0">Seasonal Altar{overview ? ` — ${overview.date}` : ''}</h3>
    {#if overview}<span class="muted">rotates {new Date(overview.expiresAt).toLocaleString()} · reward {overview.xpRewardPercent}% of level XP</span>{/if}
    <label>Character
      <select bind:value={only}>
        <option value="">All</option>
        {#each overview?.characters ?? [] as c}<option value={c.name}>{c.name}</option>{/each}
      </select>
    </label>
    <label>Priority <input type="number" min="1" max="100" style="width:4em" bind:value={priority} /></label>
    <button onclick={load} disabled={loading}>{loading ? 'Loading…' : 'Refresh'}</button>
  </div>

  {#if overview}
    <table style="margin-top:8px">
      <thead>
        <tr>
          <th>Category</th><th>Bracket</th><th>Requirement</th><th>Favor</th>
          {#each shown as c}<th>{c.name}</th>{/each}
        </tr>
      </thead>
      <tbody>
        {#each overview.offerings as o (o.id)}
          <tr>
            <td>{o.category}</td>
            <td>{o.level_min}–{o.level_max}</td>
            <td>{o.requirement.quantity}× {o.requirement.code}</td>
            <td class="muted">{o.favor.target_type} {o.favor.target_code}</td>
            {#each shown as c}
              {@const s = cell(c, o)}
              {@const live = liveTask(c.name, o.id)}
              {@const p = previews[key(c.name, o.id)]}
              <td>
                {#if s.kind === 'error'}<span class="muted" title={c.error ?? ''}>—</span>
                {:else if s.kind === 'done'}✓ done (+{s.xp} XP)
                {:else if s.kind === 'done-other'}<span class="muted" title={s.other}>category done</span>
                {:else if s.kind === 'blocked'}<span class="muted" title="Only one offering per category per day">blocked ({s.other})</span>
                {:else}
                  {#if s.kind === 'accepted'}<b>accepted</b>{:else}<span class="muted">lv {s.level}</span>{/if}
                  {#if live}
                    <span title={describeSpec(live.spec)}>· task #{live.id} {live.status}</span>
                  {:else}
                    <button onclick={() => preview(c.name, o.id)} disabled={p === 'loading'}>Check</button>
                    <button class="primary" onclick={() => assign(c.name, o)}>Assign</button>
                  {/if}
                  {#if p === 'loading'}<div class="muted">checking…</div>
                  {:else if p}
                    <div class={p.kind === 'infeasible' ? 'error' : 'muted'}>
                      {#if p.kind === 'ready'}items on hand
                      {:else if p.kind === 'next'}{p.nextStep} {eta(p.estimatedSeconds)}
                      {:else}{p.reason}{/if}
                    </div>
                  {/if}
                {/if}
              </td>
            {/each}
          </tr>
        {/each}
      </tbody>
    </table>
    {#each shown.filter((c) => c.error) as c}<p class="error">{c.name}: {c.error}</p>{/each}
  {/if}
</div>

<div class="panel" style="margin-top:12px">
  <h3>Today's offering tasks</h3>
  {#if todaysTasks.length === 0}<p class="muted">None yet.</p>{/if}
  <table>
    <tbody>
      {#each todaysTasks as t (t.id)}
        <tr>
          <td>#{t.id}</td><td>{t.assignedCharacter ?? '—'}</td><td>{describeSpec(t.spec)}</td>
          <td>{t.status}</td><td class="muted">{t.lastError ?? ''}</td>
        </tr>
      {/each}
    </tbody>
  </table>
</div>

<div class="panel" style="margin-top:12px">
  <h3>Automatic daily run</h3>
  <p class="muted">Queued at 00:00 UTC (and on startup). No categories checked = all of today's.</p>
  <table>
    <thead><tr><th>Character</th><th>Enabled</th><th>Priority</th><th>Categories</th></tr></thead>
    <tbody>
      {#each characters as c (c.name)}
        {@const a = auto(c.name)}
        <tr>
          <td>{c.name}</td>
          <td><input type="checkbox" checked={a.enabled ?? true} onchange={(e) => saveAuto(c.name, { enabled: e.currentTarget.checked })} /></td>
          <td><input type="number" min="1" max="100" style="width:4em" value={a.priority ?? 70}
            onchange={(e) => saveAuto(c.name, { priority: Number(e.currentTarget.value) })} /></td>
          <td>
            {#each OFFERING_CATEGORIES as cat}
              <label style="margin-right:6px"><input type="checkbox" checked={(a.categories ?? []).includes(cat)}
                onchange={(e) => toggleCategory(c.name, cat, e.currentTarget.checked)} />{cat}</label>
            {/each}
          </td>
        </tr>
      {/each}
    </tbody>
  </table>
</div>
