/**
 * TypeScript mirrors of the backend's JSON (server/.../api/Dto.kt, domain QueueModels/TaskSpec).
 * Hand-written for now; keep in sync when the Kotlin models change.
 *
 * Sealed Kotlin types (TaskSpec, StopCondition) arrive with a `kind` field naming the subtype,
 * which TypeScript models as a "discriminated union": checking `spec.kind === 'gather'`
 * narrows `spec` to the Gather shape.
 */

export type TaskStatus = 'pending' | 'claimed' | 'running' | 'suspended' | 'completed' | 'failed' | 'cancelled'
export type TaskSource = 'raid' | 'event' | 'manual' | 'schedule' | 'filler' | 'system'
export type GroupRole = 'solo' | 'group' | 'initiator' | 'participant'

export interface ItemQty { code: string; quantity: number }
export interface EquipPlan { slot: string; itemCode: string; source: string }
export interface UtilityPlan { slot: string; itemCode: string; quantity: number; source: string }
export interface CoopResult {
  tank: string; baselineWinRate: number; winRate: number; avgWinTurns: number
  winnable: boolean; plans: Record<string, MemberPlan>
}

export interface MemberPlan {
  equip?: EquipPlan[]; utilities?: UtilityPlan[]; reservePotions?: Record<string, number>
  foodCode?: string; foodQuantity?: number; transitionCosts?: Record<string, number>; spareKeys?: Record<string, number>
}
export interface EventMap { x: number; y: number; layer: string }
export type DropStrategy = 'COOK_AND_USE' | 'COOK_AND_BANK' | 'BANK_RAW'

export type TaskSpec =
  | { kind: 'gather'; skill: string; resourceCode: string; resourceName?: string; targetItemCode?: string; cookBeforeDeposit?: boolean }
  | { kind: 'fight'; monsterCode: string; monsterName?: string; equip?: EquipPlan[]; utilities?: UtilityPlan[]; loadoutOptimized?: boolean; defaultDropStrategy?: DropStrategy }
  | { kind: 'craft'; skill: string; itemCode: string; itemName?: string; mode: 'RECYCLE' | 'BANK'; targetQuantity?: number }
  | { kind: 'task_master'; taskType: 'items' | 'monsters' }
  | { kind: 'bank_withdraw'; itemCode: string; quantity: number }
  | { kind: 'bank_recycle'; itemCode: string; quantity: number; craftSkill: string; itemName?: string }
  | { kind: 'inventory_deposit'; itemCode: string; quantity: number }
  | { kind: 'inventory_recycle'; itemCode: string; quantity: number; craftSkill: string }
  | { kind: 'bulk_bank_withdraw'; items: ItemQty[] }
  | { kind: 'bulk_inventory_deposit'; items: ItemQty[] }
  | { kind: 'boss_fight'; monsterCode: string; monsterName?: string; plans?: Record<string, MemberPlan>; raidCode?: string; eventCode?: string; eventMap?: EventMap }
  | { kind: 'event_gather'; eventCode: string; resourceCode: string; skill: string; map: EventMap }
  | { kind: 'event_npc'; eventCode: string; npcCode: string; map: EventMap; sell?: ItemQty[]; buy?: ItemQty[] }
  | { kind: 'event_fight'; eventCode: string; monsterCode: string; map: EventMap }
  | { kind: 'daily_offering'; categories?: string[]; offeringId?: string | null }

export type StopCondition =
  | { kind: 'count'; target: number }
  | { kind: 'skill_level'; skill: string; level: number }
  | { kind: 'until'; epochMillis: number }

export interface TaskRequirements { minSkillLevels?: Record<string, number>; minFreeInventorySlots?: number; allowedCharacters?: string[] }

export interface QueuedTask {
  id: number; type: string; spec: TaskSpec; priority: number; source: TaskSource; status: TaskStatus
  assignedCharacter?: string; claimedBy?: string; requirements: TaskRequirements; stopCondition?: StopCondition
  groupId?: number; groupRole: GroupRole; checkpoint?: Record<string, unknown>; lastError?: string
  attempts: number; notBeforeMillis?: number; expiresAtMillis?: number; createdAtMillis: number; updatedAtMillis: number
}

export interface TaskEvent { id: number; taskId: number; character?: string; kind: string; message?: string; createdAtMillis: number }

export interface RunState {
  prepared: boolean; gathers: number; fightsWon: number; fightsLost: number; crafted: number
  recycled: number; bankTrips: number; tasksCompleted: number; consecutiveDeaths: number
}

export interface WorkerStatus {
  character: string; state: 'IDLE' | 'RUNNING' | 'DISABLED' | 'PAUSED'; taskId?: number; taskType?: string
  message: string; progress: RunState; lastError?: string; updatedAtMillis: number
}

export interface CharacterSettings { enabled: boolean; allowedTypes?: string[] | null; filler?: TaskSpec | null }
/** Mirrors engine OfferingConfig (config kind `offering`, keyed by character). Missing = enabled, all categories. */
export interface OfferingConfig { character: string; enabled?: boolean; categories?: string[]; priority?: number }
export const OFFERING_CATEGORIES = ['combat', 'mining', 'woodcutting', 'fishing', 'alchemy',
  'weaponcrafting', 'gearcrafting', 'jewelrycrafting', 'cooking'] as const

