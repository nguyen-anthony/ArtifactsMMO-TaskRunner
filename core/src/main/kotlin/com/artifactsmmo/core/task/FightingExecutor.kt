package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.utils.CharacterUtils

/**
 * Executes fighting task loops.
 *
 * Loop: move to monster -> fight -> heal if needed -> repeat
 *       when inventory full -> cook droppable food, bank non-food, keep food on hand
 *
 * Healing priority:
 *   1. Eat cooked food from inventory
 *   2. Cook raw food from inventory, then eat
 *   3. Withdraw cooked food from bank, then eat
 *   4. Rest as last resort
 */
class FightingExecutor(private val helper: ActionHelper) {

    /** Cached cookable drop info per monster, populated once per monster type. */
    private val monsterCookableCache = mutableMapOf<String, List<ActionHelper.CookableDropInfo>>()

    /**
     * Execute a single iteration of the fight loop.
     */
    suspend fun executeStep(
        characterName: String,
        task: TaskType.Fight,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)

        // Discover cookable drops for this monster (cached after first call)
        val cookableDrops = getCookableDrops(task.monsterCode, char)

        // The set of item codes we consider "food-related" and want to keep on hand
        val foodCodes = buildFoodCodes(cookableDrops)

        // Check if inventory is full
        if (helper.isInventoryFull(char)) {
            onStatus("Inventory full, handling...")
            return handleFullInventory(characterName, cookableDrops, foodCodes, onStatus)
        }

        // Check HP - heal if below 50%
        if (!CharacterUtils.hasEnoughHP(char, 0.5)) {
            return handleHealing(characterName, char, cookableDrops, foodCodes, onStatus)
        }

        // Find monster location and move there
        val monsterMap = helper.findNearest(char, "monster", task.monsterCode)
            ?: return StepResult.Error("No ${task.monsterCode} locations found on map")

        if (!helper.isAt(char, monsterMap.x, monsterMap.y)) {
            onStatus("Moving to ${task.monsterName}...")
            char = helper.moveTo(characterName, monsterMap.x, monsterMap.y)
        }

        onStatus("Fighting ${task.monsterName}... (HP: ${char.hp}/${char.maxHp})")

