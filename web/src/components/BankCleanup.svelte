<!--
  Bank cleanup: owned gear beaten on every stat by other owned gear. Copies are kept for
  characters that can't use anything better yet; the rest is surplus that can be recycled.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import type { CleanupRow, CleanupState, GePrice } from '../lib/types'

  let { target = '' }: { target?: string } = $props()

  const TYPES = ['weapon', 'shield', 'helmet', 'body_armor', 'leg_armor', 'boots', 'ring', 'amulet', 'artifact', 'rune', 'bag']

  let data = $state<CleanupState | null>(null)
  let tab = $state<'gear' | 'resources'>('gear')
  let typeFilter = $state('')
  let onlySurplus = $state(true)
  let manualPriority = $state(50)
  let showSettings = $state(false)
  let busy = $state(false)
  let error = $state('')
  let message = $state('')

  const load = (p: Promise<CleanupState>, msg = '') => {
    busy = true
    return p.then((d) => { data = d; error = ''; message = msg }).catch((e) => (error = e.message)).finally(() => (busy = false))
  }
  load(api.cleanup())

  const rows = $derived((data?.report?.rows ?? []).filter((r) =>
    (!typeFilter || r.type === typeFilter) && (!onlySurplus || r.surplus > 0)))
  const recyclableRows = $derived((data?.report?.rows ?? []).filter((r) => r.craftSkill && r.recyclable > 0))

  async function recycle(r: CleanupRow, ask = true) {
    if (!r.craftSkill) return
    const q = ask ? Math.min(Number(prompt(`Recycle how many ${r.name}? (surplus in bank: ${r.recyclable})`, String(r.recyclable))), r.bank) : r.recyclable
    if (!q || q < 1) return
    return api.createTask({
      spec: { kind: 'bank_recycle', itemCode: r.code, itemName: r.name, quantity: q, craftSkill: r.craftSkill },
      assignedCharacter: target || undefined, priority: Number(manualPriority) || 50, dedupeKey: `cleanup-recycle:${r.code}`,
    })
  }

  async function recycleOne(r: CleanupRow) {
    try {
      const t = await recycle(r)
      if (t) message = `Queued recycle #${t.id} for ${r.name}${target ? ` (${target})` : ''}`
    } catch (e: any) { error = e.message }
  }

  async function deleteFromBank(code: string, name: string, bank: number, suggested = bank) {
    if (bank < 1) { error = `No ${name} in the bank`; return }
    const q = Math.min(Number(prompt(`Permanently delete how many ${name}? (in bank: ${bank})`, String(suggested))), bank)
    if (!q || q < 1) return
    if (!confirm(`Delete ${q}× ${name}? This can't be undone.`)) return
    try {
      const t = await api.createTask({
        spec: { kind: 'bank_delete', itemCode: code, itemName: name, quantity: q },
        assignedCharacter: target || undefined, priority: Number(manualPriority) || 50, dedupeKey: `cleanup-delete:${code}`,
      })
      message = `Queued delete #${t.id}: ${q}× ${name}${target ? ` (${target})` : ''}`
    } catch (e: any) { error = e.message }
  }

  async function recycleAll() {
    if (!confirm(`Queue recycles for ${recyclableRows.length} items (${recyclableRows.map((r) => `${r.recyclable}× ${r.code}`).join(', ')})?`)) return
    let n = 0
    try {
      for (const r of recyclableRows) if (await recycle(r, false)) n++
      message = `Queued ${n} recycle task(s)`
    } catch (e: any) { error = e.message }
  }

  async function setKeep(code: string, on: boolean) {
    if (!data) return
    const c = $state.snapshot(data.config)
    c.keep = on ? [...c.keep, code] : c.keep.filter((k) => k !== code)
    await load(api.saveCleanup(c), on ? `Keeping ${code}` : `${code} removed from keep list`)
  }

  async function saveSettings() {
    if (!data) return
    const c = $state.snapshot(data.config)
    c.spares = Number(c.spares); c.lookaheadLevels = Number(c.lookaheadLevels); c.recyclePriority = Number(c.recyclePriority)
    c.resourceGearMargin = Number(c.resourceGearMargin); c.resourceFoodMargin = Number(c.resourceFoodMargin)
    c.resourceXpWindow = Number(c.resourceXpWindow); c.resourceThinUses = Number(c.resourceThinUses)
    await load(api.saveCleanup(c), 'Settings saved')
  }

  function toggleType(t: string, on: boolean) {
    if (!data) return
    data.config.types = on ? [...data.config.types, t] : data.config.types.filter((x) => x !== t)
  }

  const owned = (r: CleanupRow) => r.bank + r.inventory + r.equipped

  const VERDICTS = ['unused', 'no_recipe', 'thin'] as const
  const VERDICT_LABEL: Record<string, string> = { unused: 'Unused', no_recipe: 'No recipe', thin: 'Thin use' }
  const VERDICT_HINT: Record<string, string> = {
    unused: 'everything it makes is outgrown; sell or delete',
    no_recipe: 'review before selling',
    thin: 'only a few uses (or Task Master items) left; keep what you need',
  }
  let verdictFilter = $state('')
  let hideProtected = $state(false)
  let ge = $state<Record<string, GePrice>>({})
  let geBusy = $state('')
  const resourceRows = $derived((data?.report?.resources ?? []).filter((r) => !hideProtected || !r.protectedBy))
  const fmt = (n: number) => n.toLocaleString()

  async function checkGe(code: string) {
    geBusy = code
    try { ge[code] = await api.gePrice(code) } catch (e: any) { error = e.message } finally { geBusy = '' }
  }
