<!--
  Form that builds a TaskSpec. Used by "New task" and as the filler editor on Characters.

  `spec = $bindable()` lets the parent write `<SpecEditor bind:spec={x} />`: edits made here
  flow back into the parent's variable. We rebuild `spec` from the individual fields with an
  $effect, so the parent always holds a valid object for the selected kind.
-->
<script lang="ts">
  import { catalog } from '../lib/catalog'
  import { api } from '../lib/api'
  import { live } from '../lib/live.svelte'
  import { SKILLS, type Catalog, type CharacterDto, type Craftable, type TaskSpec, type EquipPlan, type UtilityPlan } from '../lib/types'

  // `who` = the assigned character's levels. When set, pickers only offer what that
  // character can do (unless "Show all" is ticked).
  let { spec = $bindable(), character = '', who = null }: {
    spec: TaskSpec | null; character?: string; who?: CharacterDto | null
  } = $props()

  /** Fights have no hard level gate; allow a little above the character's level. */
  const FIGHT_LEVEL_MARGIN = 5
  let showAll = $state(false)

  const kinds = {
    gather: 'Gather', fight: 'Fight', craft: 'Craft', task_master: 'Task master',
    bank_withdraw: 'Withdraw from bank', inventory_deposit: 'Deposit to bank',
    bank_recycle: 'Recycle from bank', inventory_recycle: 'Recycle from inventory',
  } as const
  type Kind = keyof typeof kinds

  // Start from the incoming spec (e.g. an existing filler) when editing.
  const init = spec as any
  let kind = $state<Kind>((init?.kind in kinds ? init.kind : 'gather') as Kind)
  let resourceCode = $state(init?.resourceCode ?? '')
  let targetItemCode = $state(init?.targetItemCode ?? '')
  let cookBeforeDeposit = $state(init?.cookBeforeDeposit ?? false)
  let monsterCode = $state(init?.monsterCode ?? '')
  let itemCode = $state(init?.itemCode ?? '')
  let mode = $state<'BANK' | 'RECYCLE'>(init?.mode ?? 'BANK')
  let quantity = $state<number>(init?.targetQuantity ?? init?.quantity ?? 1)
  let taskType = $state<'items' | 'monsters'>(init?.taskType ?? 'monsters')
  let craftSkill = $state(init?.craftSkill ?? 'weaponcrafting')
  let equip = $state<EquipPlan[]>(init?.equip ?? [])
  let utilities = $state<UtilityPlan[]>(init?.utilities ?? [])

  let cat = $state<Catalog | null>(null)
  let catError = $state('')
  catalog().then((c) => (cat = c)).catch((e) => (catError = e.message))

  // Look up names/skills from the catalog so the spec is complete.
  const resource = $derived(cat?.resources.find((r) => r.code === resourceCode))
  const item = $derived(cat?.items.find((i) => i.code === itemCode))

  $effect(() => {
    spec = build()
  })

  // ── Filtering ──
  // `$derived` recomputes whenever `who`, `showAll` or the catalog changes. The currently
  // selected entry is always kept (tagged) so switching character doesn't silently drop it.
  const skillOf = (skill?: string) => (skill ? who?.skills?.[skill] ?? 0 : 0)
  const levelled = $derived(!!who && who.level != null && !showAll)
  function why(level: number, allowed: number, reachable = true): string {
    if (showAll) return ''
    if (!reachable) return ' — unreachable'
    return levelled && level > allowed ? ' — too high' : ''
  }
  function keep<T extends { code: string }>(list: T[], ok: (x: T) => boolean, selected: string): T[] {
    return list.filter((x) => ok(x) || x.code === selected)
  }
  const resources = $derived(keep(cat?.resources ?? [],
    (r) => !why(r.level, skillOf(r.skill), r.reachable), resourceCode))
  const monsters = $derived(keep(cat?.monsters ?? [],
    (m) => !why(m.level, (who?.level ?? 0) + FIGHT_LEVEL_MARGIN, m.reachable), monsterCode))
  const craftables = $derived(byRecipeOrder(keep((cat?.items ?? []).filter((i) => i.craftSkill),
    (i) => !why(i.level, skillOf(i.craftSkill)), itemCode)))
  // ── Craftable quantities ──
  // Fetched when a craft picker is visible; re-fetched when the character, "Show all" or the
  // bank changes (live.bankTick, ≤1/s from the server; we additionally wait 2 s so bursts of
  // deposits cause one refresh). Selection and typed quantity are left alone on refresh.
  let recipes = $state<Record<string, Craftable>>({})
  let recipeOrder = $state<string[]>([])
  let recipesError = $state('')
  let recipesLoading = $state(false)
  let refreshNonce = $state(0)
  const needsRecipes = $derived(kind === 'craft' || (kind === 'gather' && !!resource))
  let lastFetch = 0
  $effect(() => {
    if (!needsRecipes) return
    live.bankTick; refreshNonce                    // dependencies
    const who = character, all = showAll
    const wait = Math.max(0, 2000 - (Date.now() - lastFetch))
    const t = setTimeout(async () => {
      lastFetch = Date.now(); recipesLoading = true
      try {
        const list = await api.craftable(who, all)
        recipes = Object.fromEntries(list.map((r) => [r.code, r]))
        recipeOrder = list.map((r) => r.code)
        recipesError = ''
      } catch (e: any) { recipesError = e.message } finally { recipesLoading = false }
    }, wait)
    return () => clearTimeout(t)
  })
  const maxOf = (code: string) => recipes[code]?.maxCraftable
  const maxLabel = (code: string) => (maxOf(code) == null ? '' : ` — max ${maxOf(code)}`)
  const selectedRecipe = $derived(recipes[itemCode])
  // Default the quantity to the max the first time a recipe with a known max is picked.
  let defaultedFor = ''
  $effect(() => {
    const r = selectedRecipe
    if (kind === 'craft' && r && defaultedFor !== r.code) {
      defaultedFor = r.code
      if (r.maxCraftable > 0) quantity = r.maxCraftable
    }
  })
  /** Server order (craftable now first, then level desc); unknown codes keep catalog order at the end. */
  function byRecipeOrder<T extends { code: string }>(list: T[]): T[] {
    const idx = new Map(recipeOrder.map((c, i) => [c, i]))
    return [...list].sort((a, b) => (idx.get(a.code) ?? 1e9) - (idx.get(b.code) ?? 1e9))
  }

  const gatherTargets = $derived(keep((cat?.items ?? []).filter((i) => i.craftSkill === resource?.skill || i.craftSkill === 'cooking'),
    (i) => !why(i.level, skillOf(i.craftSkill)), targetItemCode))

  function build(): TaskSpec | null {
    switch (kind) {
      case 'gather':
        if (!resource) return null
        return { kind, skill: resource.skill, resourceCode, resourceName: resource.name,
                 targetItemCode: targetItemCode || undefined, cookBeforeDeposit }
      case 'fight':
        if (!monsterCode) return null
        return { kind, monsterCode, equip, utilities, loadoutOptimized: equip.length > 0 }
      case 'craft':
        if (!item?.craftSkill) return null
        return { kind, skill: item.craftSkill, itemCode, itemName: item.name, mode, targetQuantity: mode === 'BANK' ? quantity : 0 }
      case 'task_master':
        return { kind, taskType }
      case 'bank_withdraw':
      case 'inventory_deposit':
        return itemCode ? { kind, itemCode, quantity } : null
      case 'bank_recycle':
      case 'inventory_recycle':
        return itemCode ? { kind, itemCode, quantity, craftSkill: item?.craftSkill ?? craftSkill } : null
    }
  }

  // Fight: ask the backend's gear optimizer (fight simulator) for the best loadout.
  let optimizing = $state(false)
  let optResult = $state('')
  async function optimize() {
    optimizing = true; optResult = ''
    try {
      const r = await api.simLoadout(character, monsterCode)
      equip = r.equip; utilities = r.utilities
      optResult = `Win rate ${(r.baselineWinRate * 100).toFixed(0)}% → ${(r.winRate * 100).toFixed(0)}% (${r.equip.length} gear, ${r.utilities.length} utility changes)`
    } catch (e: any) { optResult = e.message } finally { optimizing = false }
  }
