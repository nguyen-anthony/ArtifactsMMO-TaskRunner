/**
 * Tiny fetch wrapper for the backend. Every call is same-origin, so the browser sends the
 * HttpOnly session cookie automatically; we never see or store the key/token in JS.
 */
import type {
  Catalog, CharacterDto, CharacterSettings, LogLine, Me, MemberPlan, QueuedTask, RateWindow,
  StopCondition, TaskEvent, TaskRequirements, TaskSpec, EquipPlan, UtilityPlan, Craftable,
} from './types'

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

/** Set by App.svelte: called on any 401 so the UI can fall back to the login screen. */
export let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (res.status === 401 && path !== '/login') onUnauthorized()
  if (!res.ok) {
    // Backend errors look like {"error": "..."}.
    const msg = await res.json().then((j) => j.error as string).catch(() => res.statusText)
    throw new ApiError(res.status, msg)
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}

export interface CreateTask {
  spec: TaskSpec; priority?: number; assignedCharacter?: string
  requirements?: TaskRequirements; stopCondition?: StopCondition
}

export const api = {
  me: () => request<Me>('GET', '/me'),
  login: (key: string) => request<Me>('POST', '/login', { key }),
  logout: () => request<void>('POST', '/logout'),

  tasks: (query = 'status=pending,claimed,running,suspended') => request<QueuedTask[]>('GET', `/tasks?${query}`),
  task: (id: number) => request<QueuedTask>('GET', `/tasks/${id}`),
  taskEvents: (id: number) => request<TaskEvent[]>('GET', `/tasks/${id}/events`),
  createTask: (t: CreateTask) => request<QueuedTask>('POST', '/tasks', t),
  createGroup: (g: { spec: TaskSpec; slots: { assignedCharacter?: string }[]; priority?: number; stopCondition?: StopCondition }) =>
    request<unknown>('POST', '/tasks/group', g),
  cancelTask: (id: number) => request<void>('POST', `/tasks/${id}/cancel`, { reason: 'cancelled from web UI' }),

  characters: () => request<CharacterDto[]>('GET', '/characters'),
  characterDetails: (name: string) => request<Record<string, any>>('GET', `/characters/${name}/details`),
  saveSettings: (name: string, s: CharacterSettings) => request<CharacterSettings>('PUT', `/characters/${name}/settings`, s),

  configs: (kind: 'event' | 'raid') => request<Record<string, any>>('GET', `/configs/${kind}`),
  saveConfig: (kind: string, key: string, value: unknown) => request<void>('PUT', `/configs/${kind}/${key}`, value),
  deleteConfig: (kind: string, key: string) => request<void>('DELETE', `/configs/${kind}/${key}`),

  bank: () => request<{ code: string; quantity: number }[]>('GET', '/bank'),
  rates: () => request<{ buckets: Record<string, RateWindow[]> }>('GET', '/rates'),
  logs: (character?: string) => request<LogLine[]>('GET', `/logs?limit=300${character ? `&character=${character}` : ''}`),
  resume: () => request<void>('POST', '/control/resume'),

  content: () => request<Catalog>('GET', '/content'),
  /** Recipes + max craftable. No character = bank only. `all` = include above-level recipes. */
  craftable: (character: string, all = false) =>
    request<Craftable[]>('GET', `/craftable?skill=all&all=${all}${character ? `&character=${encodeURIComponent(character)}` : ''}`),
  simLoadout: (character: string, monsterCode: string) =>
    request<{ baselineWinRate: number; winRate: number; equip: EquipPlan[]; utilities: UtilityPlan[] }>(
      'POST', '/sim/loadout', { character, monsterCode }),
  simCoop: (members: string[], monsterCode: string) => request<Record<string, MemberPlan>>('POST', '/sim/coop', { members, monsterCode }),
}
