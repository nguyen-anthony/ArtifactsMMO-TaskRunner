/** Small display helpers. */
import type { QueuedTask, TaskSpec } from './types'

export function describeSpec(s: TaskSpec): string {
  switch (s.kind) {
    case 'gather': return `Gather ${s.resourceCode}${s.targetItemCode ? ` → ${s.targetItemCode}` : ''}`
    case 'fight': return `Fight ${s.monsterCode}`
    case 'craft': return `Craft ${s.itemCode} (${s.mode.toLowerCase()}${s.targetQuantity ? ` ×${s.targetQuantity}` : ''})`
    case 'task_master': return `Task master (${s.taskType})`
    case 'bank_withdraw': return `Withdraw ${s.quantity}× ${s.itemCode}`
    case 'bank_recycle': return `Recycle ${s.quantity}× ${s.itemCode} from bank`
    case 'bank_delete': return `Delete ${s.quantity}× ${s.itemCode} from bank`
    case 'inventory_deposit': return `Deposit ${s.quantity}× ${s.itemCode}`
    case 'inventory_recycle': return `Recycle ${s.quantity}× ${s.itemCode}`
    case 'bulk_bank_withdraw': return `Withdraw ${s.items.length} items`
    case 'bulk_inventory_deposit': return `Deposit ${s.items.length} items`
    case 'gold_deposit': return `Deposit ${s.quantity > 0 ? s.quantity : 'all'} gold`
    case 'gold_withdraw': return `Withdraw ${s.quantity > 0 ? s.quantity : 'all'} gold`
    case 'npc_buy': return `Buy ${s.items.map((i) => `${i.quantity}× ${i.code}`).join(', ')} from ${s.npcName ?? s.npcCode}`
    case 'boss_fight': return `${s.raidCode ? 'Raid' : s.eventCode ? 'Event boss' : 'Boss'} ${s.monsterCode}`
    case 'event_gather': return `Event gather ${s.resourceCode}`
    case 'event_npc': return `Event NPC ${s.npcName ?? s.npcCode}${s.buy?.length ? `: buy ${s.buy.map((b) => `${b.quantity}× ${b.code}`).join(', ')}` : ''}`
    case 'event_fight': return `Event fight ${s.monsterName ?? s.monsterCode}`
    case 'daily_offering': return s.offeringId
      ? `Offering ${s.offeringId}`
      : `Daily offerings${s.categories?.length ? ` (${s.categories.join(', ')})` : ''}`
    case 'toolkit_sync': return 'Sync tool kit'
  }
}

export function describeTask(t: QueuedTask): string {
  const role = t.groupRole === 'initiator' ? ' [initiator]' : t.groupRole === 'participant' ? ' [participant]' : ''
  return describeSpec(t.spec) + role
}

/** Pass `now` from a reactive clock so the label re-renders as time passes. */
export function ago(ms: number, now: number = Date.now()): string {
  const s = Math.max(0, Math.round((now - ms) / 1000))
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.round(s / 60)}m ago`
  return `${Math.round(s / 3600)}h ago`
}

export const time = (ms: number) => new Date(ms).toLocaleTimeString()
