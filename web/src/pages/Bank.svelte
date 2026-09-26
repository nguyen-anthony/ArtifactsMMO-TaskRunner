<!-- Bank contents with a search box, plus quick "withdraw to character" and API rate usage. -->
<script lang="ts">
  import { api } from '../lib/api'
  import { live } from '../lib/live.svelte'
  import type { CharacterDto, RateWindow } from '../lib/types'

  let items = $state<{ code: string; quantity: number }[]>([])
  let rates = $state<Record<string, RateWindow[]>>({})
  let characters = $state<CharacterDto[]>([])
  let filter = $state('')
  let target = $state('')
  let message = $state('')
  let error = $state('')

  // Re-read whenever the stream reports a bank change (reading live.bankTick subscribes).
  $effect(() => {
    live.bankTick
    api.bank().then((b) => (items = b)).catch((e) => (error = e.message))
  })
  api.characters().then((c) => { characters = c; target = c[0]?.name ?? '' })
  const loadRates = () => api.rates().then((r) => (rates = r.buckets)).catch(() => {})
  loadRates()
  $effect(() => { const t = setInterval(loadRates, 5000); return () => clearInterval(t) })

  // $derived recomputes only when `items` or `filter` change.
  const shown = $derived(items.filter((i) => i.code.includes(filter.toLowerCase())))

  async function withdraw(code: string) {
    const q = Number(prompt(`Withdraw how many ${code} to ${target}?`, '1'))
    if (!q) return
    try {
      const t = await api.createTask({ spec: { kind: 'bank_withdraw', itemCode: code, quantity: q }, assignedCharacter: target, priority: 60 })
      message = `Queued withdraw task #${t.id}`
    } catch (e: any) { error = e.message }
  }

  const windowLabel = (ms: number) => (ms >= 3_600_000 ? '/h' : ms >= 60_000 ? '/min' : '/s')
</script>

<div class="panel" style="margin-bottom:12px">
  <strong>API rate usage</strong>
  <div class="row">
    {#each Object.entries(rates) as [bucket, windows]}
      <div><span class="muted">{bucket}</span> {windows.map((w) => `${w.used}/${w.limit}${windowLabel(w.windowMillis)}`).join(' · ')}</div>
    {/each}
  </div>
</div>

<div class="row" style="margin-bottom:12px">
  <label>Search <input bind:value={filter} placeholder="item code" /></label>
  <label>Withdraw to
    <select bind:value={target}>{#each characters as c}<option>{c.name}</option>{/each}</select>
  </label>
  {#if message}<span>{message}</span>{/if}
  {#if error}<span class="error">{error}</span>{/if}
</div>

<div class="panel">
  <table>
    <thead><tr><th>Item</th><th>Quantity</th><th></th></tr></thead>
    <tbody>
      {#each shown as i (i.code)}
        <tr><td>{i.code}</td><td>{i.quantity}</td><td><button onclick={() => withdraw(i.code)}>Withdraw</button></td></tr>
      {/each}
    </tbody>
  </table>
</div>
