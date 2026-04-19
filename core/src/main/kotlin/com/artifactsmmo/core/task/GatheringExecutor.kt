package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.SimpleItem

/**
 * Executes gathering task loops (mining, woodcutting, fishing).
 *
 * Loop: ensure tool equipped -> check for tool upgrade -> move to resource -> gather ->
 *       when inventory full -> handle (bank or craft-then-bank) -> return to resource
 */
class GatheringExecutor(private val helper: ActionHelper) {

    /** Track last upgrade check time per character (epoch millis). */
    private val lastUpgradeCheck = mutableMapOf<String, Long>()

    /** How often to check for tool upgrades (5 minutes). */
    private val upgradeCheckIntervalMs = 5 * 60 * 1000L

    /**
     * Execute a single iteration of the gathering loop.
     * Returns a result describing what happened.
     */
    suspend fun executeStep(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)

        // Check if inventory is full
        if (helper.isInventoryFull(char)) {
            onStatus("Inventory full, handling...")
            return handleFullInventory(characterName, task, onStatus)
        }

        // Ensure best tool is equipped
        onStatus("Checking tool...")
        char = helper.ensureToolEquipped(characterName, task.skill)

        // Check for tool upgrade from bank materials periodically
        val now = System.currentTimeMillis()
        val lastCheck = lastUpgradeCheck[characterName] ?: 0L
        if (now - lastCheck >= upgradeCheckIntervalMs) {
            lastUpgradeCheck[characterName] = now
            char = tryUpgradeTool(characterName, char, task.skill, onStatus)
        }

        // Find resource location and move there
        val resourceMap = helper.findNearest(char, "resource", task.resourceCode)
            ?: return StepResult.Error("No ${task.resourceCode} locations found on map")

        if (!helper.isAt(char, resourceMap.x, resourceMap.y)) {
            onStatus("Moving to ${task.resourceName}...")
            char = helper.moveTo(characterName, resourceMap.x, resourceMap.y)
        }

        // Gather
        val totalItems = char.inventory.sumOf { it.quantity }
        onStatus("Gathering ${task.resourceName}... (Inv: $totalItems/${char.inventoryMaxItems})")

