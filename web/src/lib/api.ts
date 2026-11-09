/**
 * Tiny fetch wrapper for the backend. Every call is same-origin, so the browser sends the
 * HttpOnly session cookie automatically; we never see or store the key/token in JS.
 */
import type {
  Catalog, CharacterDto, CharacterProfile, CharacterSettings, LogLine, Me, MemberPlan, QueuedTask, RateWindow,
  StopCondition, TaskEvent, TaskRequirements, TaskSpec, EquipPlan, UtilityPlan, Craftable, CoopResult, NpcItem,
  OfferingOverview, OfferingPreview, KnownLoadout, ToolKitConfig, ToolKitState,
  BankThreshold, ThresholdSettings, ThresholdsState,
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
  dedupeKey?: string; expiresAtMillis?: number
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
  characterProfiles: () => request<CharacterProfile[]>('GET', '/characters/profiles'),
  characterDetails: (name: string) => request<CharacterProfile>('GET', `/characters/${name}/details`),
  saveSettings: (name: string, s: CharacterSettings) => request<CharacterSettings>('PUT', `/characters/${name}/settings`, s),

  configs: (kind: 'event' | 'raid' | 'offering') => request<Record<string, any>>('GET', `/configs/${kind}`),
  saveConfig: (kind: string, key: string, value: unknown) => request<void>('PUT', `/configs/${kind}/${key}`, value),
  deleteConfig: (kind: string, key: string) => request<void>('DELETE', `/configs/${kind}/${key}`),

  offerings: () => request<OfferingOverview>('GET', '/offerings'),
  offeringPreview: (character: string, offeringId: string) =>
    request<OfferingPreview>('GET', `/offerings/${encodeURIComponent(character)}/plan/${encodeURIComponent(offeringId)}`),

  bank: () => request<{ code: string; quantity: number }[]>('GET', '/bank'),

  loadouts: () => request<KnownLoadout[]>('GET', '/loadouts'),
  saveLoadout: (l: KnownLoadout) => request<KnownLoadout>('PUT', '/loadouts', l),
  /** Filters: character, monster, level, version (at least one), or { all: 'true' }. */
  deleteLoadouts: (filter: Record<string, string | number>) =>
    request<{ removed: number }>('DELETE', `/loadouts?${new URLSearchParams(
      Object.entries(filter).map(([k, v]) => [k, String(v)]),
    )}`),
  toolKit: () => request<ToolKitState>('GET', '/toolkit'),
  saveToolKit: (c: ToolKitConfig) => request<ToolKitState>('PUT', '/toolkit', c),
  refreshToolKit: () => request<ToolKitState>('POST', '/toolkit/refresh'),
  thresholds: () => request<ThresholdsState>('GET', '/thresholds'),
  refreshThresholds: () => request<ThresholdsState>('POST', '/thresholds/refresh'),
  saveThresholdSettings: (s: ThresholdSettings) => request<ThresholdsState>('PUT', '/thresholds/settings', s),
  saveThreshold: (t: BankThreshold) =>
    request<ThresholdsState>('PUT', `/thresholds/${encodeURIComponent(t.itemCode)}`, t),
  deleteThreshold: (itemCode: string) =>
    request<ThresholdsState>('DELETE', `/thresholds/${encodeURIComponent(itemCode)}`),
  rates: () => request<{ buckets: Record<string, RateWindow[]> }>('GET', '/rates'),
  logs: (character?: string) => request<LogLine[]>('GET', `/logs?limit=300${character ? `&character=${character}` : ''}`),
  resume: () => request<void>('POST', '/control/resume'),

  content: () => request<Catalog>('GET', '/content'),
  npcItems: (npcCode: string) => request<NpcItem[]>('GET', `/content/npcs/${encodeURIComponent(npcCode)}/items`),
  /** Recipes + max craftable. No character = bank only. `all` = include above-level recipes. */
  craftable: (character: string, all = false) =>
    request<Craftable[]>('GET', `/craftable?skill=all&all=${all}${character ? `&character=${encodeURIComponent(character)}` : ''}`),
  simLoadout: (character: string, monsterCode: string) =>
    request<{ baselineWinRate: number; winRate: number; equip: EquipPlan[]; utilities: UtilityPlan[] }>(
      'POST', '/sim/loadout', { character, monsterCode }),
  simCoop: (members: string[], monsterCode: string, tankOverride?: string) =>
    request<CoopResult>('POST', '/sim/coop', { members, monsterCode, tankOverride: tankOverride || undefined }),
}
