package com.artifactsmmo.engine.realtime

import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.CoopOptimizer
import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.core.task.GearOptimizer
import com.artifactsmmo.domain.task.EquipPlan
import com.artifactsmmo.domain.task.EventMap
import com.artifactsmmo.domain.task.ItemQty
import com.artifactsmmo.domain.task.MemberPlan
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.domain.task.UtilityPlan
import com.artifactsmmo.engine.scheduler.RaidPlanner

/**
 * The event-dispatch decisions from the old EventDispatcher, unchanged, but returning
 * specs instead of assigning tasks directly:
 *  - resource: every candidate with the required skill level gathers,
 *  - npc: only the designated trader, if items are configured,
 *  - monster: each candidate is gear-optimized; only those above minWinRate fight.
 */
class LegacyEventPlanner(
    private val helper: ActionHelper,
    private val gear: GearOptimizer,
    private val allCharacters: () -> List<String>,
    private val log: (String) -> Unit = {},
) : EventPlanner {

    override suspend fun plan(event: ActiveEvent, config: EventConfig): List<Pair<String, TaskSpec>> {
        val map = EventMap(event.map.x, event.map.y, event.map.layer)
        val candidates = config.eligibleCharacters.ifEmpty { allCharacters() }
        return when (event.content.type) {
            "resource" -> {
                val resource = helper.contentCache.getResource(event.content.code)
                candidates.mapNotNull { name ->
                    val char = runCatching { helper.refreshCharacter(name) }.getOrNull() ?: return@mapNotNull null
                    val level = CharacterUtils.getSkillLevel(char, resource.skill) ?: 0
                    if (level < resource.level) { log("Event ${event.code}: $name ${resource.skill} $level < ${resource.level}"); null }
                    else name to TaskSpec.EventGather(event.code, resource.code, resource.skill, map, resource.name)
                }
            }
            "npc" -> {
                val trader = config.designatedTrader
                if (trader == null || (config.itemsToSell.isEmpty() && config.itemsToBuy.isEmpty())) emptyList()
                else listOf(trader to TaskSpec.EventNpc(
                    event.code, event.content.code, map,
                    sell = config.itemsToSell.map { ItemQty(it.code, it.quantity) },
                    buy = config.itemsToBuy.map { ItemQty(it.code, it.quantity) },
                    npcName = event.name,
                ))
            }
            "monster" -> candidates.mapNotNull { name ->
                // Sequential on purpose: later characters reuse the optimizer's cache.
                val char = runCatching { helper.refreshCharacter(name) }.getOrNull() ?: return@mapNotNull null
                val opt = runCatching { gear.optimizeWithCacheHint(char, event.content.code) }.getOrNull() ?: return@mapNotNull null
                val win = opt.optimizedScore.winRate
                if (win < config.minWinRate) { log("Event ${event.code}: $name win rate ${(win * 100).toInt()}% too low"); return@mapNotNull null }
                if (opt.equipActions.isNotEmpty() || opt.utilityActions.isNotEmpty()) gear.markOptimized(name, event.content.code)
                name to TaskSpec.EventFight(
                    event.code, event.content.code, map, monsterName = event.name,
                    equip = opt.equipActions.map { EquipPlan(it.slot, it.itemCode, it.source) },
                    utilities = opt.utilityActions.map { UtilityPlan(it.slot, it.itemCode, it.quantity, it.source) },
                )
            }
            else -> emptyList()
        }
    }
}

/** Raid team provisioning via the existing CoopOptimizer. */
class LegacyRaidPlanner(private val coop: CoopOptimizer) : RaidPlanner {
    override suspend fun plan(members: List<String>, monsterCode: String, tankOverride: String?): Map<String, MemberPlan> =
        coop.optimizeForBossFight(members, monsterCode, tankOverride).participants.associate { p ->
            p.characterName to MemberPlan(
                equip = p.equipActions.map { EquipPlan(it.slot, it.itemCode, it.source) },
                utilities = p.utilityActions.map { UtilityPlan(it.slot, it.itemCode, it.quantity, it.source) },
                reservePotions = p.reservePotions, foodCode = p.foodCode, foodQuantity = p.foodQuantity,
                transitionCosts = p.transitionCosts, spareKeys = p.spareKeys,
            )
        }
}