        return try {
            val result = helper.gather(characterName)
            val drops = result.details.items.joinToString(", ") { "${it.quantity}x ${it.code}" }
            onStatus("Gathered: $drops (+${result.details.xp} XP)")
            StepResult.Gathered(result.details.xp, result.details.items.map { it.code to it.quantity })
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 486) {
                // Still in cooldown, just wait
                StepResult.Waiting
            } else throw e
        }
    }

    /**
     * Run once when a gather task starts (CRAFT_THEN_BANK only).
     * Checks the bank for leftover raw materials that can be crafted into
     * refined items, withdraws them, crafts, and deposits the results.
     */
    suspend fun prepareGatherTask(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ) {
        if (task.onFullInventory != FullInventoryStrategy.CRAFT_THEN_BANK) return

        val char = helper.refreshCharacter(characterName)
        val bankCraftable = try {
            helper.findCraftableRefinementsFromBank(char, task.skill)
        } catch (_: Exception) {
            return
        }

        if (bankCraftable.isEmpty()) return

        val workshopSkill = when (task.skill) {
            "mining" -> "mining"
            "woodcutting" -> "woodcutting"
            "fishing" -> "cooking"
            "alchemy" -> "alchemy"
            else -> return
        }

        for ((item, maxQty, withdrawList) in bankCraftable) {
            // Check how much inventory space we have
            val currentItems = helper.refreshCharacter(characterName).inventory.sumOf { it.quantity }
            val maxItems = helper.refreshCharacter(characterName).inventoryMaxItems
            val withdrawTotal = withdrawList.sumOf { it.quantity }

            if (currentItems + withdrawTotal > maxItems) {
                // Not enough space — craft in batches or skip
                onStatus("Not enough inventory space to process bank leftovers for ${item.name}, skipping")
                continue
            }

            onStatus("Withdrawing ${withdrawTotal} raw materials from bank for ${item.name}...")
            helper.bankWithdrawItems(characterName, withdrawList)

            val workshop = helper.findNearestWorkshop(helper.refreshCharacter(characterName), workshopSkill)
            if (workshop == null) {
                // Can't find workshop, deposit back
                helper.bankDepositItems(characterName, withdrawList)
                continue
            }

            onStatus("Crafting ${maxQty}x ${item.name} from bank leftovers...")
            helper.moveTo(characterName, workshop.x, workshop.y)
            helper.craft(characterName, item.code, maxQty)

            // Deposit the crafted items (resource type only)
            val updatedChar = helper.refreshCharacter(characterName)
            val toDeposit = updatedChar.inventory
                .filter { slot -> slot.quantity > 0 }
                .mapNotNull { slot ->
                    val type = helper.getItemType(slot.code)
                    if (type == "resource") SimpleItem(slot.code, slot.quantity) else null
                }

            if (toDeposit.isNotEmpty()) {
                onStatus("Depositing crafted ${item.name}...")
                helper.bankDepositItems(characterName, toDeposit)
            }
        }
    }

    /**
     * Check if a better tool is available — either already in the bank (ready-made)
     * or craftable from bank materials. Ready-made tools are checked first so that
     * tools crafted by other characters are picked up immediately.
     */
    private suspend fun tryUpgradeTool(
        characterName: String,
        currentChar: Character,
        skill: String,
        onStatus: (String) -> Unit
    ): Character {
        // First: check if a better tool already exists in the bank
        val readyMade = try {
            helper.findReadyMadeToolInBank(currentChar, skill)
        } catch (_: Exception) { null }

        if (readyMade != null) {
            onStatus("Found ${readyMade.tool.name} in bank! Withdrawing...")
            helper.bankWithdrawItems(characterName, listOf(SimpleItem(readyMade.tool.code, 1)))

            // Unequip current weapon if any
            var char = helper.refreshCharacter(characterName)
            if (char.weaponSlot.isNotEmpty()) {
                // Deposit old tool to bank
                val oldTool = char.weaponSlot
                char = helper.unequip(characterName, "weapon")
                helper.bankDepositItems(characterName, listOf(SimpleItem(oldTool, 1)))
            }

            char = helper.equip(characterName, readyMade.tool.code, "weapon")
            onStatus("Equipped ${readyMade.tool.name}!")
            return char
        }

        // Second: check if we can craft a better tool from bank materials
        val upgrade = try {
            helper.findBestCraftableToolFromBank(currentChar, skill)
        } catch (_: Exception) {
            null
        } ?: return currentChar

        onStatus("Upgrade available: ${upgrade.tool.name}! Withdrawing materials...")

        // Withdraw ingredients from bank
        var char = helper.bankWithdrawItems(characterName, upgrade.ingredients)

        // Move to the crafting workshop
        val workshop = helper.findNearestWorkshop(char, upgrade.craftSkill)
        if (workshop == null) {
            onStatus("No ${upgrade.craftSkill} workshop found, skipping upgrade")
            // Deposit the ingredients back
            helper.bankDepositItems(characterName, upgrade.ingredients)
            return helper.refreshCharacter(characterName)
        }

        onStatus("Crafting ${upgrade.tool.name} at ${upgrade.craftSkill} workshop...")
        char = helper.moveTo(characterName, workshop.x, workshop.y)
        helper.craft(characterName, upgrade.tool.code, 1)

        // Unequip current weapon if any
        char = helper.refreshCharacter(characterName)
        if (char.weaponSlot.isNotEmpty()) {
            char = helper.unequip(characterName, "weapon")
        }

        // Equip the new tool
        char = helper.equip(characterName, upgrade.tool.code, "weapon")
        onStatus("Equipped ${upgrade.tool.name}!")

        return char
    }

    private suspend fun handleFullInventory(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ): StepResult {
        when (task.onFullInventory) {
            FullInventoryStrategy.BANK_ONLY -> {
                onStatus("Banking items...")
                val char = helper.refreshCharacter(characterName)
                val safeTypes = setOf("resource", "consumable")
                val itemsToDeposit = mutableListOf<com.artifactsmmo.client.models.SimpleItem>()
                for (slot in char.inventory) {
                    if (slot.quantity <= 0) continue
                    val type = helper.getItemType(slot.code)
                    if (type in safeTypes) {
                        itemsToDeposit.add(com.artifactsmmo.client.models.SimpleItem(slot.code, slot.quantity))
                    }
                }
                if (itemsToDeposit.isNotEmpty()) {
                    helper.bankDepositItems(characterName, itemsToDeposit)
                }
                return StepResult.Banked
            }
            FullInventoryStrategy.CRAFT_THEN_BANK -> {
                val char = helper.refreshCharacter(characterName)

                // Find craftable refinements from current inventory
                val craftable = helper.findCraftableRefinements(char, task.skill)
                var totalCrafted = 0

                // Collect raw ingredient codes from recipes we're about to craft,
                // so we can keep leftovers instead of depositing them
                val rawIngredientCodes = mutableSetOf<String>()

                if (craftable.isNotEmpty()) {
                    // Collect ingredient codes
                    for ((item, _) in craftable) {
                        item.craft?.items?.forEach { rawIngredientCodes.add(it.code) }
                    }

                    // Move to workshop
                    val workshopSkill = when (task.skill) {
                        "mining" -> "mining"
                        "woodcutting" -> "woodcutting"
                        "fishing" -> "cooking"
                        "alchemy" -> "alchemy"
                        else -> task.skill
                    }
                    val workshop = helper.findNearestWorkshop(char, workshopSkill)
                    if (workshop != null) {
                        onStatus("Moving to ${workshopSkill} workshop to craft...")
                        helper.moveTo(characterName, workshop.x, workshop.y)

                        // Re-check what's craftable after moving (character state may have changed)
                        val updatedChar = helper.refreshCharacter(characterName)
                        val updatedCraftable = helper.findCraftableRefinements(updatedChar, task.skill)

                        for ((item, maxQty) in updatedCraftable) {
                            onStatus("Crafting ${maxQty}x ${item.name}...")
                            helper.craft(characterName, item.code, maxQty)
                            totalCrafted += maxQty
                        }
                    }
                }

                // Bank resources and consumables, EXCEPT leftover raw ingredients
                val updatedChar = helper.refreshCharacter(characterName)
                val safeTypes2 = setOf("resource", "consumable")
                val itemsToDeposit = mutableListOf<com.artifactsmmo.client.models.SimpleItem>()
                for (slot in updatedChar.inventory) {
                    if (slot.quantity <= 0) continue
                    if (slot.code in rawIngredientCodes) continue
                    val type = helper.getItemType(slot.code)
                    if (type in safeTypes2) {
                        itemsToDeposit.add(com.artifactsmmo.client.models.SimpleItem(slot.code, slot.quantity))
                    }
                }

                if (itemsToDeposit.isNotEmpty()) {
                    onStatus("Banking items (keeping leftover raw materials)...")
                    helper.bankDepositItems(characterName, itemsToDeposit)
                } else {
                    onStatus("Nothing to bank, keeping leftovers")
                }

                return if (totalCrafted > 0) StepResult.CraftedAndBanked(totalCrafted) else StepResult.Banked
            }
        }
    }
}

sealed class StepResult {
    data class Gathered(val xp: Int, val items: List<Pair<String, Int>>) : StepResult()
    data object Banked : StepResult()
    data class CraftedAndBanked(val craftCount: Int) : StepResult()
    data class FightWon(val xp: Int, val gold: Int) : StepResult()
    data class FightLost(val message: String) : StepResult()
    data object Rested : StepResult()
    data object Waiting : StepResult()
    data class Error(val message: String) : StepResult()
    /** Crafting task: successfully crafted (and optionally recycled) items. */
    data class Crafted(val count: Int, val recycled: Int = 0) : StepResult()
    /** Crafting task: ran out of materials in bank + inventory. */
    data object OutOfMaterials : StepResult()
    /** Crafting task (specific mode): target quantity has been reached. */
    data object CraftTaskComplete : StepResult()
    /** Task master: a task was completed and rewards collected. */
    data object TaskMasterTaskCompleted : StepResult()
    /** Task master: current task was cancelled (can't fulfill), will accept new one. */
    data object TaskMasterTaskCancelled : StepResult()
}
