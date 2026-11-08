<!--
  Cached optimizer loadouts (known_loadouts.json): one entry per character × exact level × monster.
  The optimizer reuses an entry only after a fight simulation re-verifies it, so edits are safe;
  deleting an entry makes the next optimization search from scratch (and a running fight task
  for that character re-optimizes before its next fight).
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { catalog } from '../lib/catalog'
  import type { KnownLoadout } from '../lib/types'

  const GEAR_SLOTS = ['weapon', 'shield', 'helmet', 'body_armor', 'leg_armor', 'boots',
    'ring1', 'ring2', 'amulet', 'artifact1', 'artifact2', 'artifact3', 'rune', 'bag']
  const UTILITY_SLOTS = ['utility1', 'utility2']
  const slotType = (slot: string) => slot.replace(/\d$/, '')

  let loadouts = $state<KnownLoadout[]>([])
  let characterFilter = $state('')
  let monsterFilter = $state('')
  let latestOnly = $state(true)
  let message = $state('')
  let error = $state('')
  let itemsByType = $state<Record<string, string[]>>({})

  // Editing state: key of the row being edited plus a working copy of its slots.
  let editingKey = $state<string | null>(null)
  let draftGear = $state<Record<string, string>>({})
  let draftUtil = $state<Record<string, { code: string; quantity: number }>>({})

  const key = (l: KnownLoadout) => `${l.characterName}|${l.exactLevel}|${l.monsterCode}|${l.optimizerVersion}`

  function load() {
    api.loadouts().then((l) => (loadouts = l)).catch((e) => (error = e.message))
  }
  load()
  catalog().then((c) => {
    const m: Record<string, string[]> = {}
    for (const i of c.items) (m[i.type] ??= []).push(i.code)
    itemsByType = m
  }).catch(() => {})

  const characters = $derived([...new Set(loadouts.map((l) => l.characterName))].sort())
  const shown = $derived.by(() => {
    const m = monsterFilter.trim().toLowerCase()
    const rows = loadouts.filter((l) =>
      (!characterFilter || l.characterName === characterFilter) && (!m || l.monsterCode.includes(m)))
    if (!latestOnly) return rows
    // Highest level per character × monster (the one the optimizer will look up next).
    const best = new Map<string, KnownLoadout>()
    for (const l of rows) {
      const k = `${l.characterName}|${l.monsterCode}`
      const cur = best.get(k)
      if (!cur || l.exactLevel > cur.exactLevel) best.set(k, l)
    }
    return rows.filter((l) => best.get(`${l.characterName}|${l.monsterCode}`) === l)
  })

  function startEdit(l: KnownLoadout) {
    editingKey = key(l)
    draftGear = Object.fromEntries(GEAR_SLOTS.map((s) => [s, l.gear[s] ?? '']))
    draftUtil = Object.fromEntries(UTILITY_SLOTS.map((s) => [s, { ...(l.utilities?.[s] ?? { code: '', quantity: 0 }) }]))
  }

  async function saveEdit(l: KnownLoadout) {
    error = ''
    try {
      const gear = Object.fromEntries(Object.entries(draftGear).filter(([, v]) => v.trim()).map(([k, v]) => [k, v.trim()]))
      const utilities = Object.fromEntries(Object.entries(draftUtil).filter(([, u]) => u.code.trim() && u.quantity > 0)
        .map(([k, u]) => [k, { code: u.code.trim(), quantity: Number(u.quantity) }]))
      await api.saveLoadout({ ...l, gear, utilities })
      message = `Saved ${l.characterName} vs ${l.monsterCode} (lvl ${l.exactLevel})`
      editingKey = null
      load()
    } catch (e: any) { error = e.message }
  }

  async function remove(filter: Record<string, string | number>, label: string) {
    if (!confirm(`Delete ${label}? The optimizer will search again next time.`)) return
    error = ''
    try {
      const r = await api.deleteLoadouts(filter)
      message = `Deleted ${r.removed} loadout(s) — ${label}`
      load()
    } catch (e: any) { error = e.message }
  }

  const removeOne = (l: KnownLoadout) => remove(
    { character: l.characterName, monster: l.monsterCode, level: l.exactLevel, version: l.optimizerVersion },
    `${l.characterName} vs ${l.monsterCode} (lvl ${l.exactLevel})`)
  const removeAllLevels = (l: KnownLoadout) => remove(
    { character: l.characterName, monster: l.monsterCode },
    `all ${l.characterName} vs ${l.monsterCode} loadouts`)

  const gearSummary = (l: KnownLoadout) => GEAR_SLOTS.filter((s) => l.gear[s]).map((s) => `${s}: ${l.gear[s]}`)
  const utilSummary = (l: KnownLoadout) =>
    Object.values(l.utilities ?? {}).map((u) => `${u.quantity}× ${u.code}`).join(', ') || '—'
