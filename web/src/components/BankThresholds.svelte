<!--
  Bank restock rules: when an item drops below its minimum in the bank, the engine queues an
  unassigned task (craft from bank ingredients, or gather N times) for any capable character.
-->
<script lang="ts">
  import { api } from '../lib/api'
  import { catalog } from '../lib/catalog'
  import type { BankThreshold, ThresholdsState } from '../lib/types'

  let { editCode = $bindable(null), characters = [] }: { editCode?: string | null; characters?: string[] } = $props()

  let data = $state<ThresholdsState | null>(null)
  let items = $state<{ code: string; name: string; craftSkill?: string }[]>([])
  let resources = $state<{ code: string; name: string }[]>([])
  let error = $state('')
  let message = $state('')
  let draft = $state<BankThreshold | null>(null)
  let isNew = $state(false)

  const load = (p: Promise<ThresholdsState>, msg = '') =>
    p.then((d) => { data = d; error = ''; message = msg }).catch((e) => (error = e.message))
  load(api.thresholds())
  catalog().then((c) => { items = c.items; resources = c.resources }).catch(() => {})

  const itemByCode = $derived(Object.fromEntries(items.map((i) => [i.code, i])))

  function blank(code: string): BankThreshold {
    const craftable = !!itemByCode[code]?.craftSkill
    return {
      itemCode: code, minQuantity: 100, action: craftable ? 'craft' : 'gather', batch: craftable ? 500 : 1000,
      priority: null, enabled: true, resourceCode: null, allowedCharacters: [], reserve: true,
    }
  }

  function edit(code: string) {
    const existing = data?.rules.find((r) => r.itemCode === code)
    isNew = !existing
    draft = existing ? structuredClone($state.snapshot(existing)) : blank(code)
  }

  // The Bank table's "Threshold" button sets editCode.
  $effect(() => {
    if (editCode && data) { edit(editCode); editCode = null }
  })

  async function save() {
    if (!draft) return
    const d = $state.snapshot(draft)
    if (!d.itemCode) { error = 'Pick an item'; return }
    d.priority = d.priority === null || (d.priority as unknown) === '' ? null : Number(d.priority)
    await load(api.saveThreshold(d), `Saved ${d.itemCode}`)
    if (!error) draft = null
  }

  async function remove(code: string) {
    if (!confirm(`Delete the threshold for ${code}?`)) return
    await load(api.deleteThreshold(code), `Deleted ${code}`)
  }

  async function toggle(r: BankThreshold) {
    await load(api.saveThreshold({ ...$state.snapshot(r), enabled: !r.enabled }))
  }

  async function saveSettings() {
    if (!data) return
    await load(api.saveThresholdSettings($state.snapshot(data.settings)), 'Settings saved')
  }

  function toggleChar(name: string, on: boolean) {
    if (!draft) return
    draft.allowedCharacters = on ? [...draft.allowedCharacters, name] : draft.allowedCharacters.filter((c) => c !== name)
  }
</script>

<div class="panel" style="margin-bottom:12px">
  <div class="row" style="justify-content:space-between">
    <strong>Restock thresholds</strong>
    <div class="row">
      <button onclick={() => { isNew = true; draft = blank('') }}>Add threshold</button>
      <button onclick={() => load(api.refreshThresholds(), 'Re-evaluated')}>Check now</button>
    </div>
  </div>
  {#if error}<div class="error">{error}</div>{/if}
  {#if message}<div class="muted">{message}</div>{/if}

  {#if data}
    <div class="row">
      <label><input type="checkbox" bind:checked={data.settings.enabled} /> Auto-restock enabled</label>
      <label>Default priority <input type="number" style="width:60px" bind:value={data.settings.defaultPriority} /></label>
      <label>Retry after failure (min) <input type="number" style="width:60px" bind:value={data.settings.failureBackoffMinutes} /></label>
      <button onclick={saveSettings}>Save settings</button>
    </div>

    {#if draft}
      <div class="panel editor">
        <div class="row">
          <label>Item
            {#if isNew}
              <input list="threshold-items" bind:value={draft.itemCode} placeholder="item code"
                onchange={() => { if (draft) draft = { ...blank(draft.itemCode), itemCode: draft.itemCode } }} />
              <datalist id="threshold-items">{#each items as i}<option value={i.code}>{i.name}</option>{/each}</datalist>
            {:else}<strong>{draft.itemCode}</strong>{/if}
          </label>
          <label>Below <input type="number" min="0" style="width:80px" bind:value={draft.minQuantity} /></label>
          <label>Action
            <select bind:value={draft.action}>
              <option value="craft">craft from bank</option>
              <option value="gather">gather</option>
            </select>
          </label>
          <label>{draft.action === 'craft' ? 'Craft up to' : 'Gathers'}
            <input type="number" min="1" style="width:80px" bind:value={draft.batch} /></label>
          {#if draft.action === 'gather'}
            <label>Resource
              <select bind:value={draft.resourceCode}>
                <option value={null}>auto</option>
                {#each resources as r}<option value={r.code}>{r.name}</option>{/each}
              </select>
            </label>
          {/if}
          <label>Priority <input type="number" style="width:60px" placeholder={String(data.settings.defaultPriority)}
            bind:value={draft.priority} /></label>
          <label title="Task Master won't take this item from the bank below the threshold">
            <input type="checkbox" bind:checked={draft.reserve} /> Reserve for restock</label>
        </div>
        <div class="row">
          <span class="muted">Characters (none = anyone):</span>
          {#each characters as c}
            <label><input type="checkbox" checked={draft.allowedCharacters.includes(c)}
              onchange={(e) => toggleChar(c, e.currentTarget.checked)} /> {c}</label>
          {/each}
        </div>
        <div class="row">
          <button onclick={save}>Save</button>
          <button onclick={() => (draft = null)}>Cancel</button>
        </div>
      </div>
    {/if}

    {#if data.rules.length}
      <table>
        <thead><tr><th>Item</th><th>Bank</th><th>Below</th><th>Action</th><th>Priority</th><th>Status</th><th></th></tr></thead>
        <tbody>
          {#each data.rules as r (r.itemCode)}
            {@const s = data.statuses[r.itemCode]}
            <tr class:disabled={!r.enabled}>
              <td>{r.itemCode}{r.reserve ? ' 🔒' : ''}</td>
              <td>{s?.bankQuantity ?? '—'}</td>
              <td>{r.minQuantity}</td>
              <td>{r.action === 'craft' ? `craft ≤${r.batch}` : `gather ×${r.batch}${r.resourceCode ? ` (${r.resourceCode})` : ''}`}</td>
              <td>{r.priority ?? `${data.settings.defaultPriority} (default)`}</td>
              <td class="state-{s?.state ?? 'none'}">
                {s?.state ?? 'not checked'}{s?.taskId ? ` #${s.taskId}` : ''}{s?.detail ? ` — ${s.detail}` : ''}
              </td>
              <td>
                <button onclick={() => edit(r.itemCode)}>Edit</button>
                <button onclick={() => toggle(r)}>{r.enabled ? 'Disable' : 'Enable'}</button>
                <button onclick={() => remove(r.itemCode)}>Delete</button>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    {:else}
      <p class="muted">No thresholds yet. Use "Add threshold" or the Threshold button on a bank row.</p>
    {/if}
  {/if}
</div>

<style>
  .editor { margin: 8px 0; }
  tr.disabled { opacity: 0.5; }
  .state-blocked, .state-backoff { color: var(--warn, #d9822b); }
  .state-queued { color: var(--accent, #3b82f6); }
</style>
