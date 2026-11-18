<!--
  Tool kit: every character carries one tool per managed skill. When good tools are scarce,
  characters earlier in the claim order get them first (optionally per skill). The engine
  queues a short `toolkit_sync` bank trip for characters whose kit can change and recycles
  tools nobody needs anymore (every character already has one at least as good).
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { ago } from '../lib/format'
  import { TOOL_SKILLS, type FoodConfig, type GearFocusConfig, type ToolKitConfig, type ToolKitPlan } from '../lib/types'

  let config = $state<ToolKitConfig | null>(null)
  let plan = $state<ToolKitPlan | null>(null)
  let roster = $state<string[]>([])
  let message = $state('')
  let error = $state('')
  let busy = $state(false)

  // Gathering gear focus: one item code per line.
  let focus = $state<GearFocusConfig | null>(null)
  let prospectText = $state('')
  let focusMessage = $state('')
  api.gearFocus()
    .then((f) => { focus = f; prospectText = f.prospectFor.join('\n') })
    .catch((e) => (error = e.message))

  // Fight food sizing / heal timing.
  let food = $state<FoodConfig | null>(null)
  let foodMessage = $state('')
  api.foodConfig().then((f) => (food = f)).catch((e) => (error = e.message))

  async function saveFood() {
    if (!food) return
    busy = true; error = ''; foodMessage = ''
    try {
      food = await api.saveFoodConfig({
        ...food,
        lootReserveFights: Number(food.lootReserveFights), minFood: Number(food.minFood),
        maxFood: Number(food.maxFood), healSafety: Number(food.healSafety),
      })
      foodMessage = 'Saved.'
    } catch (e: any) { error = e.message } finally { busy = false }
  }

  async function saveFocus() {
    busy = true; error = ''; focusMessage = ''
    try {
      focus = await api.saveGearFocus({ prospectFor: prospectText.split(/[\s,]+/).filter(Boolean) })
      prospectText = focus.prospectFor.join('\n')
      focusMessage = 'Saved.'
    } catch (e: any) { error = e.message } finally { busy = false }
  }

  function apply(s: { config: ToolKitConfig; plan: ToolKitPlan | null }) {
    config = s.config
    plan = s.plan
  }

  Promise.all([api.toolKit(), api.characters()])
    .then(([s, chars]) => { apply(s); roster = chars.map((c) => c.name) })
    .catch((e) => (error = e.message))

  /** Full order shown in the editor: listed characters, then the rest in roster order. */
  function fullOrder(listed: string[]): string[] {
    const known = listed.filter((n) => roster.includes(n))
    return [...known, ...roster.filter((n) => !known.includes(n))]
  }

  function move(list: string[], i: number, dir: -1 | 1): string[] {
    const out = [...list]
    const j = i + dir
    if (j < 0 || j >= out.length) return out
    ;[out[i], out[j]] = [out[j], out[i]]
    return out
  }

  function toggleSkill(skill: string, on: boolean) {
    if (!config) return
    config.skills = on ? [...new Set([...config.skills, skill])] : config.skills.filter((s) => s !== skill)
  }

  function toggleOverride(skill: string, on: boolean) {
    if (!config) return
    const next = { ...config.perSkillPriority }
    if (on) next[skill] = fullOrder(config.priority)
    else delete next[skill]
    config.perSkillPriority = next
  }

  async function save() {
    if (!config) return
    busy = true; error = ''; message = ''
    try {
      apply(await api.saveToolKit({ ...config, priority: fullOrder(config.priority) }))
      message = 'Saved — plan recomputed.'
    } catch (e: any) { error = e.message } finally { busy = false }
  }

  async function refresh() {
    busy = true; error = ''; message = ''
    try { apply(await api.refreshToolKit()); message = 'Plan recomputed.' }
    catch (e: any) { error = e.message } finally { busy = false }
  }

  const pending = (name: string) => {
    const a = plan?.actions[name]
    if (!a) return ''
    const parts = [
      ...Object.entries(a.deposit).map(([c, q]) => `bank ${q}× ${c}`),
      ...a.withdraw.map((c) => `take ${c}`),
    ]
    return parts.join(', ')
  }
</script>