</script>

<div class="panel" style="margin-bottom:12px">
  <div class="row" style="justify-content:space-between">
    <div class="row">
      <strong>Cleanup</strong>
      <button class:active={tab === 'gear'} onclick={() => (tab = 'gear')}>Gear</button>
      <button class:active={tab === 'resources'} onclick={() => (tab = 'resources')}>Resources</button>
    </div>
    <div class="row">
      <button onclick={() => (showSettings = !showSettings)}>Settings</button>
      <button onclick={() => load(api.refreshCleanup(), 'Re-analysed')} disabled={busy}>{busy ? 'Analysing…' : 'Analyse now'}</button>
    </div>
  </div>
  {#if error}<div class="error">{error}</div>{/if}
  {#if message}<div class="muted">{message}</div>{/if}

  {#if tab === 'resources' && data}
    {#if showSettings}
      <div class="panel editor">
        <div class="row">
          <label title="Gear/tools still count as a use down to (character level − margin)">Gear margin
            <input type="number" min="0" style="width:60px" bind:value={data.config.resourceGearMargin} /></label>
          <label title="Food/potions still count as a use down to (character level − margin)">Food margin
            <input type="number" min="0" style="width:60px" bind:value={data.config.resourceFoodMargin} /></label>
          <label title="A recipe counts while some character's craft skill is below recipe level + window (0 = ignore crafting XP)">XP window
            <input type="number" min="0" style="width:60px" bind:value={data.config.resourceXpWindow} /></label>
          <label title="Flag a resource as thin when some products are outgrown and at most this many uses remain">Thin ≤ uses
            <input type="number" min="0" style="width:60px" bind:value={data.config.resourceThinUses} /></label>
          <label title="Comma-separated item types that never count as a use">Ignore types
            <input style="width:120px" value={data.config.resourceIgnoreTypes.join(', ')}
              onchange={(e) => data && (data.config.resourceIgnoreTypes = e.currentTarget.value.split(',').map((s) => s.trim()).filter(Boolean))} /></label>
          <span class="muted">Keep list: {data.config.keep.length ? data.config.keep.join(', ') : 'none'}</span>
          <button onclick={saveSettings}>Save settings</button>
        </div>
      </div>
    {/if}
    {#if !data.report}
      <p class="muted">The engine isn't running, so there's no analysis yet.</p>
    {:else}
      <div class="row">
        <label>Show
          <select bind:value={verdictFilter}>
            <option value="">all</option>
            {#each VERDICTS as v}<option value={v}>{VERDICT_LABEL[v]}</option>{/each}
          </select>
        </label>
        <label><input type="checkbox" bind:checked={hideProtected} /> Hide kept/protected</label>
        <label title="Priority for delete tasks queued from this tab">Priority
          <input type="number" style="width:60px" bind:value={manualPriority} /></label>
        <span class="muted">Suggestions only — nothing is sold or deleted unless you click Delete (goes to {target || 'any character'}).</span>
      </div>
      {#each VERDICTS.filter((v) => !verdictFilter || v === verdictFilter) as v}
        {@const group = resourceRows.filter((r) => r.verdict === v)}
        {#if group.length}
          <h4>{VERDICT_LABEL[v]} <span class="muted">({group.length}) — {VERDICT_HINT[v]}</span></h4>
          <table>
            <thead><tr><th>Item</th><th title="bank / inventory">Owned</th><th>Why</th><th>Sell</th><th></th></tr></thead>
            <tbody>
              {#each group as r (r.code)}
                <tr>
                  <td>{r.name} <span class="muted">{r.subtype} L{r.level}</span></td>
                  <td>{r.bank + r.inventory} <span class="muted">({r.bank}/{r.inventory})</span></td>
                  <td>
                    {#if r.verdict === 'unused'}
                      <span class="muted">Recipes only reach L{r.maxRecipeLevel ?? 0}; nothing useful at your levels.</span>
                    {:else if r.verdict === 'no_recipe'}
                      <span class="muted">Not an ingredient anywhere (may matter for offerings/achievements).</span>
                    {/if}
                    {#each r.uses ?? [] as u}
                      <div title={u.path.join(' → ')}>
                        {u.name} <span class="muted">L{u.level} · {u.reason} · enough for {fmt(u.crafts)} {u.kind === 'npc' ? 'purchases' : 'crafts'}</span>
                      </div>
                    {/each}
                    {#each r.taskTargets ?? [] as t}
                      <div title={t.path.join(' → ')}>
                        <span class="badge">Task Master</span> {t.name}
                        <span class="muted">{t.reason.replace('Task Master item ', '')} · enough for {fmt(t.crafts)}</span>
                      </div>
                    {/each}
                    {#if r.protectedBy}<div class="muted">Protected: {r.protectedBy}</div>{/if}
                  </td>
                  <td>
                    {#each r.npcSell ?? [] as s}
                      <div>{s.npcName} {s.price} {s.currency}{#if !s.permanent} <span class="muted">(event)</span>{/if}</div>
                    {/each}
                    {#if ge[r.code]}
                      {@const g = ge[r.code]}
                      <div class="muted" title={g.recentSales.slice(0, 5).map((s) => `${s.quantity}× @ ${s.price}`).join(', ')}>
                        GE: sell {g.lowestSell ?? '—'} · buy {g.highestBuy ?? '—'} · last {g.recentSales[0]?.price ?? '—'}
                      </div>
                    {:else}
                      <button onclick={() => checkGe(r.code)} disabled={geBusy === r.code}>{geBusy === r.code ? '…' : 'GE price'}</button>
                    {/if}
                  </td>
                  <td>
                    {#if data.config.keep.includes(r.code)}
                      <button onclick={() => setKeep(r.code, false)}>Unkeep</button>
                    {:else}
                      <button onclick={() => setKeep(r.code, true)}>Keep</button>
                      {#if r.bank > 0 && !r.protectedBy}
                        <button onclick={() => deleteFromBank(r.code, r.name, r.bank)}>Delete</button>
                      {/if}
                    {/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        {/if}
      {/each}
      {#if !resourceRows.length}
        <p class="muted">No cleanup suggestions for resources. 🎉</p>
      {/if}
    {/if}
  {:else if data}
    {#if showSettings}
      <div class="panel editor">
        <div class="row">
          <span class="muted">Item types:</span>
          {#each TYPES as t}
            <label><input type="checkbox" checked={data.config.types.includes(t)}
              onchange={(e) => toggleType(t, e.currentTarget.checked)} /> {t}</label>
          {/each}
        </div>
        <div class="row">
          <label title="Copies of each obsolete item to keep anyway">Spares <input type="number" min="0" style="width:60px" bind:value={data.config.spares} /></label>
          <label title="Also keep items a character can use within this many levels">Look-ahead levels
            <input type="number" min="0" style="width:60px" bind:value={data.config.lookaheadLevels} /></label>
          <label><input type="checkbox" bind:checked={data.config.protectIngredients} /> Keep recipe ingredients</label>
          <label><input type="checkbox" bind:checked={data.config.protectLoadouts} /> Keep saved-loadout gear</label>
        </div>
        <div class="row">
          <label title="Queue unassigned recycle tasks for recyclable surplus every 10 minutes">
            <input type="checkbox" bind:checked={data.config.autoRecycle} /> Auto-recycle surplus</label>
          <label>Priority <input type="number" style="width:60px" bind:value={data.config.recyclePriority} /></label>
          <span class="muted">Keep list: {data.config.keep.length ? data.config.keep.join(', ') : 'none'}</span>
          <button onclick={saveSettings}>Save settings</button>
        </div>
      </div>
    {/if}

    {#if !data.report}
      <p class="muted">The engine isn't running, so there's no analysis yet.</p>
    {:else}
      <div class="row">
        <label>Type
          <select bind:value={typeFilter}><option value="">all</option>{#each data.config.types as t}<option>{t}</option>{/each}</select>
        </label>
        <label><input type="checkbox" bind:checked={onlySurplus} /> Only surplus</label>
        <span class="muted">Recycles go to {target || 'any character'}.</span>
        <label title="Priority for tasks queued by the Recycle buttons">Priority
          <input type="number" style="width:60px" bind:value={manualPriority} /></label>
        <button onclick={recycleAll} disabled={!recyclableRows.length}>Recycle all ({recyclableRows.length})</button>
      </div>
      {#if rows.length}
        <table>
          <thead><tr>
            <th>Item</th><th>Lvl</th><th title="bank / inventory / equipped">Owned</th><th>Kept</th>
            <th>Surplus</th><th>Beaten by</th><th></th>
          </tr></thead>
          <tbody>
            {#each rows as r (r.code)}
              <tr>
                <td>{r.name} <span class="muted">{r.type}</span></td>
                <td>{r.level}</td>
                <td>{owned(r)} <span class="muted">({r.bank}/{r.inventory}/{r.equipped})</span></td>
                <td>{r.kept}{#if r.keptFor.length} <span class="muted">for {r.keptFor.join(', ')}</span>{/if}</td>
                <td>{r.surplus}{#if r.protectedBy} <span class="muted">— {r.protectedBy}</span>{/if}</td>
                <td title={r.dominatedBy.join(', ')}>
                  {r.dominatedBy.slice(0, 3).join(', ')}{r.dominatedBy.length > 3 ? ` +${r.dominatedBy.length - 3}` : ''}
                </td>
                <td>
                  {#if r.craftSkill && r.recyclable > 0}
                    <button onclick={() => recycleOne(r)}>Recycle {r.recyclable}</button>
                  {:else if r.surplus > 0 && !r.craftSkill}
                    <span class="muted" title="Not crafted with weapon/gear/jewelry crafting">can't recycle</span>
                    {#if r.bank > 0 && !r.protectedBy}
                      <button onclick={() => deleteFromBank(r.code, r.name, r.bank, Math.min(r.surplus, r.bank))}>Delete</button>
                    {/if}
                  {/if}
                  {#if data.config.keep.includes(r.code)}
                    <button onclick={() => setKeep(r.code, false)}>Unkeep</button>
                  {:else}
                    <button onclick={() => setKeep(r.code, true)}>Keep</button>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      {:else}
        <p class="muted">{onlySurplus ? 'No surplus gear. 🎉' : 'Nothing owned is beaten by other owned gear.'}</p>
      {/if}
    {/if}
  {/if}
</div>

<style>
  .editor { margin: 8px 0; }
  button.active { font-weight: bold; text-decoration: underline; }
  .badge { font-size: 0.75em; padding: 0 4px; border: 1px solid currentColor; border-radius: 3px; }
</style>
