<!--
  Right-side drawer with a character's full game state (skin, equipment, stats, elements,
  effects, skills). The parent passes a preloaded profile; this component shows it as-is.
-->
<script lang="ts">
  import type { CharacterProfile } from '../lib/types'

  let { profile, loading = false, error = '', onclose }: {
    profile: CharacterProfile | null; loading?: boolean; error?: string; onclose: () => void
  } = $props()

  const IMG = 'https://artifactsmmo.com/images'
  const SLOTS = [
    'weapon', 'shield', 'helmet', 'body_armor', 'leg_armor', 'boots', 'ring1', 'ring2', 'amulet',
    'rune', 'bag', 'artifact1', 'artifact2', 'artifact3', 'utility1', 'utility2',
  ]
  const ELEMENTS = ['fire', 'earth', 'water', 'air']
  const SKILLS = ['mining', 'woodcutting', 'fishing', 'weaponcrafting', 'gearcrafting', 'jewelrycrafting', 'cooking', 'alchemy']
  const label = (s: string) => s.replace(/_/g, ' ')
  const pct = (xp: number, max: number) => (max > 0 ? Math.min(100, (xp / max) * 100) : 100)

  const core = $derived(profile ? [
    ['HP', `${profile.hp} / ${profile.max_hp}`], ['Damage', `${profile.dmg}%`], ['Haste', profile.haste],
    ['Critical strike', `${profile.critical_strike}%`], ['Initiative', profile.initiative], ['Threat', profile.threat],
    ['Wisdom', profile.wisdom], ['Prospecting', profile.prospecting], ['Speed', profile.speed], ['Gold', profile.gold],
  ] as [string, string | number][] : [])
  const invUsed = $derived(profile ? profile.inventory.reduce((n, s) => n + s.quantity, 0) : 0)

  function onkeydown(e: KeyboardEvent) { if (e.key === 'Escape') onclose() }
</script>

<svelte:window {onkeydown} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="backdrop" onclick={onclose}></div>
<aside class="drawer panel">
  <div class="head">
    <strong>{profile?.name ?? ''}</strong>
    <span class="muted small">{loading ? 'refreshing…' : ''}</span>
    <button onclick={onclose}>✕</button>
  </div>
  {#if error}<p class="error">{error}</p>{/if}

  {#if profile}
    <section class="top">
      <img class="skin" src="{IMG}/characters/{profile.skin}.png" alt={profile.skin} />
      <div class="grow">
        <div>Level <strong>{profile.level}</strong> <span class="muted small">{profile.xp} / {profile.max_xp} xp</span></div>
        <div class="bar"><div style="width:{pct(profile.xp, profile.max_xp)}%"></div></div>
        <div class="small">Inventory {invUsed} / {profile.inventory_max_items}</div>
        {#if profile.task}
          <div class="small">Task ({profile.task_type}): {profile.task} {profile.task_progress} / {profile.task_total}</div>
        {/if}
      </div>
    </section>

    <h4>Equipment</h4>
    <div class="equip">
      {#each SLOTS as slot}
        {@const code = profile[`${slot}_slot`] as string}
        <div class="slot" title="{label(slot)}{code ? `: ${code}` : ''}">
          {#if code}
            <img src="{IMG}/items/{code}.png" alt={code} />
            {#if slot.startsWith('utility')}<span class="qty">{profile[`${slot}_slot_quantity`]}</span>{/if}
          {:else}<span class="muted tiny">{label(slot)}</span>{/if}
        </div>
      {/each}
    </div>

    <h4>Stats</h4>
    <table class="compact">
      <tbody>
        {#each core as [k, v]}<tr><td class="muted">{k}</td><td>{v}</td></tr>{/each}
      </tbody>
    </table>

    <h4>Elements</h4>
    <table class="compact">
      <thead><tr><th></th><th>Attack</th><th>Damage %</th><th>Resist %</th></tr></thead>
      <tbody>
        {#each ELEMENTS as el}
          <tr class={el}><td>{el}</td><td>{profile[`attack_${el}`]}</td><td>{profile[`dmg_${el}`]}</td><td>{profile[`res_${el}`]}</td></tr>
        {/each}
      </tbody>
    </table>

    {#if profile.effects.length}
      <h4>Effects</h4>
      <table class="compact">
        <tbody>{#each profile.effects as e}<tr><td class="muted">{label(e.code)}</td><td>{e.value}</td></tr>{/each}</tbody>
      </table>
    {/if}

    <h4>Skills</h4>
    {#each SKILLS as sk}
      <div class="skill">
        <span>{sk}</span><strong>{profile[`${sk}_level`]}</strong>
        <div class="bar"><div style="width:{pct(profile[`${sk}_xp`], profile[`${sk}_max_xp`])}%"></div></div>
      </div>
    {/each}
  {:else if loading}
    <p class="muted">Loading…</p>
  {/if}
</aside>

<style>
  .backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, .4); z-index: 10; }
  .drawer {
    position: fixed; top: 0; right: 0; bottom: 0; width: min(440px, 100vw); overflow-y: auto;
    z-index: 11; border-radius: 0; display: flex; flex-direction: column; gap: 6px;
  }
  .head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
  .head strong { font-size: 16px; }
  h4 { margin: 10px 0 2px; color: var(--muted); font-weight: 500; }
  .top { display: flex; gap: 12px; align-items: center; }
  .skin { width: 72px; height: 72px; image-rendering: pixelated; object-fit: contain; }
  .grow { flex: 1; display: flex; flex-direction: column; gap: 4px; }
  .bar { height: 6px; background: var(--bg); border-radius: 3px; overflow: hidden; }
  .bar div { height: 100%; background: var(--accent); }
  .equip { display: grid; grid-template-columns: repeat(auto-fill, 52px); gap: 6px; }
  .slot {
    position: relative; width: 52px; height: 52px; border: 1px solid var(--border); border-radius: 6px;
    display: flex; align-items: center; justify-content: center; background: var(--bg); text-align: center;
  }
  .slot img { width: 44px; height: 44px; }
  .qty { position: absolute; right: 2px; bottom: 0; font-size: 11px; }
  .compact td, .compact th { padding: 3px 6px; }
  .skill { display: grid; grid-template-columns: 1fr 32px 120px; gap: 8px; align-items: center; }
  .small { font-size: 12px; }
  .tiny { font-size: 9px; }
  .fire td:first-child { color: #ff7a59; }
  .earth td:first-child { color: #c9a26b; }
  .water td:first-child { color: #5aa9ff; }
  .air td:first-child { color: #9be3c4; }
</style>
