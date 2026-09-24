package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.DropStrategy
import com.artifactsmmo.core.task.TaskType

/**
 * End-of-task cleanup (craft/cook leftovers, then bank everything), moved verbatim from the
 * legacy CharacterTaskRunner. One instance per call.
 */
internal class LegacyCleanup(private val helper: ActionHelper, private val characterName: String) {

    suspend fun run(task: TaskType, onStatus: (String) -> Unit) {
        val char = helper.refreshCharacter(characterName)
        if (char.inventory.sumOf { it.quantity } == 0) return
        when (task) {
            is TaskType.Gather -> cleanupGatherTask(task, onStatus)
            is TaskType.Fight -> cleanupFightTask(task, onStatus)
            is TaskType.Craft -> cleanupCraftTask(onStatus)
            is TaskType.TaskMaster -> cleanupTaskMasterTask(onStatus)
            else -> Unit // quick bank tasks, events, boss fights: no cleanup
        }
    }

    /**
     * Cleanup after a gather task: craft target item or cook fish if applicable, then bank all.
     */
    private suspend fun cleanupGatherTask(task: TaskType.Gather, onStatus: (String) -> Unit) {
        if (task.targetItemCode != null) {
            // Craft the specific target item from leftover inventory
            val char = helper.refreshCharacter(characterName)
            val targetItem = try { helper.getItem(task.targetItemCode) } catch (_: Exception) { null }
            val craft = targetItem?.craft
            val workshopSkill = craft?.skill

            if (craft != null && workshopSkill != null) {
                val maxCraftable = craft.items.minOfOrNull { ingredient ->
                    helper.getItemQuantity(char, ingredient.code) / ingredient.quantity
                } ?: 0

                if (maxCraftable > 0) {
                    val workshop = helper.findNearestWorkshop(char, workshopSkill)
                    if (workshop != null) {
                        onStatus("Crafting leftover materials into ${targetItem.name}...")
                        helper.moveTo(characterName, workshop.x, workshop.y)

                        val updatedChar = helper.refreshCharacter(characterName)
                        val actualCraftable = craft.items.minOfOrNull { ingredient ->
                            helper.getItemQuantity(updatedChar, ingredient.code) / ingredient.quantity
                        } ?: 0

                        if (actualCraftable > 0) {
                            onStatus("Crafting ${actualCraftable}x ${targetItem.name}...")
                            helper.craft(characterName, targetItem.code, actualCraftable)
                        }
                    }
                }
            }
        } else if (task.cookBeforeDeposit) {
            // Cook simple fish recipes from leftover inventory
            val char = helper.refreshCharacter(characterName)
            val cookable = helper.findCraftableRefinements(char, "fishing")
                .filter { (item, _) -> item.craft?.items?.size == 1 }

            if (cookable.isNotEmpty()) {
                val workshop = helper.findNearestWorkshop(char, "cooking")
                if (workshop != null) {
                    onStatus("Cooking leftover raw fish...")
                    helper.moveTo(characterName, workshop.x, workshop.y)

                    val updatedChar = helper.refreshCharacter(characterName)
                    val updatedCookable = helper.findCraftableRefinements(updatedChar, "fishing")
                        .filter { (item, _) -> item.craft?.items?.size == 1 }

                    for ((item, maxQty) in updatedCookable) {
                        onStatus("Cooking ${maxQty}x ${item.name}...")
                        helper.craft(characterName, item.code, maxQty)
                    }
                }
            }
        }

        // Bank everything
        val char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems items...")
            helper.bankDepositAll(characterName)
        }
    }

    /**
     * Cleanup after a fight task: cook drops per strategy, then bank everything.
     * COOK_AND_USE and COOK_AND_BANK drops get cooked; BANK_RAW drops are deposited raw.
     * On cleanup we bank everything (including food) since we're transitioning away.
     */
    private suspend fun cleanupFightTask(task: TaskType.Fight, onStatus: (String) -> Unit) {
        var char = helper.refreshCharacter(characterName)

        // Discover cookable drops for this monster
        val cookableDrops = helper.findCookableDrops(task.monsterCode)
        val cookingLevel = CharacterUtils.getSkillLevel(char, "cooking") ?: 0
        val allCookable = cookableDrops.filter {
            it.cookingLevelRequired <= cookingLevel && it.useLevelRequired <= char.level
        }

        // Only cook drops that are COOK_AND_USE or COOK_AND_BANK (not BANK_RAW)
        val dropsToCook = allCookable.filter {
            val strategy = task.dropStrategies[it.rawCode] ?: task.defaultDropStrategy
            strategy != DropStrategy.BANK_RAW
        }

        if (dropsToCook.isNotEmpty()) {
            var needsWorkshop = false
            for (info in dropsToCook) {
                if (helper.getItemQuantity(char, info.rawCode) >= info.rawPerCraft) {
                    needsWorkshop = true
                    break
                }
            }

            if (needsWorkshop) {
                val workshop = helper.findNearestWorkshop(char, "cooking")
                if (workshop != null) {
                    onStatus("Cooking leftover raw food...")
                    helper.moveTo(characterName, workshop.x, workshop.y)
                    char = helper.refreshCharacter(characterName)

                    for (info in dropsToCook) {
                        val rawQty = helper.getItemQuantity(char, info.rawCode)
                        val craftQty = rawQty / info.rawPerCraft
                        if (craftQty > 0) {
                            onStatus("Cooking ${craftQty}x ${info.cookedCode} (from ${craftQty * info.rawPerCraft}x ${info.rawCode})...")
                            helper.craft(characterName, info.cookedCode, craftQty)
                        }
                    }
                }
            }
        }

        // Bank everything (don't keep food — transitioning away from fighting)
        char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems items...")
            helper.bankDepositAll(characterName)
        }
    }

    /**
     * Cleanup after a craft task: deposit any remaining items to bank.
     */
    private suspend fun cleanupCraftTask(onStatus: (String) -> Unit) {
        val char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems leftover items...")
            helper.bankDepositAll(characterName)
        }
    }

    /**
     * Cleanup after a task master task: deposit any remaining items to bank.
     */
    private suspend fun cleanupTaskMasterTask(onStatus: (String) -> Unit) {
        val char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems leftover items...")
            helper.bankDepositAll(characterName)
        }
    }
}
