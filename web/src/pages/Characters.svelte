<!--
  Per-character settings: enabled flag and the default (filler) task that runs when the
  queue has nothing for this character. Also shows inventory/equipment on demand.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import SpecEditor from '../components/SpecEditor.svelte'
  import { describeSpec } from '../lib/format'
  import type { CharacterDto, TaskSpec } from '../lib/types'

  let characters = $state<CharacterDto[]>([])
  let editing = $state<string | null>(null)
  let draft = $state<TaskSpec | null>(null)
  let details = $state<Record<string, any> | null>(null)
  let error = $state('')

  const load = () => api.characters().then((c) => (characters = c)).catch((e) => (error = e.message))
  load()

  async function save(c: CharacterDto, patch: Partial<CharacterDto['settings']>) {
    try {
      await api.saveSettings(c.name, { ...c.settings, ...patch })
      editing = null
      await load()
    } catch (e: any) { error = e.message }
  }

  async function show(name: string) {
    details = await api.characterDetails(name)
  }
</script>

{#if error}<p class="error">{error}</p>{/if}

<div class="panel">
  <table>
    <thead><tr><th>Character</th><th>Enabled</th><th>Filler task</th><th></th></tr></thead>
    <tbody>
      {#each characters as c (c.name)}
        <tr>
          <td><a href={'#/characters'} onclick={() => show(c.name)}>{c.name}</a></td>
          <td><input type="checkbox" checked={c.settings.enabled} onchange={(e) => save(c, { enabled: e.currentTarget.checked })} /></td>
          <td>
            {#if editing === c.name}
              <SpecEditor bind:spec={draft} character={c.name} who={c} />
              <div class="row" style="margin-top:8px">
                <button class="primary" disabled={!draft} onclick={() => save(c, { filler: draft })}>Save</button>
                <button onclick={() => (editing = null)}>Cancel</button>
              </div>
            {:else}
              {c.settings.filler ? describeSpec(c.settings.filler) : '—'}
            {/if}
          </td>
          <td>
            {#if editing !== c.name}
              <button onclick={() => { draft = c.settings.filler ?? null; editing = c.name }}>Edit filler</button>
              {#if c.settings.filler}<button class="danger" onclick={() => save(c, { filler: null })}>Clear</button>{/if}
            {/if}
          </td>
        </tr>
      {/each}
    </tbody>
  </table>
</div>

{#if details}
  <div class="panel" style="margin-top:12px">
    <h3>{details.name} — lv {details.level} · HP {details.hp}/{details.max_hp} · {details.gold} gold · ({details.x},{details.y})</h3>
    <div class="row" style="align-items:start">
      <div>
        <h4>Inventory</h4>
        {#each (details.inventory ?? []).filter((s: any) => s.quantity > 0) as s}<div>{s.quantity}× {s.code}</div>{/each}
      </div>
      <div>
        <h4>Equipment</h4>
        {#each Object.entries(details).filter(([k, v]) => k.endsWith('_slot') && v) as [k, v]}
          <div><span class="muted">{k.replace('_slot', '')}</span> {v}</div>
        {/each}
      </div>
    </div>
  </div>
{/if}
