<!--
  Event and raid configs, edited through validated forms (the JSON is shown read-only).
  Event fields adapt to what the event spawns: resource/monster events pick eligible
  characters, NPC events pick a trader and items, and boss-monster events (e.g.
  out_of_the_ground → duskworm) configure a co-op team instead.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { catalog } from '../lib/catalog'
  import type { Catalog, EventConfig, ItemQty, RaidConfig } from '../lib/types'

  type Kind = 'event' | 'raid'
  const blankEvent = (): EventConfig => ({
    eventCode: '', enabled: true, eligibleCharacters: [], itemsToSell: [], itemsToBuy: [],
    designatedTrader: null, minWinRate: 0.9, group: null,
  })
  const blankRaid = (): RaidConfig => ({
    raidCode: '', enabled: true, initiatorName: null, participantNames: [], tankOverride: null, leadTimeMinutes: 5,
  })

  let kind = $state<Kind>('event')
  let entries = $state<Record<string, any>>({})
  let cat = $state<Catalog | null>(null)
  let characters = $state<string[]>([])
  let ev = $state<EventConfig>(blankEvent())
  let raid = $state<RaidConfig>(blankRaid())
  // Team slots shared by the boss-event and raid forms: [initiator, participant 1, participant 2].
  let team = $state<string[]>(['', '', ''])
  let tank = $state('')
  let error = $state('')
  let message = $state('')

  catalog().then((c) => (cat = c)).catch((e) => (error = e.message))
  api.characters().then((c) => (characters = c.map((x) => x.name))).catch(() => {})

  $effect(() => { load(kind) })

  async function load(k: Kind) {
    entries = await api.configs(k).catch((e) => { error = e.message; return {} })
  }

  // ── What the selected event spawns ──
  const evMeta = $derived(cat?.events.find((e) => e.code === ev.eventCode) ?? null)
  const evMonster = $derived(
    evMeta?.contentType === 'monster' ? cat?.monsters.find((m) => m.code === evMeta.contentCode) ?? null : null,
  )
  const isBossEvent = $derived(evMonster?.type === 'boss')
  const raidMeta = $derived(cat?.raids.find((r) => r.code === raid.raidCode) ?? null)
  const members = $derived(team.filter(Boolean))
  // A tank that is no longer on the team falls back to "auto".
  $effect(() => { if (tank && !members.includes(tank)) tank = '' })

  function eventLabel(code: string): string {
    const e = cat?.events.find((x) => x.code === code)
    if (!e) return code
    const boss = e.contentType === 'monster' && cat?.monsters.find((m) => m.code === e.contentCode)?.type === 'boss'
    return `${e.name} — ${boss ? 'boss' : e.contentType} ${e.contentCode}`
  }

  function setTeam(initiator: string | null | undefined, participants: string[] | undefined, tankOverride: string | null | undefined) {
    const p = participants ?? []
    team = [initiator ?? '', p[0] ?? '', p[1] ?? '']
    tank = tankOverride ?? ''
  }

  function resetForm() {
    error = ''; message = ''
    if (kind === 'event') ev = blankEvent(); else raid = blankRaid()
    setTeam('', [], null)
  }

  function edit(value: any) {
    error = ''; message = ''
    if (kind === 'event') {
      // Spread over the blank so older documents missing newer fields still bind cleanly.
      ev = { ...blankEvent(), ...structuredClone(value) }
      setTeam(ev.group?.initiatorName, ev.group?.participantNames, ev.group?.tankOverride)
    } else {
      raid = { ...blankRaid(), ...structuredClone(value) }
      setTeam(raid.initiatorName, raid.participantNames, raid.tankOverride)
    }
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' })
  }

  function toggleEligible(name: string, on: boolean) {
    ev.eligibleCharacters = on ? [...ev.eligibleCharacters, name] : ev.eligibleCharacters.filter((n) => n !== name)
  }

  // ── Build + validate ──
  function buildEvent(): EventConfig {
    const participantNames = team.slice(1).filter(Boolean)
    // Boss events carry the team; other known events drop a stale one. If the catalog is
    // unavailable (unknown event type) keep whatever group the document already had.
    const group = isBossEvent
      ? (team[0] ? { initiatorName: team[0], participantNames, tankOverride: tank || null } : null)
      : (evMeta ? null : ev.group)
    const clean = (items: ItemQty[]) => items.filter((i) => i.code.trim()).map((i) => ({ code: i.code.trim(), quantity: Number(i.quantity) }))
    return { ...ev, minWinRate: Number(ev.minWinRate), itemsToSell: clean(ev.itemsToSell), itemsToBuy: clean(ev.itemsToBuy), group }
  }

  function buildRaid(): RaidConfig {
    return {
      ...raid, initiatorName: team[0] || null, participantNames: team.slice(1).filter(Boolean),
      tankOverride: tank || null, leadTimeMinutes: Number(raid.leadTimeMinutes),
    }
  }

  function validate(value: EventConfig | RaidConfig): string | null {
    if (!team[0] && (team[1] || team[2])) return 'Pick an initiator before participants'
    if ('eventCode' in value) {
      if (!value.eventCode) return 'Choose an event'
      if (!(value.minWinRate >= 0 && value.minWinRate <= 1)) return 'Min win rate must be between 0 and 1'
      if (isBossEvent && value.enabled && !value.group) return 'Boss events need a team (at least an initiator)'
      const items = [...value.itemsToSell, ...value.itemsToBuy]
      if (items.some((i) => !(i.quantity > 0))) return 'Item quantities must be positive'
      if (evMeta?.contentType === 'npc' && items.length > 0 && !value.designatedTrader) return 'NPC trading needs a designated trader'
    } else {
      if (!value.raidCode) return 'Choose a raid'
      if (!value.initiatorName) return 'Raids need an initiator'
      if (!(value.leadTimeMinutes >= 0)) return 'Lead time must be 0 or more minutes'
    }
    return null
  }

  const preview = $derived(kind === 'event' ? buildEvent() : buildRaid())

  async function save() {
    error = ''; message = ''
    const value = kind === 'event' ? buildEvent() : buildRaid()
    const problem = validate(value)
    if (problem) { error = problem; return }
    const key = 'eventCode' in value ? value.eventCode : value.raidCode
    try {
      await api.saveConfig(kind, key, value)
      message = `Saved ${key}`
      await load(kind)
    } catch (e: any) { error = e.message }
  }

  async function remove(key: string) {
    await api.deleteConfig(kind, key)
    await load(kind)
  }

  function summary(value: any): string {
    if (kind === 'raid') {
      const names = [value.initiatorName, ...(value.participantNames ?? [])].filter(Boolean)
      return `Team: ${names.join(', ') || '—'} · lead ${value.leadTimeMinutes ?? 5}m`
    }
    if (value.group) {
      const names = [value.group.initiatorName, ...(value.group.participantNames ?? [])]
      return `${eventLabel(value.eventCode)} · team: ${names.join(', ')}`
    }
    const who = value.designatedTrader ? `trader: ${value.designatedTrader}`
      : value.eligibleCharacters?.length ? value.eligibleCharacters.join(', ') : 'all characters'
    return `${eventLabel(value.eventCode)} · ${who}`
  }
