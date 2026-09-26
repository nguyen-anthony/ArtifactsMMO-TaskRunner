<!--
  Login screen. `$props()` is how a Svelte 5 component receives inputs from its parent;
  here the parent passes an `onLogin` callback.
-->
<script lang="ts">
  import { api, ApiError } from '../lib/api'
  import type { Me } from '../lib/types'

  let { onLogin }: { onLogin: (m: Me) => void } = $props()

  let key = $state('')
  let error = $state('')
  let busy = $state(false)

  async function submit(e: SubmitEvent) {
    e.preventDefault() // stop the browser's default full-page form submit
    busy = true; error = ''
    try {
      onLogin(await api.login(key))
    } catch (err) {
      error = err instanceof ApiError ? err.message : 'Login failed'
    } finally {
      busy = false
    }
  }
</script>

<form class="panel" onsubmit={submit}>
  <h2>ArtifactsMMO TaskRunner</h2>
  <!-- bind:value keeps `key` and the input's text in sync both ways -->
  <label>Admin key <input type="password" bind:value={key} autocomplete="current-password" /></label>
  <button class="primary" disabled={busy || !key}>Log in</button>
  {#if error}<p class="error">{error}</p>{/if}
</form>

<style>
  form { max-width: 360px; margin: 15vh auto; display: flex; flex-direction: column; gap: 12px; }
  h2 { margin: 0; }
</style>
