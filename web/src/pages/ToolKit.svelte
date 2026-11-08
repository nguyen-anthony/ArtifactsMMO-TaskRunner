<!--
  Tool kit: every character carries one tool per managed skill. When good tools are scarce,
  characters earlier in the claim order get them first (optionally per skill). The engine
  queues a short `toolkit_sync` bank trip for characters whose kit can change and recycles
  tools nobody needs anymore (every character already has one at least as good).
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { ago } from '../lib/format'
  import { TOOL_SKILLS, type ToolKitConfig, type ToolKitPlan } from '../lib/types'

  let config = $state<ToolKitConfig | null>(null)
  let plan = $state<ToolKitPlan | null>(null)
  let roster = $state<string[]>([])
  let message = $state('')
  let error = $state('')
  let busy = $state(false)

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

<style>
  .card { border: 1px solid var(--border); border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; }
  .row { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; margin: 8px 0; }
  .order { padding-left: 20px; }
  .order li { display: flex; gap: 8px; align-items: center; margin: 2px 0; }
  .order li span { min-width: 120px; }
  .overrides { display: flex; gap: 24px; flex-wrap: wrap; }
  input[type='number'] { width: 70px; }
  table { border-collapse: collapse; width: 100%; }
  th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid var(--border); }
</style>
