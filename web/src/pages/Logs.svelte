<!-- Live log tail from the SSE stream, filterable by character. -->
<script lang="ts">
  import { live } from '../lib/live.svelte'
  import { time } from '../lib/format'

  let character = $state('')
  let follow = $state(true)
  let box: HTMLDivElement | undefined = $state()

  const characters = $derived([...new Set(live.logs.map((l) => l.character).filter(Boolean))] as string[])
  const shown = $derived(character ? live.logs.filter((l) => l.character === character) : live.logs)

  // After new lines render, scroll to the bottom if "follow" is on.
  $effect(() => {
    shown.length
    if (follow && box) box.scrollTop = box.scrollHeight
  })
</script>

<div class="row" style="margin-bottom:12px">
  <label>Character
    <select bind:value={character}>
      <option value="">All</option>
      {#each characters as c}<option>{c}</option>{/each}
    </select>
  </label>
  <label style="flex-direction:row;align-items:center"><input type="checkbox" bind:checked={follow} /> Follow</label>
</div>

<div class="panel box" bind:this={box}>
  {#each shown as l}
    <div class="log"><span class="muted">{time(l.timestampMillis)}</span> {#if l.character}<strong>[{l.character}]</strong>{/if} {l.message}</div>
  {/each}
</div>

<style>
  .box { height: 75vh; overflow-y: auto; }
</style>
