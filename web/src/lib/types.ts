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
  | { kind: 'bank_recycle'; itemCode: string; quantity: number; craftSkill: string }
  | { kind: 'inventory_deposit'; itemCode: string; quantity: number }
  | { kind: 'inventory_recycle'; itemCode: string; quantity: number; craftSkill: string }
  | { kind: 'bulk_bank_withdraw'; items: ItemQty[] }
  | { kind: 'bulk_inventory_deposit'; items: ItemQty[] }
  | { kind: 'boss_fight'; monsterCode: string; monsterName?: string; plans?: Record<string, MemberPlan>; raidCode?: string }
  | { kind: 'event_gather'; eventCode: string; resourceCode: string; skill: string; map: EventMap }
  | { kind: 'event_npc'; eventCode: string; npcCode: string; map: EventMap; sell?: ItemQty[]; buy?: ItemQty[] }
  | { kind: 'event_fight'; eventCode: string; monsterCode: string; map: EventMap }

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
export interface CharacterDto { name: string; status?: WorkerStatus; settings: CharacterSettings; level?: number; skills?: Record<string, number> }

export interface LogLine { timestampMillis: number; character?: string; message: string }
export interface Me { loggedIn: boolean; engineRunning: boolean; paused?: string }
export interface RateWindow { limit: number; windowMillis: number; used: number }

export interface Catalog {
  // `reachable`: at least one tile the account can get to (achievement-locked areas and
  // event-only spawns are false).
  monsters: { code: string; name: string; level: number; type: string; reachable: boolean }[]
  resources: { code: string; name: string; skill: string; level: number; reachable: boolean }[]
  items: { code: string; name: string; level: number; type: string; craftSkill?: string }[]
}

export interface CraftableIngredient { code: string; perCraft: number; have: number; missing: number }
export interface NpcBuy { npc: string; item: string; currency: string; priceEach: number; quantity: number }
/** A recipe with how many can be crafted now (inventory + bank, or bank only). */
export interface Craftable {
  code: string; name: string; skill: string; level: number; maxCraftable: number
  ingredients: CraftableIngredient[]; npcBuy: NpcBuy[]
}

export const SKILLS = ['mining', 'woodcutting', 'fishing', 'alchemy', 'weaponcrafting', 'gearcrafting', 'jewelrycrafting', 'cooking'] as const