        return try {
            val result = helper.fight(characterName)
            val fight = result.fight
            val charResult = fight.characters.find { it.characterName == characterName }

            if (fight.result == "win") {
                val drops = charResult?.drops?.joinToString(", ") { "${it.quantity}x ${it.code}" } ?: ""
                val xp = charResult?.xp ?: 0
                val gold = charResult?.gold ?: 0
                onStatus("Won! +${xp} XP, +${gold} gold${if (drops.isNotEmpty()) ", drops: $drops" else ""}")
                StepResult.FightWon(xp, gold)
            } else {
                onStatus("Lost fight against ${task.monsterName}")
                StepResult.FightLost("Lost to ${task.monsterName}")
            }
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 486) {
                StepResult.Waiting
            } else throw e
        }
    }

    /**
     * Get cookable drops for a monster, filtering by character's cooking level.
     */
    private suspend fun getCookableDrops(
        monsterCode: String,
        char: com.artifactsmmo.client.models.Character
    ): List<ActionHelper.CookableDropInfo> {
        if (!monsterCookableCache.containsKey(monsterCode)) {
            monsterCookableCache[monsterCode] = helper.findCookableDrops(monsterCode)
        }
        val cookingLevel = CharacterUtils.getSkillLevel(char, "cooking") ?: 0
        return monsterCookableCache[monsterCode]!!.filter {
            it.cookingLevelRequired <= cookingLevel && it.useLevelRequired <= char.level
        }
    }

    /**
     * Build the set of item codes we consider "food" (raw + cooked) to keep on hand.
     */
    private fun buildFoodCodes(cookableDrops: List<ActionHelper.CookableDropInfo>): Set<String> {
        val codes = mutableSetOf<String>()
        for (info in cookableDrops) {
            codes.add(info.rawCode)
            codes.add(info.cookedCode)
        }
        return codes
    }

    /**
     * Handle healing when HP is low.
     * Priority: eat cooked food > cook raw food then eat > withdraw from bank > rest
     */
    private suspend fun handleHealing(
        characterName: String,
        char: com.artifactsmmo.client.models.Character,
        cookableDrops: List<ActionHelper.CookableDropInfo>,
        foodCodes: Set<String>,
        onStatus: (String) -> Unit
    ): StepResult {
        val hpMissing = char.maxHp - char.hp

        // 1. Try to eat cooked food from inventory
        val bestFood = helper.findBestFoodInInventory(char)
        if (bestFood != null) {
            val (foodCode, healAmount, available) = bestFood
            val qty = minOf(available, maxOf(1, hpMissing / healAmount))
            onStatus("Eating ${qty}x $foodCode (heals $healAmount each)...")
            val useResult = helper.useItem(characterName, foodCode, qty)
            onStatus("Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
            return StepResult.Rested
        }

        // 2. Try to cook raw food from inventory (only drops we can actually eat)
        for (info in cookableDrops) {
            val rawQty = helper.getItemQuantity(char, info.rawCode)
            if (rawQty >= info.rawPerCraft) {
                val craftQty = rawQty / info.rawPerCraft
                onStatus("Cooking ${craftQty}x ${info.cookedCode} (from ${craftQty * info.rawPerCraft}x ${info.rawCode})...")
                val workshop = helper.findNearestWorkshop(char, "cooking")
                if (workshop != null) {
                    helper.moveTo(characterName, workshop.x, workshop.y)
                    helper.craft(characterName, info.cookedCode, craftQty)

                    // Now eat
                    val qty = minOf(craftQty, maxOf(1, hpMissing / info.healAmount))
                    onStatus("Eating ${qty}x ${info.cookedCode}...")
                    val useResult = helper.useItem(characterName, info.cookedCode, qty)
                    onStatus("Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
                    return StepResult.Rested
                }
            }
        }

        // 3. Try to withdraw cooked food from bank — first check this monster's drops,
        //    then fall back to ANY usable food in the bank
        for (info in cookableDrops) {
            val bankQty = helper.getBankItemQuantity(info.cookedCode)
            if (bankQty > 0) {
                val withdrawQty = minOf(bankQty, 25)
                onStatus("Withdrawing ${withdrawQty}x ${info.cookedCode} from bank...")
                helper.bankWithdrawItems(characterName, listOf(SimpleItem(info.cookedCode, withdrawQty)))

                val qty = minOf(withdrawQty, maxOf(1, hpMissing / info.healAmount))
                onStatus("Eating ${qty}x ${info.cookedCode}...")
                val useResult = helper.useItem(characterName, info.cookedCode, qty)
                onStatus("Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
                return StepResult.Rested
            }
        }

        // 4. Search the entire bank for ANY usable food
        val bankFood = helper.findBestFoodInBank(char)
        if (bankFood != null) {
            val (foodCode, healAmount, bankQty) = bankFood
            val withdrawQty = minOf(bankQty, 25)
            onStatus("Withdrawing ${withdrawQty}x $foodCode from bank...")
            helper.bankWithdrawItems(characterName, listOf(SimpleItem(foodCode, withdrawQty)))

            val qty = minOf(withdrawQty, maxOf(1, hpMissing / healAmount))
            onStatus("Eating ${qty}x $foodCode (heals $healAmount each)...")
            val useResult = helper.useItem(characterName, foodCode, qty)
            onStatus("Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
            return StepResult.Rested
        }

        // 5. Last resort: rest
        onStatus("HP low (${char.hp}/${char.maxHp}), no food available, resting...")
        helper.rest(characterName)
        return StepResult.Rested
    }

    /**
     * Handle full inventory during fighting.
     * - Cook any raw food drops into cooked versions
     * - Bank everything EXCEPT food (cooked food stays on character)
     * - If inventory is mostly food, bank excess food but keep ~20
     */
    private suspend fun handleFullInventory(
        characterName: String,
        cookableDrops: List<ActionHelper.CookableDropInfo>,
        foodCodes: Set<String>,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)
        var totalCrafted = 0

        // Cook any raw food in inventory
        if (cookableDrops.isNotEmpty()) {
            val workshop = helper.findNearestWorkshop(char, "cooking")
            if (workshop != null) {
                var needsWorkshop = false
                for (info in cookableDrops) {
                    val rawQty = helper.getItemQuantity(char, info.rawCode)
                    if (rawQty >= info.rawPerCraft) needsWorkshop = true
                }

                if (needsWorkshop) {
                    onStatus("Moving to cooking workshop...")
                    helper.moveTo(characterName, workshop.x, workshop.y)
                    char = helper.refreshCharacter(characterName)

                    for (info in cookableDrops) {
                        val rawQty = helper.getItemQuantity(char, info.rawCode)
                        val craftQty = rawQty / info.rawPerCraft
                        if (craftQty > 0) {
                            onStatus("Cooking ${craftQty}x ${info.cookedCode} (from ${craftQty * info.rawPerCraft}x ${info.rawCode})...")
                            helper.craft(characterName, info.cookedCode, craftQty)
                            totalCrafted += craftQty
                        }
                    }
                    char = helper.refreshCharacter(characterName)
                }
            }
        }

        // Build deposit list: everything EXCEPT food we want to keep
        val itemsToDeposit = mutableListOf<SimpleItem>()
        val foodToKeep = 25 // Keep up to this many cooked food items

        for (slot in char.inventory) {
            if (slot.quantity <= 0) continue

            if (slot.code in foodCodes) {
                // For cooked food: keep some, bank the excess
                val cookableInfo = cookableDrops.find { it.cookedCode == slot.code }
                if (cookableInfo != null) {
                    // This is cooked food - keep up to foodToKeep
                    if (slot.quantity > foodToKeep) {
                        itemsToDeposit.add(SimpleItem(slot.code, slot.quantity - foodToKeep))
                    }
                    // Don't deposit any if we have <= foodToKeep
                } else {
                    // This is raw food that we couldn't cook (shouldn't happen after cooking step,
                    // but handle edge case) - bank it
                    itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
                }
            } else {
                // Not food - bank everything
                itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
            }
        }

        if (itemsToDeposit.isNotEmpty()) {
            onStatus("Banking ${itemsToDeposit.sumOf { it.quantity }} non-food items...")
            helper.bankDepositItems(characterName, itemsToDeposit)
        }

        return if (totalCrafted > 0) StepResult.CraftedAndBanked(totalCrafted) else StepResult.Banked
    }
}
