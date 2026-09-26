<!--
  Root component: decides between the login screen and the app, and does hash-based routing
  (#/dashboard, #/queue, ...). Hash routing needs no server config: Caddy just serves
  index.html and the part after '#' never reaches the server.

  Svelte 5 runes used here:
    $state   – a reactive variable; assigning to it re-renders whatever reads it.
    $effect  – runs after render and again whenever the reactive values it read change.
-->
<script lang="ts">
  import { api, setUnauthorizedHandler } from './lib/api'
  import { live, connectStream, disconnectStream } from './lib/live.svelte'
  import Login from './pages/Login.svelte'
  import Dashboard from './pages/Dashboard.svelte'
  import Queue from './pages/Queue.svelte'
  import NewTask from './pages/NewTask.svelte'
  import Characters from './pages/Characters.svelte'
  import Configs from './pages/Configs.svelte'
  import Bank from './pages/Bank.svelte'
  import Logs from './pages/Logs.svelte'

  // Map of route -> component. The nav bar is generated from this too.
  const pages = {
    dashboard: { title: 'Dashboard', component: Dashboard },
    queue: { title: 'Queue', component: Queue },
    new: { title: 'New task', component: NewTask },
    characters: { title: 'Characters', component: Characters },
    configs: { title: 'Events & raids', component: Configs },
    bank: { title: 'Bank', component: Bank },
    logs: { title: 'Logs', component: Logs },
  } as const
  type Route = keyof typeof pages

  let loggedIn = $state<boolean | null>(null) // null = still checking
  let engineRunning = $state(true)
  let route = $state<Route>(currentRoute())

  function currentRoute(): Route {
    const r = location.hash.replace(/^#\/?/, '') as Route
    return r in pages ? r : 'dashboard'
  }
  window.addEventListener('hashchange', () => (route = currentRoute()))

  // Any 401 from the API means the session expired: drop back to login.
  setUnauthorizedHandler(() => { loggedIn = false; disconnectStream() })

  api.me().then((m) => { loggedIn = m.loggedIn; engineRunning = m.engineRunning; live.paused = m.paused ?? null })
    .catch(() => (loggedIn = false))

  // Open the live stream once logged in (the effect re-runs when `loggedIn` changes).
  $effect(() => { if (loggedIn) connectStream() })

  async function logout() {
    await api.logout()
    disconnectStream()
    loggedIn = false
  }

  async function resume() { await api.resume(); live.paused = null }

  // `{@const}`-free way to pick the component for the current route.
  let Page = $derived(pages[route].component)
</script>

{#if loggedIn === null}
  <p class="muted" style="padding:24px">Loading…</p>
{:else if !loggedIn}
  <Login onLogin={(m) => { loggedIn = true; engineRunning = m.engineRunning }} />
{:else}
  <nav>
    <strong>TaskRunner</strong>
    {#each Object.entries(pages) as [key, p]}
      <a href="#/{key}" class:active={route === key}>{p.title}</a>
    {/each}
    <span class="spacer"></span>
    <span class="muted" title="live stream">{live.connected ? '● live' : '○ reconnecting'}</span>
    <button onclick={logout}>Log out</button>
  </nav>

  {#if !engineRunning}
    <div class="banner warn">Engine not running (no ARTIFACTS_TOKEN or waiting for the instance lock). Queue edits still work.</div>
  {/if}
  {#if live.paused}
    <div class="banner bad">
      All workers paused: {live.paused}
      <button onclick={resume}>Resume</button>
    </div>
  {/if}

  <main><Page /></main>
{/if}

<style>
  nav { display: flex; gap: 16px; align-items: center; padding: 10px 16px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  nav a { color: var(--muted); text-decoration: none; }
  nav a.active { color: var(--text); border-bottom: 2px solid var(--accent); }
  .spacer { flex: 1; }
  main { padding: 16px; max-width: 1400px; margin: 0 auto; }
  .banner { padding: 8px 16px; display: flex; gap: 12px; align-items: center; }
  .banner.warn { background: #3a2f0f; }
  .banner.bad { background: #3b1414; }
</style>
