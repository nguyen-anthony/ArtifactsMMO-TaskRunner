<!--
  Event and raid configs. Each config is a small JSON document; editing is a JSON textarea
  with a template, which keeps this page simple while the shapes settle.
-->
<script lang="ts">
  import { api } from '../lib/api'

  type Kind = 'event' | 'raid'
  const templates: Record<Kind, object> = {
    event: { eventCode: '', enabled: true, eligibleCharacters: [], itemsToSell: [], itemsToBuy: [], designatedTrader: null, minWinRate: 0.9 },
    raid: { raidCode: '', enabled: true, initiatorName: '', participantNames: [], tankOverride: null, leadTimeMinutes: 5 },
  }
  const keyField: Record<Kind, string> = { event: 'eventCode', raid: 'raidCode' }

  let kind = $state<Kind>('event')
  let entries = $state<Record<string, any>>({})
  let text = $state('')
  let error = $state('')

  $effect(() => { load(kind) })

  async function load(k: Kind) {
    entries = await api.configs(k).catch((e) => { error = e.message; return {} })
    text = JSON.stringify(templates[k], null, 2)
  }

  async function save() {
    error = ''
    try {
      const value = JSON.parse(text)
      const key = value[keyField[kind]]
      if (!key) throw new Error(`${keyField[kind]} is required`)
      await api.saveConfig(kind, key, value)
      await load(kind)
    } catch (e: any) { error = e.message }
  }

  async function remove(key: string) {
    await api.deleteConfig(kind, key)
    await load(kind)
  }
</script>

<div class="row" style="margin-bottom:12px">
  <button class:primary={kind === 'event'} onclick={() => (kind = 'event')}>Events</button>
  <button class:primary={kind === 'raid'} onclick={() => (kind = 'raid')}>Raids</button>
</div>

<div class="grid">
  {#each Object.entries(entries) as [key, value]}
    <div class="panel">
      <div class="row" style="justify-content:space-between">
        <strong>{key}</strong>
        <span class="badge {value.enabled ? 'running' : 'cancelled'}">{value.enabled ? 'enabled' : 'disabled'}</span>
      </div>
      <pre class="log">{JSON.stringify(value, null, 2)}</pre>
      <div class="row">
        <button onclick={() => (text = JSON.stringify(value, null, 2))}>Edit</button>
        <button class="danger" onclick={() => remove(key)}>Delete</button>
      </div>
    </div>
  {/each}
</div>

<div class="panel" style="margin-top:12px">
  <h4>Add / update {kind} config</h4>
  <textarea rows="12" bind:value={text}></textarea>
  <div class="row" style="margin-top:8px">
    <button class="primary" onclick={save}>Save</button>
    {#if error}<span class="error">{error}</span>{/if}
  </div>
</div>