</script>

<div class="row">
  <label>Type
    <select bind:value={kind}>
      {#each Object.entries(kinds) as [k, label]}<option value={k}>{label}</option>{/each}
    </select>
  </label>

  {#if catError}<span class="error">Catalog: {catError}</span>{/if}

  {#if kind === 'gather' || kind === 'fight' || kind === 'craft'}
    <label class="check" title="Include content above the character's level or in areas the account can't reach yet">
      <input type="checkbox" bind:checked={showAll} /> Show all
    </label>
  {/if}

  {#if kind === 'gather'}
    <label>Resource
      <select bind:value={resourceCode}>
        <option value="">— choose —</option>
        {#each resources as r}<option value={r.code}>{r.name} ({r.skill} {r.level}){why(r.level, skillOf(r.skill), r.reachable)}</option>{/each}
      </select>
    </label>
    <label>Craft into (optional)
      <select bind:value={targetItemCode}>
        <option value="">— just bank raw —</option>
        {#each gatherTargets as i}
          <option value={i.code}>{i.name} ({i.craftSkill} {i.level}){maxLabel(i.code)}{why(i.level, skillOf(i.craftSkill))}</option>
        {/each}
      </select>
    </label>
    {#if resource?.skill === 'fishing'}
      <label class="check"><input type="checkbox" bind:checked={cookBeforeDeposit} /> Cook before deposit</label>
    {/if}
  {:else if kind === 'fight'}
    <label>Monster
      <select bind:value={monsterCode}>
        <option value="">— choose —</option>
        {#each monsters as m}<option value={m.code}>{m.name} (lv {m.level}{m.type !== 'normal' ? `, ${m.type}` : ''}){why(m.level, (who?.level ?? 0) + FIGHT_LEVEL_MARGIN, m.reachable)}</option>{/each}
      </select>
    </label>
    <button onclick={optimize} disabled={!character || !monsterCode || optimizing}
            title={character ? '' : 'Assign a character first'}>
      {optimizing ? 'Optimizing…' : 'Optimize gear'}
    </button>
    {#if optResult}<span class="muted">{optResult}</span>{/if}
  {:else if kind === 'craft'}
    <label>Item
      <select bind:value={itemCode}>
        <option value="">— choose —</option>
        {#each craftables as i}
          <option value={i.code} class:dim={maxOf(i.code) === 0}>{i.name} ({i.craftSkill} {i.level}){maxLabel(i.code)}{why(i.level, skillOf(i.craftSkill))}</option>
        {/each}
      </select>
    </label>
    <label>Mode
      <select bind:value={mode}><option value="BANK">Craft &amp; bank</option><option value="RECYCLE">Craft &amp; recycle (XP)</option></select>
    </label>
    {#if mode === 'BANK'}
      <label>Quantity{selectedRecipe ? ` (max ${selectedRecipe.maxCraftable})` : ''}
        <input type="number" min="1" bind:value={quantity} />
      </label>
    {/if}
    <button class="small" onclick={() => refreshNonce++} title="Recount materials" disabled={recipesLoading}>↻</button>
    {#if recipesError}<span class="error">Materials: {recipesError}</span>{/if}
  {:else if kind === 'task_master'}
    <label>Task type
      <select bind:value={taskType}><option value="monsters">Monsters</option><option value="items">Items</option></select>
    </label>
  {:else}
    <label>Item
      <select bind:value={itemCode}>
        <option value="">— choose —</option>
        {#each cat?.items ?? [] as i}<option value={i.code}>{i.name}</option>{/each}
      </select>
    </label>
    <label>Quantity <input type="number" min="1" bind:value={quantity} /></label>
    {#if (kind === 'bank_recycle' || kind === 'inventory_recycle') && !item?.craftSkill}
      <label>Workshop <select bind:value={craftSkill}>{#each SKILLS as s}<option>{s}</option>{/each}</select></label>
    {/if}
  {/if}
</div>

{#if kind === 'craft' && selectedRecipe}
  <div class="small ingredients">
    {#if mode === 'BANK' && quantity > selectedRecipe.maxCraftable}
      <div class="warn">Only {selectedRecipe.maxCraftable} craftable right now — the task will stop when materials run out.</div>
    {/if}
    {#each selectedRecipe.ingredients as ing}
      <span class:missing={ing.missing > 0}>
        {ing.code} {ing.have}/{ing.perCraft}{ing.missing > 0 ? ` — missing ${ing.missing}` : ''}
      </span>
    {/each}
    {#each selectedRecipe.npcBuy as b}
      <span class="muted">buys {b.quantity}× {b.item} from {b.npc} ({b.priceEach} {b.currency} each)</span>
    {/each}
    <span class="muted">({character ? `${character}'s inventory + bank` : 'bank only'})</span>
  </div>
{/if}

{#if kind === 'fight' && (equip.length || utilities.length)}
  <div class="muted small">
    Loadout: {equip.map((e) => `${e.slot}=${e.itemCode}`).join(', ')}
    {utilities.map((u) => `${u.slot}=${u.quantity}×${u.itemCode}`).join(', ')}
    <button onclick={() => { equip = []; utilities = [] }}>clear</button>
  </div>
{/if}

<style>
  .check { flex-direction: row; align-items: center; }
  .small { font-size: 12px; margin-top: 6px; }
  .ingredients { display: flex; flex-wrap: wrap; gap: 4px 14px; }
  .missing { color: #d9822b; }
  .warn { flex-basis: 100%; color: #d9822b; }
  option.dim { color: #888; }
</style>
