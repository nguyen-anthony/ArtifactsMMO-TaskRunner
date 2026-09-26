/**
 * Live state shared by every page, fed by one Server-Sent Events connection (/api/stream).
 *
 * Svelte 5 note: `$state(...)` makes an object *reactive*. Any component that reads
 * `live.workers` re-renders automatically when we assign into it here. Files that use runes
 * outside components must end in `.svelte.ts`, which is why this file is named that way.
 */
import type { LogLine, WorkerStatus } from './types'
import { api } from './api'

export const live = $state({
  connected: false,
  /** character name -> latest status */
  workers: {} as Record<string, WorkerStatus>,
  /** newest last, capped */
  logs: [] as LogLine[],
  /** non-null when all workers are paused by a fatal API error (bad token) */
  paused: null as string | null,
  /** bumps whenever any task row changes; pages watch it to refetch lists */
  taskTick: 0,
  /** Bumps when the bank changes (server throttles to ≤1/s). */
  bankTick: 0,
})

const MAX_LOGS = 500
let source: EventSource | null = null

export async function connectStream() {
  if (source) return
  // Seed recent logs so the page isn't empty until the next line arrives.
  live.logs = await api.logs().catch(() => [])

  // EventSource reconnects by itself after network drops; it sends our cookie automatically.
  source = new EventSource('/api/stream')
  source.onopen = () => (live.connected = true)
  source.onerror = () => (live.connected = false)

  // Each `event:` name from the server gets its own listener.
  source.addEventListener('worker', (e) => {
    const s = JSON.parse((e as MessageEvent).data) as WorkerStatus
    live.workers[s.character] = s
  })
  source.addEventListener('log', (e) => {
    live.logs.push(JSON.parse((e as MessageEvent).data))
    if (live.logs.length > MAX_LOGS) live.logs.splice(0, live.logs.length - MAX_LOGS)
  })
  source.addEventListener('control', (e) => {
    live.paused = JSON.parse((e as MessageEvent).data).paused ?? null
  })
  // Task notifications can burst (one per row change); coalesce into one tick per 500 ms.
  let pending = false
  source.addEventListener('bank', () => { live.bankTick++ })
  source.addEventListener('task', () => {
    if (pending) return
    pending = true
    setTimeout(() => { pending = false; live.taskTick++ }, 500)
  })
}

export function disconnectStream() {
  source?.close()
  source = null
  live.connected = false
}
