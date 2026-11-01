package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CharacterSeasonalOfferings
import com.artifactsmmo.client.models.MapInfo
import com.artifactsmmo.client.models.OfferingFavor
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.services.OfferingService
import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.core.offering.AcquirePlan
import com.artifactsmmo.core.offering.AcquireStep
import com.artifactsmmo.core.offering.AcquisitionPlanner
import com.artifactsmmo.core.offering.AcquisitionWorld
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.CraftMode
import com.artifactsmmo.core.task.CraftingExecutor
import com.artifactsmmo.core.task.FightingExecutor
import com.artifactsmmo.core.task.GatheringExecutor
import com.artifactsmmo.core.task.GearOptimizer
import com.artifactsmmo.core.task.StepResult
import com.artifactsmmo.core.task.TaskType
import java.util.concurrent.ConcurrentHashMap

/** [OfferingOps] on top of the existing helper and gather/fight/craft executors. */
@OptIn(kotlin.time.ExperimentalTime::class)
class LegacyOfferingOps(
    private val helper: ActionHelper,
    private val offerings: OfferingService,
    private val gathering: GatheringExecutor,
    private val fighting: FightingExecutor,
    private val crafting: CraftingExecutor,
    /** Fallback altar lookup (the static map snapshot may predate the altar). */
    private val findAltars: suspend () -> List<MapInfo>,
) : OfferingOps {

    /** "name:monster:level" -> seconds per kill, or [UNBEATABLE]. */
    private val killSeconds = ConcurrentHashMap<String, Double>()
    /** name -> (monster, utilities planned with its loadout), for mid-fight refills. */
    private val fightUtilities = ConcurrentHashMap<String, Pair<String, List<GearOptimizer.UtilityEquipAction>>>()
    @Volatile private var altar: MapInfo? = null

    override suspend fun status(character: String): CharacterSeasonalOfferings = offerings.getForCharacter(character)

    override suspend fun accept(character: String, offeringId: String) {
        helper.waitForCooldown(offerings.accept(character, offeringId).cooldown.expiration)
    }

    override suspend fun complete(character: String, offeringId: String): Int {
        val result = offerings.complete(character, offeringId)
        helper.waitForCooldown(result.cooldown.expiration)
        return result.reward.xp
    }

    override suspend fun level(character: String, category: String): Int = levels(character, listOf(category)).getValue(category)

    override suspend fun levels(character: String, categories: List<String>): Map<String, Int> {
        val char = helper.refreshCharacter(character)
        return categories.associateWith { if (it == "combat") char.level else CharacterUtils.getSkillLevel(char, it) ?: 0 }
    }

    override suspend fun plan(character: String, code: String, quantity: Int, favor: OfferingFavor): AcquirePlan {
        val char = helper.refreshCharacter(character)
        return AcquisitionPlanner(
            world(character, char),
            preferResource = favor.targetCode.takeIf { favor.targetType == "resource" },
            preferMonster = favor.targetCode.takeIf { favor.targetType == "monster" },
        ).plan(code, quantity)
    }

    private fun world(name: String, char: Character) = object : AcquisitionWorld {
        override suspend fun item(code: String) = helper.contentCache.getItemOrNull(code)
        override suspend fun resourcesDropping(code: String) = helper.contentCache.getResourcesByDrop(code)
        override suspend fun monstersDropping(code: String) = helper.contentCache.getMonstersByDrop(code)
        override fun skillLevel(skill: String) =
            if (skill == "combat") char.level else CharacterUtils.getSkillLevel(char, skill) ?: 0
        override fun canReach(contentType: String, code: String) = helper.canReach(char, contentType, code)
        override suspend fun owned(code: String) = helper.getItemQuantity(char, code) + helper.getBankItemQuantity(code)

        override suspend fun secondsPerKill(monsterCode: String): Double? {
            val key = "$name:$monsterCode:${char.level}"
            val seconds = killSeconds[key] ?: run {
                val score = runCatching { fighting.optimizeGearForMonster(name, char, monsterCode).optimizedScore }
                    .onFailure { rethrowCancellation(it) }.getOrNull()
                val value = when {
                    score == null || score.winRate < MIN_WIN_RATE -> UNBEATABLE
                    score.cycleSeconds.isFinite() && score.cycleSeconds < Double.MAX_VALUE -> score.cycleSeconds
                    else -> FALLBACK_KILL_SECONDS
                }
                killSeconds[key] = value
                value
            }
            return seconds.takeIf { it != UNBEATABLE }
        }
    }

    override suspend fun goToAltar(character: String, carryCode: String?, carryQuantity: Int, status: (String) -> Unit) {
        var char = helper.refreshCharacter(character)
        if (carryCode != null) {
            val shortfall = carryQuantity - helper.getItemQuantity(char, carryCode)
            if (shortfall > 0) {
                status("Withdrawing ${shortfall}x $carryCode...")
                char = helper.depositInventoryExcept(character, setOf(carryCode), char)
                char = helper.bankWithdrawItems(character, listOf(SimpleItem(carryCode, shortfall)))
            }
        }
        val target = helper.findNearest(char, ALTAR) ?: altar ?: findAltars().firstOrNull()?.also { altar = it }
            ?: throw IllegalStateException("Seasonal altar not found on the map")
        status("Walking to the seasonal altar...")
        helper.navigateToTile(character, target)
    }

    override suspend fun work(character: String, step: AcquireStep, prepare: Boolean, status: (String) -> Unit): StepResult =
        when (step) {
            is AcquireStep.Gather -> {
                if (prepare) gathering.ensureBestTool(character, step.skill, status)
                gathering.executeStep(character, TaskType.Gather(step.skill, step.resourceCode, step.resourceCode), status)
            }
            is AcquireStep.Fight -> {
                val planned = fightUtilities[character]?.takeIf { it.first == step.monsterCode }
                val utilities = if (prepare || planned == null) {
                    val result = fighting.prepareLoadoutForMonster(character, step.monsterCode, status)
                    result?.utilityActions.orEmpty().also { fightUtilities[character] = step.monsterCode to it }
                } else planned.second
                val monsterName = runCatching { helper.contentCache.getMonster(step.monsterCode).name }
                    .getOrDefault(step.monsterCode)
                fighting.executeStep(
                    character,
                    TaskType.Fight(step.monsterCode, monsterName, utilityActions = utilities, loadoutOptimized = true),
                    status,
                )
            }
            is AcquireStep.Craft -> crafting.executeStep(
                character,
                TaskType.Craft(step.skill, step.itemCode, step.itemCode, CraftMode.BANK, targetQuantity = step.crafts),
                status,
            )
        }

    private companion object {
        const val ALTAR = "seasonal_altar"
        /** Same bar TaskMaster uses with the API simulator. */
        const val MIN_WIN_RATE = 0.9
        const val FALLBACK_KILL_SECONDS = 60.0
        const val UNBEATABLE = -1.0
    }
}