/** Mirrors client SeasonalOffering (snake_case from the game API). */
export interface SeasonalOffering {
  id: string; category: string; type: string; level_min: number; level_max: number
  requirement: { code: string; quantity: number }
  favor: { target_type: string; target_code: string; xp_multiplier: number; drop_multiplier: number }
}
/** Mirrors engine OfferingOverview / CharacterOfferingRow / SelectedOfferingRow. */
export interface OfferingOverview {
  date: string; expiresAt: string; xpRewardPercent: number
  offerings: SeasonalOffering[]
  characters: {
    name: string; levels: Record<string, number>; completedCategories: string[]; error?: string | null
    selected: { offeringId: string; category: string; status: 'accepted' | 'completed' | string; rewardXp: number }[]
  }[]
}
/** Mirrors engine OfferingPreview. */
export interface OfferingPreview {
  offeringId: string; character: string; kind: 'ready' | 'next' | 'infeasible'
  nextStep?: string | null; estimatedSeconds?: number | null; reason?: string | null
}
export interface CharacterDto { name: string; status?: WorkerStatus; settings: CharacterSettings; level?: number; skills?: Record<string, number> }

/**
 * Raw game character (client models/Character.kt, snake_case like the game API). Per-skill
 * fields (`<skill>_level`, `_xp`, `_max_xp`), element fields (`attack_<el>`, `dmg_<el>`,
 * `res_<el>`) and slots (`<slot>_slot`) are read dynamically via the index signature.
 */
export interface CharacterProfile {
  name: string; skin: string; level: number; xp: number; max_xp: number; gold: number
  hp: number; max_hp: number; haste: number; critical_strike: number; wisdom: number
  prospecting: number; initiative: number; threat: number; speed: number; dmg: number
  effects: { code: string; value: number }[]
  task: string; task_type: string; task_progress: number; task_total: number
  inventory_max_items: number; inventory: { slot: number; code: string; quantity: number }[]
  [key: string]: any
}

export interface LogLine { timestampMillis: number; character?: string; message: string }
export interface Me { loggedIn: boolean; engineRunning: boolean; paused?: string }
export interface RateWindow { limit: number; windowMillis: number; used: number }

export interface Catalog {
  // `reachable`: at least one tile the account can get to (achievement-locked areas and
  // event-only spawns are false).
  monsters: { code: string; name: string; level: number; type: string; reachable: boolean }[]
  resources: { code: string; name: string; skill: string; level: number; reachable: boolean }[]
  items: { code: string; name: string; level: number; type: string; craftSkill?: string }[]
  /** `contentType`: what the event spawns ('monster' | 'resource' | 'npc'). */
  events: { code: string; name: string; contentType: string; contentCode: string }[]
  raids: { code: string; name: string; monster: string }[]
}

/** What an NPC trades: `buyPrice` = we can buy it, `sellPrice` = we can sell it to them. */
export interface NpcItem { code: string; currency: string; buyPrice: number | null; sellPrice: number | null }

/** Mirrors engine `KnownLoadout` (known_loadouts.json, served by /api/loadouts). */
export interface KnownLoadout {
  characterName: string
  exactLevel: number
  monsterCode: string
  optimizerVersion: string
  /** slot ('weapon', 'ring1', ...) -> item code */
  gear: Record<string, string>
  /** slot ('utility1'/'utility2') -> potion and quantity */
  utilities: Record<string, { code: string; quantity: number }>
  requiredItems: Record<string, number>
  validatedWinRate: number
  averageWinTurns?: number | null
  validatedAtEpochMs: number
}

/** Mirrors engine `EventConfig` / `EventGroupConfig` (stored under /configs/event). */
export interface EventGroupConfig {
  initiatorName: string; participantNames: string[]; tankOverride: string | null
  /** Queue priority for every member; null = the event's priority, or the EVENT (80) / RAID (90) default. */
  priority?: number | null
}
export interface EventConfig {
  eventCode: string; enabled: boolean; eligibleCharacters: string[]
  itemsToSell: ItemQty[]; itemsToBuy: ItemQty[]; designatedTrader: string | null
  minWinRate: number
  /** Boss events: one co-op group per party (earlier parties get first pick of the bank). */
  groups: EventGroupConfig[]
  /** Legacy single party; superseded by `groups`. */
  group: EventGroupConfig | null
  /** Queue priority for this event's tasks; null = EVENT default (80). */
  priority: number | null
  /** Per-character overrides of `priority` for solo tasks. */
  characterPriorities: Record<string, number>
}
/** Mirrors engine `RaidConfig` (stored under /configs/raid). */
export interface RaidConfig {
  raidCode: string; enabled: boolean; leadTimeMinutes: number
  /** One co-op group per party (earlier parties get first pick of the bank). */
  parties: EventGroupConfig[]
  /** Parties simulating below this win rate are skipped; 0 = never skip. */
  minWinRate: number
  /** Legacy single-party fields; superseded by `parties`. */
  initiatorName: string | null; participantNames: string[]; tankOverride: string | null
}

export interface CraftableIngredient { code: string; perCraft: number; have: number; missing: number }
export interface NpcBuy { npc: string; item: string; currency: string; priceEach: number; quantity: number }
/** A recipe with how many can be crafted now (inventory + bank, or bank only). */
export interface Craftable {
  code: string; name: string; skill: string; level: number; maxCraftable: number
  ingredients: CraftableIngredient[]; npcBuy: NpcBuy[]
}

export const SKILLS = ['mining', 'woodcutting', 'fishing', 'alchemy', 'weaponcrafting', 'gearcrafting', 'jewelrycrafting', 'cooking'] as const