</script>

<div class="row" style="margin-bottom:12px">
  <label>Character
    <select bind:value={characterFilter}>
      <option value="">All</option>
      {#each characters as c}<option>{c}</option>{/each}
    </select>
  </label>
  <label>Monster <input bind:value={monsterFilter} placeholder="monster code" /></label>
  <label><input type="checkbox" bind:checked={latestOnly} /> Highest level only</label>
  <button onclick={load}>Refresh</button>
  {#if characterFilter}
    <button onclick={() => remove({ character: characterFilter }, `every loadout for ${characterFilter}`)}>
      Clear all for {characterFilter}
    </button>
  {/if}
  {#if message}<span>{message}</span>{/if}
  {#if error}<span class="error">{error}</span>{/if}
</div>

{#each Object.entries(itemsByType) as [type, codes]}
  <datalist id="items-{type}">{#each codes as c}<option value={c}></option>{/each}</datalist>
{/each}

<div class="panel">
  <table>
    <thead>
      <tr><th>Character</th><th>Monster</th><th>Lvl</th><th>Gear</th><th>Utilities</th><th>Win</th><th>Turns</th><th>Validated</th><th></th></tr>
    </thead>
    <tbody>
      {#each shown as l (key(l))}
        <tr>
          <td>{l.characterName}</td>
          <td>{l.monsterCode}</td>
          <td>{l.exactLevel}</td>
          <td class="gear">
            {#if editingKey === key(l)}
              <div class="grid">
                {#each GEAR_SLOTS as s}
                  <label>{s} <input list="items-{slotType(s)}" bind:value={draftGear[s]} /></label>
                {/each}
                {#each UTILITY_SLOTS as s}
                  <label>{s}
                    <span class="row">
                      <input list="items-utility" bind:value={draftUtil[s].code} />
                      <input type="number" min="0" style="width:70px" bind:value={draftUtil[s].quantity} />
                    </span>
                  </label>
                {/each}
              </div>
            {:else}
              {#each gearSummary(l) as g}<span class="chip">{g}</span>{/each}
            {/if}
          </td>
          <td>{utilSummary(l)}</td>
          <td>{Math.round(l.validatedWinRate * 100)}%</td>
          <td>{l.averageWinTurns?.toFixed(1) ?? '—'}</td>
          <td class="muted">{new Date(l.validatedAtEpochMs).toLocaleString()}</td>
          <td class="actions">
            {#if editingKey === key(l)}
              <button onclick={() => saveEdit(l)}>Save</button>
              <button onclick={() => (editingKey = null)}>Cancel</button>
            {:else}
              <button onclick={() => startEdit(l)}>Edit</button>
              <button onclick={() => removeOne(l)} title="Delete this level's entry">Delete</button>
              <button onclick={() => removeAllLevels(l)} title="Delete every level for this character × monster">Clear all levels</button>
            {/if}
          </td>
        </tr>
      {:else}
        <tr><td colspan="9" class="muted">No cached loadouts.</td></tr>
      {/each}
    </tbody>
  </table>
</div>

<style>
  .gear { max-width: 520px; }
  .chip { display: inline-block; margin: 1px 4px 1px 0; padding: 1px 6px; border: 1px solid var(--border); border-radius: 4px; font-size: 0.85em; }
  .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 12px; }
  .grid label { display: flex; flex-direction: column; font-size: 0.85em; }
  .actions { white-space: nowrap; }
</style>
