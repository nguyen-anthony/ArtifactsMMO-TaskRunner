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
 *  - boss monster: the configured `group` is co-op optimized and fights together as one
 *    BossFight group at the event tile; skipped when no group is configured.
 */
class LegacyEventPlanner(
    private val helper: ActionHelper,
    private val gear: GearOptimizer,
    private val coop: CoopOptimizer,
    private val allCharacters: () -> List<String>,
    private val log: (String) -> Unit = {},
) : EventPlanner {

    override suspend fun plan(event: ActiveEvent, config: EventConfig): List<EventPlan> {
        val map = EventMap(event.map.x, event.map.y, event.map.layer)
        val candidates = config.eligibleCharacters.ifEmpty { allCharacters() }
        return when (event.content.type) {
            "resource" -> {
                val resource = helper.contentCache.getResource(event.content.code)
                candidates.mapNotNull { name ->
                    val char = runCatching { helper.refreshCharacter(name) }.getOrNull() ?: return@mapNotNull null
                    val level = CharacterUtils.getSkillLevel(char, resource.skill) ?: 0
                    if (level < resource.level) { log("Event ${event.code}: $name ${resource.skill} $level < ${resource.level}"); null }
                    else EventPlan.Solo(name, TaskSpec.EventGather(event.code, resource.code, resource.skill, map, resource.name))
                }
            }
            "npc" -> {
                val trader = config.designatedTrader
                if (trader == null || (config.itemsToSell.isEmpty() && config.itemsToBuy.isEmpty())) emptyList()
                else listOf(EventPlan.Solo(trader, TaskSpec.EventNpc(
                    event.code, event.content.code, map,
                    sell = config.itemsToSell.map { ItemQty(it.code, it.quantity) },
                    buy = config.itemsToBuy.map { ItemQty(it.code, it.quantity) },
                    npcName = event.name,
                )))
            }
            "monster" -> {
                val monster = runCatching { helper.contentCache.getMonsterOrNull(event.content.code) }.getOrNull()
                if (monster?.type == "boss") planBossGroup(event, config, map, monster.name)
                else planSoloFights(event, config, map, candidates)
            }
            else -> emptyList()
        }
    }

    private suspend fun planSoloFights(event: ActiveEvent, config: EventConfig, map: EventMap, candidates: List<String>): List<EventPlan> =
        candidates.mapNotNull { name ->
            // Sequential on purpose: later characters reuse the optimizer's cache.
            val char = runCatching { helper.refreshCharacter(name) }.getOrNull() ?: return@mapNotNull null
            val opt = runCatching { gear.optimizeWithCacheHint(char, event.content.code) }.getOrNull() ?: return@mapNotNull null
            val win = opt.optimizedScore.winRate
            if (win < config.minWinRate) { log("Event ${event.code}: $name win rate ${(win * 100).toInt()}% too low"); return@mapNotNull null }
            if (opt.equipActions.isNotEmpty() || opt.utilityActions.isNotEmpty()) gear.markOptimized(name, event.content.code)
            EventPlan.Solo(name, TaskSpec.EventFight(
                event.code, event.content.code, map, monsterName = event.name,
                equip = opt.equipActions.map { EquipPlan(it.slot, it.itemCode, it.source) },
                utilities = opt.utilityActions.map { UtilityPlan(it.slot, it.itemCode, it.quantity, it.source) },
            ))
        }

    /** Boss monsters can't be soloed: fight them as the configured co-op group, or not at all. */
    private suspend fun planBossGroup(event: ActiveEvent, config: EventConfig, map: EventMap, monsterName: String): List<EventPlan> {
        val group = config.group
        if (group == null) {
            log("Event ${event.code}: ${event.content.code} is a boss — skipped (configure a group for this event)")
            return emptyList()
        }
        val members = group.members
        val result = runCatching { coop.optimizeForBossFight(members, event.content.code, group.tankOverride) }
            .onFailure { log("Event ${event.code}: co-op planning failed: ${it.message}") }
            .getOrNull() ?: return emptyList()
        val win = result.optimizedWinRate
        if (win < config.minWinRate) {
            log("Event ${event.code}: group ${members.joinToString()} win rate ${(win * 100).toInt()}% < ${(config.minWinRate * 100).toInt()}% — skipped")
            return emptyList()
        }
        log("Event ${event.code}: group ${members.joinToString()} win rate ${(win * 100).toInt()}% (tank ${result.tankName})")
        return listOf(EventPlan.Group(members, TaskSpec.BossFight(
            monsterCode = event.content.code, monsterName = monsterName,
            plans = result.toMemberPlans(), eventCode = event.code, eventMap = map,
        )))
    }
}

/** Raid team provisioning via the existing CoopOptimizer. */
class LegacyRaidPlanner(private val coop: CoopOptimizer) : RaidPlanner {
    override suspend fun plan(members: List<String>, monsterCode: String, tankOverride: String?): Map<String, MemberPlan> =
        coop.optimizeForBossFight(members, monsterCode, tankOverride).toMemberPlans()
}

private fun CoopOptimizer.CoopOptimizationResult.toMemberPlans(): Map<String, MemberPlan> =
    participants.associate { p ->
        p.characterName to MemberPlan(
            equip = p.equipActions.map { EquipPlan(it.slot, it.itemCode, it.source) },
            utilities = p.utilityActions.map { UtilityPlan(it.slot, it.itemCode, it.quantity, it.source) },
            reservePotions = p.reservePotions, foodCode = p.foodCode, foodQuantity = p.foodQuantity,
            transitionCosts = p.transitionCosts, spareKeys = p.spareKeys,
        )
    }
