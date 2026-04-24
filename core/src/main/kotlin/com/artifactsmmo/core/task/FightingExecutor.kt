package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.utils.CharacterUtils

/**
 * Executes fighting task loops.
 *
 * Loop: move to monster -> fight -> heal if needed -> repeat
 *       when inventory full -> handle drops per strategy, bank non-food, keep food on hand
 *
 * Drop strategies (per cookable drop):
 *   - COOK_AND_USE: cook and keep on hand for healing (default)
 *   - COOK_AND_BANK: cook then deposit to bank
 *   - BANK_RAW: deposit raw without cooking
 *
 * Healing priority:
 *   1. Eat cooked food from inventory (COOK_AND_USE drops only)
 *   2. Cook raw food from inventory (COOK_AND_USE drops only), then eat
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
        val allCookableDrops = getCookableDrops(task.monsterCode, char)

        // Split drops by strategy
        val cookAndUseDrops = allCookableDrops.filter {
            getDropStrategy(task, it.rawCode) == DropStrategy.COOK_AND_USE
        }
        val cookAndBankDrops = allCookableDrops.filter {
            getDropStrategy(task, it.rawCode) == DropStrategy.COOK_AND_BANK
        }
        val bankRawDrops = allCookableDrops.filter {
            getDropStrategy(task, it.rawCode) == DropStrategy.BANK_RAW
        }

        // Food codes: only COOK_AND_USE drops are considered "food" to keep on hand
        val foodCodes = buildFoodCodes(cookAndUseDrops)

        // Check if inventory is full
        if (helper.isInventoryFull(char)) {
            onStatus("Inventory full, handling...")
            return handleFullInventory(characterName, cookAndUseDrops, cookAndBankDrops, bankRawDrops, foodCodes, onStatus)
        }

        // Check HP - heal if below 50%
        if (!CharacterUtils.hasEnoughHP(char, 0.5)) {
            return handleHealing(characterName, char, cookAndUseDrops, foodCodes, onStatus)
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
     * Get the drop strategy for a raw item code, defaulting to COOK_AND_USE.
     */
    private fun getDropStrategy(task: TaskType.Fight, rawCode: String): DropStrategy {
        return task.dropStrategies[rawCode] ?: DropStrategy.COOK_AND_USE
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
     * Only includes COOK_AND_USE drops.
     */
    private fun buildFoodCodes(cookAndUseDrops: List<ActionHelper.CookableDropInfo>): Set<String> {
        val codes = mutableSetOf<String>()
        for (info in cookAndUseDrops) {
            codes.add(info.rawCode)
            codes.add(info.cookedCode)
        }
        return codes
    }

    /**
     * Handle healing when HP is low.
     * Priority: eat cooked food > cook raw food then eat (COOK_AND_USE only) > withdraw from bank > rest
     */
    private suspend fun handleHealing(
        characterName: String,
        char: com.artifactsmmo.client.models.Character,
        cookAndUseDrops: List<ActionHelper.CookableDropInfo>,
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

        // 2. Try to cook raw food from inventory (only COOK_AND_USE drops)
        for (info in cookAndUseDrops) {
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

        // 3. Try to withdraw cooked food from bank — first check this monster's COOK_AND_USE drops,
        //    then fall back to ANY usable food in the bank
        for (info in cookAndUseDrops) {
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
     * - COOK_AND_USE drops: cook, keep on hand for healing
     * - COOK_AND_BANK drops: cook, then deposit to bank
     * - BANK_RAW drops: deposit raw to bank
     * - Non-food items: bank per deposit rules
     */
    private suspend fun handleFullInventory(
        characterName: String,
        cookAndUseDrops: List<ActionHelper.CookableDropInfo>,
        cookAndBankDrops: List<ActionHelper.CookableDropInfo>,
        bankRawDrops: List<ActionHelper.CookableDropInfo>,
        foodCodes: Set<String>,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)
        var totalCrafted = 0

        // Collect all drops that need cooking (COOK_AND_USE + COOK_AND_BANK)
        val dropsToCook = cookAndUseDrops + cookAndBankDrops

        if (dropsToCook.isNotEmpty()) {
            var needsWorkshop = false
            for (info in dropsToCook) {
                val rawQty = helper.getItemQuantity(char, info.rawCode)
                if (rawQty >= info.rawPerCraft) needsWorkshop = true
            }

            if (needsWorkshop) {
                val workshop = helper.findNearestWorkshop(char, "cooking")
                if (workshop != null) {
                    onStatus("Moving to cooking workshop...")
                    helper.moveTo(characterName, workshop.x, workshop.y)
                    char = helper.refreshCharacter(characterName)

                    for (info in dropsToCook) {
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

        // Build the set of cooked item codes from COOK_AND_BANK drops — these get deposited
        val cookAndBankCookedCodes = cookAndBankDrops.map { it.cookedCode }.toSet()

        // Build the set of raw item codes from BANK_RAW drops — these get deposited raw
        val bankRawCodes = bankRawDrops.map { it.rawCode }.toSet()

        // Build deposit list
        val itemsToDeposit = mutableListOf<SimpleItem>()
        val foodToKeep = 25 // Keep up to this many cooked food items (COOK_AND_USE only)

        for (slot in char.inventory) {
            if (slot.quantity <= 0) continue

            when {
                // COOK_AND_BANK cooked items: always deposit all
                slot.code in cookAndBankCookedCodes -> {
                    itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
                }
                // BANK_RAW items: always deposit all
                slot.code in bankRawCodes -> {
                    itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
                }
                // COOK_AND_USE food: keep some, bank excess
                slot.code in foodCodes -> {
                    val cookableInfo = cookAndUseDrops.find { it.cookedCode == slot.code }
                    if (cookableInfo != null) {
                        // Cooked food from COOK_AND_USE — keep up to foodToKeep
                        if (slot.quantity > foodToKeep) {
                            itemsToDeposit.add(SimpleItem(slot.code, slot.quantity - foodToKeep))
                        }
                    } else {
                        // Raw food that we couldn't cook — bank it
                        itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
                    }
                }
                // Everything else: deposit per standard rules
                else -> {
                    val shouldDeposit = try { helper.shouldDepositItem(char, slot.code) } catch (_: Exception) { true }
                    if (!shouldDeposit) continue
                    itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
                }
            }
        }

        if (itemsToDeposit.isNotEmpty()) {
            onStatus("Banking ${itemsToDeposit.sumOf { it.quantity }} items...")
            helper.bankDepositItems(characterName, itemsToDeposit)
        }

        return if (totalCrafted > 0) StepResult.CraftedAndBanked(totalCrafted) else StepResult.Banked
    }
}
