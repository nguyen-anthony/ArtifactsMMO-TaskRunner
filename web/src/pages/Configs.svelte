<!--
  Event and raid configs, edited through validated forms (the JSON is shown read-only).
  Event fields adapt to what the event spawns: resource/monster events pick eligible
  characters, NPC events pick a trader and items, and boss-monster events (e.g.
  out_of_the_ground → duskworm) and raids configure one or more co-op parties instead.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { catalog } from '../lib/catalog'
  import type { Catalog, EventConfig, EventGroupConfig, ItemQty, NpcItem, RaidConfig } from '../lib/types'

  type Kind = 'event' | 'raid'
  /** Form state for one party: slots = [initiator, participant 1, participant 2]. */
  type Party = { slots: string[]; tank: string; priority: number | null }
  const EVENT_PRIORITY = 80
  const RAID_PRIORITY = 90
  const blankEvent = (): EventConfig => ({
    eventCode: '', enabled: true, eligibleCharacters: [], itemsToSell: [], itemsToBuy: [],
    designatedTrader: null, minWinRate: 0.9, groups: [], group: null, priority: null, characterPriorities: {},
  })
  const blankRaid = (): RaidConfig => ({
    raidCode: '', enabled: true, leadTimeMinutes: 5, parties: [], minWinRate: 0,
    initiatorName: null, participantNames: [], tankOverride: null,
  })
  const blankParty = (): Party => ({ slots: ['', '', ''], tank: '', priority: null })
  /** Blank/invalid number inputs → null (= use the default). */
  const num = (v: unknown): number | null => (v === '' || v == null || Number.isNaN(Number(v)) ? null : Number(v))
  const validPriority = (p: number | null | undefined) => p == null || (Number.isInteger(p) && p >= 1 && p <= 100)

  let kind = $state<Kind>('event')
  let entries = $state<Record<string, any>>({})
  let cat = $state<Catalog | null>(null)
  let characters = $state<string[]>([])
  let ev = $state<EventConfig>(blankEvent())
  let raid = $state<RaidConfig>(blankRaid())
  // Parties shared by the boss-event and raid forms. Order = bank priority.
  let parties = $state<Party[]>([blankParty()])
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

  // ── What the event's NPC trades (null = not loaded / unavailable → free-form codes) ──
  let npcItems = $state<NpcItem[] | null>(null)
  let npcError = $state('')
  $effect(() => {
    const npc = evMeta?.contentType === 'npc' ? evMeta.contentCode : null
    npcItems = null; npcError = ''
    if (!npc) return
    api.npcItems(npc)
      .then((items) => { if (evMeta?.contentCode === npc) npcItems = items })
      .catch((e) => (npcError = `Couldn't load what ${npc} trades: ${e.message}`))
  })
  const sellable = $derived(new Map((npcItems ?? []).filter((i) => i.sellPrice != null).map((i) => [i.code, i])))
  const buyable = $derived(new Map((npcItems ?? []).filter((i) => i.buyPrice != null).map((i) => [i.code, i])))
  function itemName(code: string): string { return cat?.items.find((i) => i.code === code)?.name ?? code }
  /** Price hint for a row, or a warning when the NPC doesn't trade that item that way. */
  function tradeHint(code: string, mode: 'sell' | 'buy'): { text: string; bad: boolean } | null {
    if (!npcItems || !code.trim()) return null
    const entry = (mode === 'sell' ? sellable : buyable).get(code.trim())
    if (!entry) return { text: `NPC doesn't ${mode === 'sell' ? 'buy' : 'sell'} this`, bad: true }
    return { text: `${mode === 'sell' ? entry.sellPrice : entry.buyPrice} ${entry.currency} each`, bad: false }
  }
  // A tank that is no longer in its party falls back to "auto".
  $effect(() => {
    for (const p of parties) if (p.tank && !p.slots.includes(p.tank)) p.tank = ''
  })

  function eventLabel(code: string): string {
    const e = cat?.events.find((x) => x.code === code)
    if (!e) return code
    const boss = e.contentType === 'monster' && cat?.monsters.find((m) => m.code === e.contentCode)?.type === 'boss'
    return `${e.name} — ${boss ? 'boss' : e.contentType} ${e.contentCode}`
  }

  /** Saved party lists, falling back to the legacy single-party fields of older documents. */
  function eventParties(value: any): EventGroupConfig[] {
    return value.groups?.length ? value.groups : value.group ? [value.group] : []
  }
  function raidParties(value: any): EventGroupConfig[] {
    if (value.parties?.length) return value.parties
    return value.initiatorName
      ? [{ initiatorName: value.initiatorName, participantNames: value.participantNames ?? [], tankOverride: value.tankOverride ?? null }]
      : []
  }

  function setParties(groups: EventGroupConfig[]) {
    parties = groups.length
      ? groups.map((g) => ({
          slots: [g.initiatorName, g.participantNames[0] ?? '', g.participantNames[1] ?? ''],
          tank: g.tankOverride ?? '',
          priority: g.priority ?? null,
        }))
      : [blankParty()]
  }

  /** Filled parties (blank ones are ignored) in config shape. */
  function builtParties(): EventGroupConfig[] {
    return parties
      .filter((p) => p.slots.some(Boolean))
      .map((p) => ({
        initiatorName: p.slots[0], participantNames: p.slots.slice(1).filter(Boolean), tankOverride: p.tank || null,
        priority: num(p.priority),
      }))
  }

  /** Default a party falls back to when its priority is blank. */
  const partyDefault = $derived(kind === 'raid' ? RAID_PRIORITY : (num(ev.priority) ?? EVENT_PRIORITY))
  /** Characters whose solo tasks can take a per-character priority (none checked = all). */
  const prioritizable = $derived(ev.eligibleCharacters.length ? ev.eligibleCharacters : characters)

  function setCharPriority(name: string, raw: string) {
    const { [name]: _, ...rest } = ev.characterPriorities
    const p = num(raw)
    ev.characterPriorities = p == null ? rest : { ...rest, [name]: p }
  }

  function resetForm() {
    error = ''; message = ''
    if (kind === 'event') ev = blankEvent(); else raid = blankRaid()
    setParties([])
  }

  function edit(value: any) {
    error = ''; message = ''
    if (kind === 'event') {
      // Spread over the blank so older documents missing newer fields still bind cleanly.
      ev = { ...blankEvent(), ...$state.snapshot(value) }
      setParties(eventParties(ev))
    } else {
      raid = { ...blankRaid(), ...$state.snapshot(value) }
      setParties(raidParties(raid))
    }
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' })
  }

  /** Characters already picked in any slot other than [party pi, slot si]. */
  function takenElsewhere(pi: number, si: number): Set<string> {
    const taken = new Set<string>()
    parties.forEach((p, i) => p.slots.forEach((c, j) => { if (c && !(i === pi && j === si)) taken.add(c) }))
    return taken
  }

  function toggleEligible(name: string, on: boolean) {
    ev.eligibleCharacters = on ? [...ev.eligibleCharacters, name] : ev.eligibleCharacters.filter((n) => n !== name)
  }

  // ── Build + validate ──
  function buildEvent(): EventConfig {
    // Boss events carry the parties; other known events drop stale ones. If the catalog is
    // unavailable (unknown event type) keep whatever parties the document already had.
    const groups = isBossEvent ? builtParties() : (evMeta ? [] : eventParties(ev))
    const clean = (items: ItemQty[]) => items.filter((i) => i.code.trim()).map((i) => ({ code: i.code.trim(), quantity: Number(i.quantity) }))
    // Per-character overrides only apply to solo resource/monster events, and only for eligible characters.
    const soloEvent = !isBossEvent && evMeta?.contentType !== 'npc'
    const characterPriorities = soloEvent
      ? Object.fromEntries(Object.entries(ev.characterPriorities ?? {}).filter(([c]) => !ev.eligibleCharacters.length || ev.eligibleCharacters.includes(c)))
      : {}
    return {
      ...ev, minWinRate: Number(ev.minWinRate), itemsToSell: clean(ev.itemsToSell), itemsToBuy: clean(ev.itemsToBuy), groups, group: null,
      priority: num(ev.priority), characterPriorities,
    }
  }

  function buildRaid(): RaidConfig {
    return {
      ...raid, parties: builtParties(), minWinRate: Number(raid.minWinRate), leadTimeMinutes: Number(raid.leadTimeMinutes),
      initiatorName: null, participantNames: [], tankOverride: null,
    }
  }

  function validate(value: EventConfig | RaidConfig): string | null {
    if (parties.some((p) => !p.slots[0] && (p.slots[1] || p.slots[2]))) return 'Every party needs an initiator before participants'
    const groupList = 'eventCode' in value ? value.groups : value.parties
    if (!groupList.every((g) => validPriority(g.priority))) return 'Party priorities must be whole numbers from 1 to 100'
    if ('eventCode' in value) {
      if (!value.eventCode) return 'Choose an event'
      if (!validPriority(value.priority)) return 'Event priority must be a whole number from 1 to 100'
      if (!Object.values(value.characterPriorities).every(validPriority)) return 'Character priorities must be whole numbers from 1 to 100'
      if (!(value.minWinRate >= 0 && value.minWinRate <= 1)) return 'Min win rate must be between 0 and 1'
      if (isBossEvent && value.enabled && !value.groups.length) return 'Boss events need at least one party (an initiator)'
      if (value.itemsToSell.some((i) => !(i.quantity >= 0))) return 'Sell "keep" amounts must be 0 or more'
      if (value.itemsToBuy.some((i) => !(i.quantity > 0))) return 'Buy quantities must be positive'
      const items = [...value.itemsToSell, ...value.itemsToBuy]
      if (evMeta?.contentType === 'npc') {
        const badSell = value.itemsToSell.filter((i) => tradeHint(i.code, 'sell')?.bad).map((i) => i.code)
        const badBuy = value.itemsToBuy.filter((i) => tradeHint(i.code, 'buy')?.bad).map((i) => i.code)
        if (badSell.length) return `The NPC doesn't buy: ${badSell.join(', ')}`
        if (badBuy.length) return `The NPC doesn't sell: ${badBuy.join(', ')}`
      }
      if (evMeta?.contentType === 'npc' && items.length > 0 && !value.designatedTrader) return 'NPC trading needs a designated trader'
    } else {
      if (!value.raidCode) return 'Choose a raid'
      if (!value.parties.length) return 'Raids need at least one party (an initiator)'
      if (!(value.minWinRate >= 0 && value.minWinRate <= 1)) return 'Min win rate must be between 0 and 1'
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

  function partiesLabel(groups: EventGroupConfig[]): string {
    return groups.map((g) => [g.initiatorName, ...(g.participantNames ?? [])].join(', ') + (g.priority != null ? ` (p${g.priority})` : '')).join(' | ')
  }

  function summary(value: any): string {
    if (kind === 'raid') {
      const groups = raidParties(value)
      return `${groups.length > 1 ? 'Parties' : 'Team'}: ${partiesLabel(groups) || '—'} · lead ${value.leadTimeMinutes ?? 5}m`
    }
    const prio = `priority ${value.priority ?? EVENT_PRIORITY}`
    const groups = eventParties(value)
    if (groups.length) {
      return `${eventLabel(value.eventCode)} · ${prio} · ${groups.length > 1 ? 'parties' : 'team'}: ${partiesLabel(groups)}`
    }
    const overrides = Object.entries(value.characterPriorities ?? {}).map(([c, p]) => `${c} ${p}`).join(', ')
    const who = value.designatedTrader ? `trader: ${value.designatedTrader}`
      : value.eligibleCharacters?.length ? value.eligibleCharacters.join(', ') : 'all characters'
    return `${eventLabel(value.eventCode)} · ${prio}${overrides ? ` (${overrides})` : ''} · ${who}`
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

{#snippet partyPicker()}
  <div class="stack">
    {#each parties as party, pi}
      <div class="row">
        {#if parties.length > 1}<span class="muted small">Party {pi + 1}</span>{/if}
        {#each party.slots as _, si}
          <label>{si === 0 ? 'Initiator' : `Participant ${si}`}
            <select bind:value={party.slots[si]}>
              <option value="">—</option>
              {#each characters as c}<option value={c} disabled={takenElsewhere(pi, si).has(c)}>{c}</option>{/each}
            </select>
          </label>
        {/each}
        <label>Tank
          <select bind:value={party.tank}>
            <option value="">Auto (optimizer picks)</option>
            {#each party.slots.filter(Boolean) as c}<option value={c}>{c}</option>{/each}
          </select>
        </label>
        <label>Priority
          <input type="number" min="1" max="100" step="1" style="width:80px" placeholder={String(partyDefault)} bind:value={party.priority} />
        </label>
        {#if parties.length > 1}<button class="danger" onclick={() => parties.splice(pi, 1)}>✕</button>{/if}
      </div>
    {/each}
    <div>
      <button onclick={() => parties.push(blankParty())}>+ Add party</button>
      <span class="muted small">Each party fights separately; earlier parties get first pick of bank gear and potions. Blank priority = {partyDefault}; higher runs first and can interrupt lower-priority work.</span>
    </div>
  </div>
{/snippet}

{#snippet itemRows(items: ItemQty[], mode: 'sell' | 'buy')}
  <div class="stack">
    <span class="muted small">
      {mode === 'sell' ? 'Sell to NPC — "keep" = sell down to this many (0 = sell all)' : 'Buy from NPC — quantity per event'}
    </span>
    {#each items as item, i}
      {@const hint = tradeHint(item.code, mode)}
      <div class="row">
        <input list={npcItems ? `npc-${mode}` : 'cfg-items'} placeholder="item code" bind:value={item.code} />
        <label class="check small">{mode === 'sell' ? 'keep' : 'qty'}
          <input type="number" min={mode === 'sell' ? 0 : 1} style="width:90px" bind:value={item.quantity} />
        </label>
        <button class="danger" onclick={() => items.splice(i, 1)}>✕</button>
        {#if hint}<span class="small {hint.bad ? 'error' : 'muted'}">{hint.text}</span>{/if}
      </div>
    {/each}
    <div><button onclick={() => items.push({ code: '', quantity: mode === 'sell' ? 0 : 1 })}>+ Add item</button></div>
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
      <label>Priority (1–100)
        <input type="number" min="1" max="100" step="1" style="width:80px" placeholder={String(EVENT_PRIORITY)} bind:value={ev.priority} />
      </label>
      {#if !evMeta || evMeta.contentType === 'monster'}
        <label>Min win rate (0–1)
          <input type="number" min="0" max="1" step="0.05" style="width:90px" bind:value={ev.minWinRate} />
        </label>
      {/if}
    </div>

    {#if isBossEvent}
      <p class="muted small">
        {evMonster?.name} is a boss: each party below is queued as its own co-op group (initiator fires
        the fight with its participants). Parties below the min win rate are skipped; without a
        party the event is skipped.
      </p>
      {@render partyPicker()}
    {:else if evMeta?.contentType === 'npc'}
      <div class="row">
        <label>Designated trader
          <select bind:value={ev.designatedTrader}>
            <option value={null}>— none (skip) —</option>
            {#each characters as c}<option value={c}>{c}</option>{/each}
          </select>
        </label>
      </div>
      {#if npcError}<p class="error small">{npcError} — any item code is accepted.</p>{/if}
      <div class="row" style="align-items:start">
        {@render itemRows(ev.itemsToSell, 'sell')}
        {@render itemRows(ev.itemsToBuy, 'buy')}
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
        <span class="muted small">
          Per-character priority overrides (blank = event priority {num(ev.priority) ?? EVENT_PRIORITY}). When events overlap,
          a character works the higher-priority one first.
        </span>
        <div class="row">
          {#each prioritizable as c}
            <label class="check small">{c}
              <input type="number" min="1" max="100" step="1" style="width:70px"
                placeholder={String(num(ev.priority) ?? EVENT_PRIORITY)}
                value={ev.characterPriorities?.[c] ?? ''}
                oninput={(e) => setCharPriority(c, e.currentTarget.value)} />
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
      <label>Min win rate (0 = never skip)
        <input type="number" min="0" max="1" step="0.05" style="width:90px" bind:value={raid.minWinRate} />
      </label>
    </div>
    {@render partyPicker()}
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
<datalist id="npc-sell">
  {#each [...sellable.values()] as i}<option value={i.code}>{itemName(i.code)} — {i.sellPrice} {i.currency}</option>{/each}
</datalist>
<datalist id="npc-buy">
  {#each [...buyable.values()] as i}<option value={i.code}>{itemName(i.code)} — {i.buyPrice} {i.currency}</option>{/each}
</datalist>

<style>
  .stack { display: flex; flex-direction: column; gap: 8px; }
  .small { font-size: 12px; }
  .check { flex-direction: row; align-items: center; gap: 6px; }
</style>