<h2>Tool kit</h2>
{#if error}<p class="bad">{error}</p>{/if}
{#if message}<p class="muted">{message}</p>{/if}

{#if config}
  <section class="card">
    <label><input type="checkbox" bind:checked={config.enabled} /> Manage tool kits</label>

    <h3>Skills</h3>
    <div class="row">
      {#each TOOL_SKILLS as skill}
        <label><input type="checkbox" checked={config.skills.includes(skill)}
          onchange={(e) => toggleSkill(skill, e.currentTarget.checked)} /> {skill}</label>
      {/each}
    </div>

    <h3>Claim order</h3>
    <p class="muted">Who gets the better tool first when there aren't enough to go around.</p>
    <ol class="order">
      {#each fullOrder(config.priority) as name, i (name)}
        <li>
          <span>{name}</span>
          <button disabled={i === 0} onclick={() => config && (config.priority = move(fullOrder(config.priority), i, -1))}>↑</button>
          <button disabled={i === roster.length - 1} onclick={() => config && (config.priority = move(fullOrder(config.priority), i, 1))}>↓</button>
        </li>
      {/each}
    </ol>

    <h3>Per-skill overrides</h3>
    <div class="overrides">
      {#each config.skills as skill (skill)}
        {@const custom = config.perSkillPriority[skill]}
        <div>
          <label><input type="checkbox" checked={!!custom}
            onchange={(e) => toggleOverride(skill, e.currentTarget.checked)} /> {skill}</label>
          {#if custom}
            <ol class="order">
              {#each fullOrder(custom) as name, i (name)}
                <li>
                  <span>{name}</span>
                  <button disabled={i === 0} onclick={() => config && (config.perSkillPriority = { ...config.perSkillPriority, [skill]: move(fullOrder(custom), i, -1) })}>↑</button>
                  <button disabled={i === roster.length - 1} onclick={() => config && (config.perSkillPriority = { ...config.perSkillPriority, [skill]: move(fullOrder(custom), i, 1) })}>↓</button>
                </li>
              {/each}
            </ol>
          {/if}
        </div>
      {/each}
    </div>

    <h3>Surplus and priorities</h3>
    <div class="row">
      <label><input type="checkbox" bind:checked={config.recycleSurplus} /> Recycle surplus tools</label>
      <label>Spares kept per tool <input type="number" min="0" bind:value={config.spares} /></label>
      <label>Sync priority <input type="number" bind:value={config.syncPriority} /></label>
      <label>Recycle priority <input type="number" bind:value={config.recyclePriority} /></label>
    </div>

    <div class="row">
      <button disabled={busy} onclick={save}>Save</button>
      <button disabled={busy} onclick={refresh}>Recompute plan</button>
    </div>
  </section>

  <section class="card">
    <h3>Current plan</h3>
    {#if !plan}
      <p class="muted">{config.enabled ? 'Not computed yet (engine down or starting up).' : 'Tool kits are disabled.'}</p>
    {:else}
      <p class="muted">Computed {ago(plan.computedAtMillis)}</p>
      <table>
        <thead>
          <tr><th>Character</th>{#each plan.skills as s}<th>{s}</th>{/each}<th>Pending</th></tr>
        </thead>
        <tbody>
          {#each Object.keys(plan.assignments) as name}
            <tr>
              <td>{name}</td>
              {#each plan.skills as s}<td>{plan.assignments[name][s] ?? '—'}</td>{/each}
              <td class="muted">{pending(name)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
      <h4>Surplus</h4>
      {#if Object.keys(plan.surplus).length === 0}
        <p class="muted">None.</p>
      {:else}
        <ul>
          {#each Object.entries(plan.surplus) as [code, qty]}<li>{qty}× {code}</li>{/each}
        </ul>
      {/if}
    {/if}
  </section>
{/if}

{#if focus}
  <section class="card">
    <h3>Gathering gear focus</h3>
    <p class="muted">
      Gathering and crafting gear always takes skill speed first (e.g. the altar doll). After that, gathering
      favours <b>prospecting</b> when the resource can drop one of these items as a rare drop (or the character
      no longer earns XP there), and <b>wisdom</b> otherwise. Individual gather tasks can override this.
    </p>
    <label>Prospect for (item codes, one per line)
      <textarea rows="8" bind:value={prospectText}></textarea>
    </label>
    <div class="row">
      <button disabled={busy} onclick={saveFocus}>Save</button>
      {#if focusMessage}<span class="muted">{focusMessage}</span>{/if}
    </div>
  </section>
{/if}

{#if food}
  <section class="card">
    <h3>Fight food</h3>
    <p class="muted">
      Characters learn, per monster, how much food they eat, how much loot drops and how much damage they take per
      fight. Every bank visit deposits loot and tops food up so food and inventory room run out together; they heal
      only when HP gets close to what the next fight could cost, and rest when that is quicker than eating or
      fetching food. Until a few fights are seen (or with learning off) they heal below 90% and carry heal + 25 food.
    </p>
    <div class="row">
      <label><input type="checkbox" bind:checked={food.adaptive} /> Learn food load and heal timing</label>
      <label><input type="checkbox" bind:checked={food.restWhenCheaper} /> Rest when cheaper</label>
    </div>
    <div class="row">
      <label title="Inventory kept free for this many fights' worth of drops">Loot room (fights) <input type="number" min="0" max="20" bind:value={food.lootReserveFights} /></label>
      <label>Min food <input type="number" min="0" bind:value={food.minFood} /></label>
      <label title="0 = no cap besides inventory room">Max food <input type="number" min="0" bind:value={food.maxFood} /></label>
      <label title="Heal once HP is at or below this × the worst recent damage taken in one fight">Heal safety × <input type="number" min="1" max="5" step="0.1" bind:value={food.healSafety} /></label>
    </div>
    <div class="row">
      <button disabled={busy} onclick={saveFood}>Save</button>
      {#if foodMessage}<span class="muted">{foodMessage}</span>{/if}
    </div>
  </section>
{/if}

<style>
  .card { border: 1px solid var(--border); border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; }
  .row { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; margin: 8px 0; }
  .order { padding-left: 20px; }
  .order li { display: flex; gap: 8px; align-items: center; margin: 2px 0; }
  .order li span { min-width: 120px; }
  .overrides { display: flex; gap: 24px; flex-wrap: wrap; }
  input[type='number'] { width: 70px; }
  textarea { display: block; width: 280px; margin-top: 4px; font-family: monospace; }
  table { border-collapse: collapse; width: 100%; }
  th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid var(--border); }
</style>