</script>

<div class="row" style="margin-bottom:12px">
  <button class:primary={kind === 'event'} onclick={() => { kind = 'event'; resetForm() }}>Events</button>
  <button class:primary={kind === 'raid'} onclick={() => { kind = 'raid'; resetForm() }}>Raids</button>
</div>

<div class="grid">
  {#each Object.entries(entries) as [key, value]}
    <div class="panel">
      <div class="row" style="justify-content:space-between">
        <strong>{key}</strong>
        <span class="badge {value.enabled ? 'running' : 'cancelled'}">{value.enabled ? 'enabled' : 'disabled'}</span>
      </div>
      <p class="muted">{summary(value)}</p>
      <div class="row">
        <button onclick={() => edit(value)}>Edit</button>
        <button class="danger" onclick={() => remove(key)}>Delete</button>
      </div>
    </div>
  {/each}
</div>

{#snippet teamPicker()}
  <div class="row">
    {#each team as _, i}
      <label>{i === 0 ? 'Initiator' : `Participant ${i}`}
        <select bind:value={team[i]}>
          <option value="">—</option>
          {#each characters as c}<option value={c} disabled={team.includes(c) && team[i] !== c}>{c}</option>{/each}
        </select>
      </label>
    {/each}
    <label>Tank
      <select bind:value={tank}>
        <option value="">Auto (optimizer picks)</option>
        {#each members as c}<option value={c}>{c}</option>{/each}
      </select>
    </label>
  </div>
{/snippet}

{#snippet itemRows(items: ItemQty[], title: string)}
  <div class="stack">
    <span class="muted small">{title}</span>
    {#each items as item, i}
      <div class="row">
        <input list="cfg-items" placeholder="item code" bind:value={item.code} />
        <input type="number" min="1" style="width:90px" bind:value={item.quantity} />
        <button class="danger" onclick={() => items.splice(i, 1)}>✕</button>
      </div>
    {/each}
    <div><button onclick={() => items.push({ code: '', quantity: 1 })}>+ Add item</button></div>
  </div>
{/snippet}

<div class="panel stack" style="margin-top:12px">
  <div class="row" style="justify-content:space-between">
    <h4 style="margin:0">Add / update {kind} config</h4>
    <button onclick={resetForm}>New</button>
  </div>

  {#if kind === 'event'}
    <div class="row">
      <label>Event
        {#if cat?.events.length}
          <select bind:value={ev.eventCode}>
            <option value="">— choose —</option>
            {#if ev.eventCode && !evMeta}<option value={ev.eventCode}>{ev.eventCode}</option>{/if}
            {#each cat.events as e}<option value={e.code}>{eventLabel(e.code)}</option>{/each}
          </select>
        {:else}
          <input bind:value={ev.eventCode} placeholder="event code" />
        {/if}
      </label>
      <label class="check"><input type="checkbox" bind:checked={ev.enabled} /> Enabled</label>
      {#if !evMeta || evMeta.contentType === 'monster'}
        <label>Min win rate (0–1)
          <input type="number" min="0" max="1" step="0.05" style="width:90px" bind:value={ev.minWinRate} />
        </label>
      {/if}
    </div>

    {#if isBossEvent}
      <p class="muted small">
        {evMonster?.name} is a boss: it's fought by this team as one co-op group (initiator fires the
        fight with the participants). Without a team the event is skipped.
      </p>
      {@render teamPicker()}
    {:else if evMeta?.contentType === 'npc'}
      <div class="row">
        <label>Designated trader
          <select bind:value={ev.designatedTrader}>
            <option value={null}>— none (skip) —</option>
            {#each characters as c}<option value={c}>{c}</option>{/each}
          </select>
        </label>
      </div>
      <div class="row" style="align-items:start">
        {@render itemRows(ev.itemsToSell, 'Sell to NPC')}
        {@render itemRows(ev.itemsToBuy, 'Buy from NPC')}
      </div>
    {:else}
      <div class="stack">
        <span class="muted small">Eligible characters (none checked = all characters)</span>
        <div class="row">
          {#each characters as c}
            <label class="check">
              <input type="checkbox" checked={ev.eligibleCharacters.includes(c)}
                onchange={(e) => toggleEligible(c, e.currentTarget.checked)} /> {c}
            </label>
          {/each}
        </div>
      </div>
    {/if}
  {:else}
    <div class="row">
      <label>Raid
        {#if cat?.raids.length}
          <select bind:value={raid.raidCode}>
            <option value="">— choose —</option>
            {#if raid.raidCode && !raidMeta}<option value={raid.raidCode}>{raid.raidCode}</option>{/if}
            {#each cat.raids as r}<option value={r.code}>{r.name} — {r.monster}</option>{/each}
          </select>
        {:else}
          <input bind:value={raid.raidCode} placeholder="raid code" />
        {/if}
      </label>
      <label class="check"><input type="checkbox" bind:checked={raid.enabled} /> Enabled</label>
      <label>Lead time (minutes)
        <input type="number" min="0" style="width:90px" bind:value={raid.leadTimeMinutes} />
      </label>
    </div>
    {@render teamPicker()}
  {/if}

  <details>
    <summary class="muted small">JSON preview</summary>
    <pre class="log">{JSON.stringify(preview, null, 2)}</pre>
  </details>

  <div class="row">
    <button class="primary" onclick={save}>Save</button>
    {#if error}<span class="error">{error}</span>{/if}
    {#if message}<span class="muted">{message}</span>{/if}
  </div>
</div>

<datalist id="cfg-items">
  {#each cat?.items ?? [] as i}<option value={i.code}>{i.name}</option>{/each}
</datalist>

<style>
  .stack { display: flex; flex-direction: column; gap: 8px; }
  .small { font-size: 12px; }
  .check { flex-direction: row; align-items: center; gap: 6px; }
</style>
