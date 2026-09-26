<!--
  Create a task (solo) or a boss group. Solo tasks go to one character or "any eligible";
  boss groups pick 1-3 members, optionally run the co-op optimizer, then enqueue a group.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { catalog } from '../lib/catalog'
  import SpecEditor from '../components/SpecEditor.svelte'
  import type { Catalog, CharacterDto, MemberPlan, StopCondition, TaskSpec } from '../lib/types'
  import { SKILLS } from '../lib/types'

  let mode = $state<'solo' | 'boss'>('solo')
  let characters = $state<CharacterDto[]>([])
  api.characters().then((c) => (characters = c))

  // ── Solo ──
  let spec = $state<TaskSpec | null>(null)
  let assigned = $state('')
  let priority = $state(50)
  let stopKind = $state<'none' | 'count' | 'skill_level' | 'minutes'>('none')
  let stopCount = $state(100)
  let stopSkill = $state<string>('mining')
  let stopLevel = $state(10)
  let stopMinutes = $state(60)
  let message = $state('')
  let error = $state('')

  function stopCondition(): StopCondition | undefined {
    switch (stopKind) {
      case 'count': return { kind: 'count', target: stopCount }
      case 'skill_level': return { kind: 'skill_level', skill: stopSkill, level: stopLevel }
      case 'minutes': return { kind: 'until', epochMillis: Date.now() + stopMinutes * 60_000 }
      default: return undefined
    }
  }

  async function createSolo() {
    if (!spec) return
    error = ''; message = ''
    try {
      const t = await api.createTask({ spec, priority, assignedCharacter: assigned || undefined, stopCondition: stopCondition() })
      message = `Queued task #${t.id}`
    } catch (e: any) { error = e.message }
  }

  // ── Boss group ──
  let cat = $state<Catalog | null>(null)
  catalog().then((c) => (cat = c))
  let bossCode = $state('')
  let members = $state<string[]>(['', '', ''])
  let plans = $state<Record<string, MemberPlan>>({})
  let planning = $state(false)
  const chosen = $derived(members.filter(Boolean))

  async function planCoop() {
    planning = true; error = ''
    try { plans = await api.simCoop(chosen, bossCode) } catch (e: any) { error = e.message } finally { planning = false }
  }

  async function createBoss() {
    error = ''; message = ''
    const monster = cat?.monsters.find((m) => m.code === bossCode)
    try {
      await api.createGroup({
        spec: { kind: 'boss_fight', monsterCode: bossCode, monsterName: monster?.name, plans },
        // First slot = initiator (calls the fight endpoint with the others as participants).
        slots: chosen.map((c) => ({ assignedCharacter: c })),
        priority, stopCondition: stopCondition(),
      })
      message = `Queued boss group for ${chosen.join(', ')}`
    } catch (e: any) { error = e.message }
  }
</script>

<div class="row" style="margin-bottom:12px">
  <button class:primary={mode === 'solo'} onclick={() => (mode = 'solo')}>Task</button>
  <button class:primary={mode === 'boss'} onclick={() => (mode = 'boss')}>Boss group</button>
</div>

<div class="panel stack">
  {#if mode === 'solo'}
    <div class="row">
      <label>Character
        <select bind:value={assigned}>
          <option value="">Any eligible</option>
          {#each characters as c}<option value={c.name}>{c.name}</option>{/each}
        </select>
      </label>
    </div>
    <!-- `character`: whose gear "Optimize gear" uses. `who`: that character's levels, used
         to filter the pickers (none selected = "Any eligible" = only unreachable content hidden). -->
    <SpecEditor bind:spec character={assigned} who={characters.find((c) => c.name === assigned) ?? null} />
  {:else}
    <div class="row">
      <label>Boss
        <select bind:value={bossCode}>
          <option value="">— choose —</option>
          {#each cat?.monsters.filter((m) => m.type !== 'normal') ?? [] as m}<option value={m.code}>{m.name} (lv {m.level})</option>{/each}
        </select>
      </label>
      {#each members as _, i}
        <label>{i === 0 ? 'Initiator' : `Participant ${i}`}
          <select bind:value={members[i]}>
            <option value="">—</option>
            {#each characters as c}<option value={c.name} disabled={members.includes(c.name) && members[i] !== c.name}>{c.name}</option>{/each}
          </select>
        </label>
      {/each}
      <button onclick={planCoop} disabled={!bossCode || !members[0] || planning}>{planning ? 'Planning…' : 'Plan gear (co-op optimizer)'}</button>
    </div>
    {#if Object.keys(plans).length}
      <pre class="log">{JSON.stringify(plans, null, 2)}</pre>
    {/if}
  {/if}

  <div class="row">
    <label>Priority <input type="number" bind:value={priority} style="width:80px" /></label>
    <label>Stop when
      <select bind:value={stopKind}>
        <option value="none">Never (until cancelled)</option>
        <option value="count">After N (gathers / wins / crafts)</option>
        <option value="skill_level">Skill reaches level</option>
        <option value="minutes">After N minutes</option>
      </select>
    </label>
    {#if stopKind === 'count'}<label>N <input type="number" bind:value={stopCount} /></label>{/if}
    {#if stopKind === 'skill_level'}
      <label>Skill <select bind:value={stopSkill}><option>combat</option>{#each SKILLS as s}<option>{s}</option>{/each}</select></label>
      <label>Level <input type="number" bind:value={stopLevel} /></label>
    {/if}
    {#if stopKind === 'minutes'}<label>Minutes <input type="number" bind:value={stopMinutes} /></label>{/if}
  </div>

  <div class="row">
    {#if mode === 'solo'}
      <button class="primary" disabled={!spec} onclick={createSolo}>Queue task</button>
    {:else}
      <button class="primary" disabled={!bossCode || !members[0]} onclick={createBoss}>Queue boss group</button>
    {/if}
    {#if message}<span>{message} — <a href="#/queue">view queue</a></span>{/if}
    {#if error}<span class="error">{error}</span>{/if}
  </div>
</div>

<style>
  .stack { display: flex; flex-direction: column; gap: 16px; }
</style>
