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
     * Tracks which monster code each character's weapon has already been optimised for.
     * Keyed by character name so multiple characters sharing this executor instance do not
     * incorrectly skip each other's weapon-check — previously a single shared var caused
     * character A's optimisation to suppress character B's check.
     */
    private val weaponOptimisedForMonster = mutableMapOf<String, String?>()

    /** Gear optimizer — used by TaskMaster and the re-optimize button. */
    val gearOptimizer = GearOptimizer(helper)

    /**
     * Run a full gear+weapon+utility optimization for TaskMaster use.
     * Delegates to [GearOptimizer.optimizeWithCacheHint] which reuses cached results
     * across similarly-leveled characters when possible.
     */
    suspend fun optimizeGearForMonster(
        characterName: String,
        char: com.artifactsmmo.client.models.Character,
        monsterCode: String
    ): GearOptimizer.OptimizationResult {
        return gearOptimizer.optimizeWithCacheHint(char, monsterCode)
    }

    /**
     * Execute a single iteration of the fight loop.
     *
     * [previousChar] may be supplied by the caller when it already holds a fresh
     * [Character] from the previous iteration's fight response. When provided the
     * leading GET /characters/{name} fetch is skipped entirely.
     */
    suspend fun executeStep(
        characterName: String,
        task: TaskType.Fight,
        onStatus: (String) -> Unit,
        previousChar: com.artifactsmmo.client.models.Character? = null
    ): StepResult {
        // Use the character threaded from the previous tick's fight response when available.
        var char = previousChar ?: helper.refreshCharacter(characterName)

        // ── One-time weapon optimisation per monster target ──
        // Runs once per character when a new monster code is encountered, then skips on every
        // subsequent iteration to avoid per-fight overhead.
        // This is a fast heuristic fallback — Fight tasks assigned via the wizard already have
        // gear+weapon optimized. The check here catches Fight tasks assigned externally or after
        // gear changes since assignment.
        if (!task.loadoutOptimized && weaponOptimisedForMonster[characterName] != task.monsterCode) {
            weaponOptimisedForMonster[characterName] = task.monsterCode
            onStatus("Checking best weapon for ${task.monsterName}...")
            val weaponSwap = try {
                gearOptimizer.findBestWeapon(char, task.monsterCode)
            } catch (_: Exception) { null }

            when {
                weaponSwap == null -> {
                    onStatus("Current weapon is optimal for ${task.monsterName}")
                }
                weaponSwap.itemCode.isEmpty() -> {
                    // No combat weapons owned — unequip the gathering tool so it isn't
                    // used as a combat weapon (e.g. fishing net vs red slimes)
                    onStatus("No combat weapons available — unequipping gathering tool...")
                    try { char = helper.unequip(characterName, "weapon") } catch (_: Exception) {}
                }
                else -> {
                    onStatus("Switching to ${weaponSwap.itemCode} (better vs ${task.monsterName})...")
                    char = helper.retrieveAndEquipItems(characterName, listOf(weaponSwap))
                }
            }
        }

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

        // Check HP - heal if below 75%
        if (!CharacterUtils.hasEnoughHP(char, 0.90)) {
            return handleHealing(characterName, char, cookAndUseDrops, foodCodes, onStatus)
        }

        // Find monster location and move there (handles underground/interior transitions)
        val monsterMap = helper.findNearest(char, "monster", task.monsterCode)
            ?: return StepResult.Error("No ${task.monsterCode} locations found on map")

        if (!helper.isAt(char, monsterMap.x, monsterMap.y) || char.layer != monsterMap.layer) {
            onStatus("Moving to ${task.monsterName}...")
            char = helper.navigateWithTeleport(characterName, char, monsterMap)
        }

        onStatus("Fighting ${task.monsterName}... (HP: ${char.hp}/${char.maxHp})")

        return try {
            val result = helper.fight(characterName)
            val fight = result.fight
            val charResult = fight.characters.find { it.characterName == characterName }
            // The updated Character is in result.characters — thread it to the next tick.
            val updatedChar = result.characters.find { it.name == characterName }

            if (fight.result == "win") {
                val drops = charResult?.drops?.joinToString(", ") { "${it.quantity}x ${it.code}" } ?: ""
                val xp = charResult?.xp ?: 0
                val gold = charResult?.gold ?: 0
                onStatus("Won! +${xp} XP, +${gold} gold${if (drops.isNotEmpty()) ", drops: $drops" else ""}")
                StepResult.FightWon(xp, gold, updatedChar)
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
     * Get the drop strategy for a raw item code, falling back to the task's default.
     */
    private fun getDropStrategy(task: TaskType.Fight, rawCode: String): DropStrategy {
        return task.dropStrategies[rawCode] ?: task.defaultDropStrategy
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
     * Internal entry point for [EventExecutor] to invoke the healing logic without
     * going through the full [executeStep] (which requires a map-cache monster lookup).
     */
    internal suspend fun handleEventHealing(
        characterName: String,
        char: com.artifactsmmo.client.models.Character,
        task: TaskType.Fight,
        onStatus: (String) -> Unit
    ): StepResult {
        val allCookableDrops = getCookableDrops(task.monsterCode, char)
        val cookAndUseDrops = allCookableDrops.filter {
            getDropStrategy(task, it.rawCode) == DropStrategy.COOK_AND_USE
        }
        val foodCodes = buildFoodCodes(cookAndUseDrops)
        return handleHealing(characterName, char, cookAndUseDrops, foodCodes, onStatus)
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
                    helper.navigateWithTeleport(characterName, char, workshop)
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
        //    then fall back to ANY usable food in the bank.
        //    Withdraw only enough to heal + a small carry buffer (10 extra), NOT the entire
        //    free inventory. Over-withdrawing fills inventory → triggers handleFullInventory →
        //    deposits the food → next heal trip re-withdraws → infinite cycle.
        for (info in cookAndUseDrops) {
            val bankQty = helper.getBankItemQuantity(info.cookedCode)
            if (bankQty > 0) {
                val freeCapacity = (char.inventoryMaxItems - char.inventory.sumOf { it.quantity })
                    .coerceAtLeast(0)
                if (freeCapacity == 0) break  // no room — skip to rest
                val qtyToHeal = maxOf(1, hpMissing / info.healAmount)
                val withdrawQty = minOf(bankQty, freeCapacity, qtyToHeal + 25)
                onStatus("Withdrawing ${withdrawQty}x ${info.cookedCode} from bank...")
                helper.bankWithdrawItems(characterName, listOf(SimpleItem(info.cookedCode, withdrawQty)))

                val qty = minOf(withdrawQty, qtyToHeal)
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
            val freeCapacity = (char.inventoryMaxItems - char.inventory.sumOf { it.quantity })
                .coerceAtLeast(0)
            if (freeCapacity > 0) {
                val qtyToHeal = maxOf(1, hpMissing / healAmount)
                val withdrawQty = minOf(bankQty, freeCapacity, qtyToHeal + 25)
                onStatus("Withdrawing ${withdrawQty}x $foodCode from bank...")
                helper.bankWithdrawItems(characterName, listOf(SimpleItem(foodCode, withdrawQty)))

                val qty = minOf(withdrawQty, qtyToHeal)
                onStatus("Eating ${qty}x $foodCode (heals $healAmount each)...")
                val useResult = helper.useItem(characterName, foodCode, qty)
                onStatus("Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
                return StepResult.Rested
            }
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
                    helper.navigateWithTeleport(characterName, char, workshop)
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
                    // Protect any usable food the character is carrying for healing.
                    // Without this guard, bank-withdrawn healing food (e.g. cooked_salmon
                    // fetched during handleHealing) gets deposited right back on the next
                    // handleFullInventory call, creating an endless bank-trip cycle.
                    val isHealingFood = try {
                        val item = helper.contentCache.getItemOrNull(slot.code)
                        item != null &&
                        item.type == "consumable" &&
                        item.level <= char.level &&
                        item.effects.any { it.code == "heal" && it.value > 0 }
                    } catch (_: Exception) { false }
                    if (isHealingFood) continue

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

    /**
     * Execute a single boss-fight iteration.
     *
     * Initiator path (task.isInitiator = true):
     *  1. Heal to full HP
     *  2. Navigate to boss tile
     *  3. Ensure 1 free inventory slot
     *  4. Update status and await all participants via [coordinator]
     *  5. Fire fight API with participant names
     *  6. Return FightWon / FightLost
     *
     * Participant path (task.isInitiator = false):
     *  1. Heal to full HP
     *  2. Navigate to boss tile
     *  3. Ensure 1 free inventory slot
     *  4. Signal ready via [coordinator]
     *  5. Wait for the server-applied fight cooldown to expire
     *  6. Return StepResult.Waiting to re-enter the loop for the next round
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun executeBossStep(
        characterName: String,
        task: TaskType.BossFight,
        coordinator: BossEncounterCoordinator,
        onStatus: (String) -> Unit,
        previousChar: com.artifactsmmo.client.models.Character? = null
    ): StepResult {
        var char = previousChar ?: helper.refreshCharacter(characterName)

        // ── Restock check — evaluate BEFORE healing so we don't waste a rest action ──
        // Conditions that require leaving the dungeon for a full bank resupply:
        //   1. Inventory is full of loot (no room for boss drops)
        //   2. Both utility slots are empty AND no inventory reserves remain
        //   3. No food left AND HP is too low to fight safely
        val protectedCodes = task.reservePotions.keys + setOfNotNull(task.foodCode)
        val nonProtectedUsed = char.inventory
            .filter { it.quantity > 0 && it.code !in protectedCodes }
            .sumOf { it.quantity }
        val totalUsed = char.inventory.filter { it.quantity > 0 }.sumOf { it.quantity }
        val inventoryFull = totalUsed >= char.inventoryMaxItems && nonProtectedUsed > 0

        val u1Code = helper.getEquippedInSlot(char, "utility1")
        val u2Code = helper.getEquippedInSlot(char, "utility2")
        val u1Empty = u1Code.isEmpty() || helper.getEquippedUtilityQuantity(char, "utility1") == 0
        val u2Empty = u2Code.isEmpty() || helper.getEquippedUtilityQuantity(char, "utility2") == 0
        val noReserves = task.reservePotions.keys.all { helper.getItemQuantity(char, it) == 0 }
        val potionsExhausted = u1Empty && u2Empty && noReserves

        val noFood = helper.findBestFoodInInventory(char) == null
        val hpTooLow = !CharacterUtils.hasEnoughHP(char, 0.75)

        if (inventoryFull || potionsExhausted || (noFood && hpTooLow)) {
            val reason = when {
                inventoryFull -> "inventory full of loot"
                potionsExhausted -> "utility potions exhausted"
                else -> "no food and HP too low"
            }
            onStatus("Restock needed ($reason) — heading to bank...")
            return StepResult.NeedsRestock
        }

        // ── Heal from inventory food only (no bank trips inside the dungeon) ──
        if (!CharacterUtils.hasEnoughHP(char, 0.75)) {
            val bestFood = helper.findBestFoodInInventory(char)
            if (bestFood != null) {
                val (foodCode, healAmount, available) = bestFood
                val hpMissing = char.maxHp - char.hp
                val qty = minOf(available, maxOf(1, hpMissing / healAmount))
                onStatus("Eating ${qty}x $foodCode (heals $healAmount each)...")
                val useResult = helper.useItem(characterName, foodCode, qty)
                onStatus("Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
                return StepResult.Rested
            }
            // No food in inventory — rest as last resort
            onStatus("HP low (${char.hp}/${char.maxHp}), no food in inventory, resting...")
            helper.rest(characterName)
            return StepResult.Rested
        }

        // ── Utility slot reserve re-equip ──────────────────────────────────────
        // If any utility slot has dropped to or below the re-equip threshold AND the
        // character still has reserve potions in inventory for that slot's potion type,
        // re-equip from inventory now — before moving to the boss tile.
        if (task.reservePotions.isNotEmpty()) {
            val reequipActions = mutableListOf<GearOptimizer.UtilityEquipAction>()
            for (utilitySlot in listOf("utility1", "utility2")) {
                val equippedCode = helper.getEquippedInSlot(char, utilitySlot)
                if (equippedCode.isEmpty()) continue
                val equippedQty = helper.getEquippedUtilityQuantity(char, utilitySlot)
                if (equippedQty > CoopOptimizer.UTILITY_REEQUIP_THRESHOLD) continue
                // Slot is below threshold — check if we have reserves in inventory
                val reserveInInv = helper.getItemQuantity(char, equippedCode)
                if (reserveInInv <= 0) continue
                val newQty = (equippedQty + reserveInInv).coerceAtMost(CoopOptimizer.UTILITY_MAX_QUANTITY)
                onStatus("Refilling $utilitySlot ($equippedCode: ${equippedQty} → ${newQty})...")
                reequipActions.add(GearOptimizer.UtilityEquipAction(utilitySlot, equippedCode, newQty, "inventory"))
            }
            if (reequipActions.isNotEmpty()) {
                char = helper.retrieveAndEquipUtilities(characterName, reequipActions)
            }
        }

        // Navigate to boss tile
        val monsterMap = helper.findNearest(char, "monster", task.monsterCode)
            ?: return StepResult.Error("No ${task.monsterCode} locations found on map")

        if (!helper.isAt(char, monsterMap.x, monsterMap.y) || char.layer != monsterMap.layer) {
            onStatus("Moving to ${task.monsterName}...")
            char = helper.navigateWithTeleport(characterName, char, monsterMap)
        }

        if (task.isInitiator) {
            // ── Initiator path ──────────────────────────────────────────────────
            if (task.participantNames.isNotEmpty()) {
                onStatus("Waiting for participants: ${task.participantNames.joinToString(", ")}...")
                try {
                    coordinator.awaitParticipants(characterName)
                } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                    // Encounter was cleared (stop/cancel); bail out
                    return StepResult.Waiting
                }
            }

            onStatus("Fighting ${task.monsterName} (boss)... (HP: ${char.hp}/${char.maxHp})")
            return try {
                val result = helper.fight(characterName, participants = task.participantNames)
                val fight = result.fight
                val charResult = fight.characters.find { it.characterName == characterName }
                val updatedChar = result.characters.find { it.name == characterName }

                if (fight.result == "win") {
                    val drops = charResult?.drops?.joinToString(", ") { "${it.quantity}x ${it.code}" } ?: ""
                    val xp = charResult?.xp ?: 0
                    val gold = charResult?.gold ?: 0
                    onStatus("Won! +${xp} XP, +${gold} gold${if (drops.isNotEmpty()) ", drops: $drops" else ""}")
                    StepResult.FightWon(xp, gold, updatedChar)
                } else {
                    onStatus("Lost boss fight against ${task.monsterName}")
                    StepResult.FightLost("Lost to ${task.monsterName}")
                }
            } catch (e: com.artifactsmmo.client.ArtifactsApiException) {
                if (e.errorCode == 486) StepResult.Waiting else throw e
            }
        } else {
            // ── Participant path ─────────────────────────────────────────────────
            onStatus("Ready for boss fight vs ${task.monsterName}, signalling initiator...")
            try {
                coordinator.signalReady(characterName)
            } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                // Encounter was cleared; bail out
                return StepResult.Waiting
            }

            // The initiator fires the fight API after collecting all ready signals.
            // The server then applies a cooldown to all participants automatically.
            // We must wait for that cooldown to actually appear before draining it —
            // polling with short sleeps until cooldown > 0, then waiting it out.
            onStatus("Boss fight in progress, waiting for cooldown...")
            var cooldownSeen = false
            repeat(30) { // poll up to ~15 seconds for the cooldown to appear
                if (cooldownSeen) return@repeat
                val polledChar = try { helper.refreshCharacter(characterName) } catch (_: Exception) { return@repeat }
                if (polledChar.cooldown > 0) {
                    cooldownSeen = true
                    onStatus("Boss fight complete, waiting ${polledChar.cooldown}s cooldown...")
                    helper.waitForCooldown(polledChar.cooldownExpiration)
                } else {
                    kotlinx.coroutines.delay(500)
                }
            }

            onStatus("Ready for next boss fight round...")
            return StepResult.Waiting
        }
    }

    /**
     * Full resupply trip for a boss fight loop. Called when [StepResult.NeedsRestock]
     * is returned by [executeBossStep].
     *
     * Single bank trip that:
     *  1. Deposits all loot (keeps reserve potions, food, and dungeon keys)
     *  2. Refills reserve potions to their target quantities
     *  3. Re-equips utility slots to 100
     *  4. Withdraws food up to [CoopOptimizer.FOOD_BUFFER]
     *  5. Withdraws transition costs + spare keys for re-entry
     *  6. Navigates back to the boss tile (consuming keys at the transition)
     */
    suspend fun restockForBossFight(
        characterName: String,
        task: TaskType.BossFight,
        onStatus: (String) -> Unit
    ) {
        var char = helper.refreshCharacter(characterName)

        // Navigate to bank (exits dungeon if character is still inside; uses a held
        // return teleport potion when it saves significant time)
        onStatus("Restocking — heading to bank...")
        char = try {
            helper.navigateToBank(characterName, char)
        } catch (e: IllegalStateException) {
            onStatus("No bank found — cannot restock")
            return
        }

        // 1. Deposit all loot — keep reserve potions, food, and keys
        val keepCodes = task.reservePotions.keys +
            setOfNotNull(task.foodCode) +
            task.transitionCosts.keys +
            task.spareKeys.keys
        val lootToDeposit = char.inventory
            .filter { it.quantity > 0 && it.code !in keepCodes }
            .map { com.artifactsmmo.client.models.SimpleItem(it.code, it.quantity) }
        if (lootToDeposit.isNotEmpty()) {
            onStatus("Depositing ${lootToDeposit.sumOf { it.quantity }} loot items...")
            helper.bankDepositItems(characterName, lootToDeposit)
            char = helper.refreshCharacter(characterName)
        }

        // 2. Refill reserve potions to target quantities
        if (task.reservePotions.isNotEmpty()) {
            onStatus("Restocking reserve potions...")
            helper.withdrawReservePotions(characterName, task.reservePotions)
            char = helper.refreshCharacter(characterName)
        }

        // 3. Re-equip utility slots to 100 (from refilled reserves or bank)
        val reequipActions = mutableListOf<GearOptimizer.UtilityEquipAction>()
        for (utilitySlot in listOf("utility1", "utility2")) {
            val equippedCode = helper.getEquippedInSlot(char, utilitySlot)
            if (equippedCode.isEmpty()) continue
            val equippedQty = helper.getEquippedUtilityQuantity(char, utilitySlot)
            if (equippedQty >= CoopOptimizer.UTILITY_MAX_QUANTITY) continue
            val inInv = helper.getItemQuantity(char, equippedCode)
            if (inInv > 0) {
                val newQty = (equippedQty + inInv).coerceAtMost(CoopOptimizer.UTILITY_MAX_QUANTITY)
                reequipActions.add(GearOptimizer.UtilityEquipAction(utilitySlot, equippedCode, newQty, "inventory"))
            }
        }
        if (reequipActions.isNotEmpty()) {
            onStatus("Refilling utility slots...")
            char = helper.retrieveAndEquipUtilities(characterName, reequipActions)
        }

        // 4. Restock food
        if (task.foodCode != null && task.foodQuantity > 0) {
            val currentFood = helper.getItemQuantity(char, task.foodCode)
            val foodDeficit = task.foodQuantity - currentFood
            if (foodDeficit > 0) {
                val bankQty = helper.getBankItemQuantity(task.foodCode)
                val toWithdraw = foodDeficit.coerceAtMost(bankQty)
                if (toWithdraw > 0) {
                    onStatus("Restocking food (${toWithdraw}x ${task.foodCode})...")
                    helper.bankWithdrawItems(characterName, listOf(com.artifactsmmo.client.models.SimpleItem(task.foodCode, toWithdraw)))
                    char = helper.refreshCharacter(characterName)
                }
            }
        }

        // 5. Restock transition keys (entry cost + spare).
        //    Gold costs are NOT stocked from bank — gold is on the character and deducted
        //    automatically by the server at transition time. Only item costs are restocked.
        val allKeys = (task.transitionCosts.entries + task.spareKeys.entries)
            .filter { it.key != "gold" }   // skip gold — not a bank-withdrawable item
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.sum() }
        if (allKeys.isNotEmpty()) {
            val keyWithdrawals = mutableListOf<com.artifactsmmo.client.models.SimpleItem>()
            for ((code, needed) in allKeys) {
                val inInv = helper.getItemQuantity(char, code)
                val deficit = needed - inInv
                if (deficit > 0) {
                    val bankQty = helper.getBankItemQuantity(code)
                    val toWithdraw = deficit.coerceAtMost(bankQty)
                    if (toWithdraw > 0) keyWithdrawals.add(com.artifactsmmo.client.models.SimpleItem(code, toWithdraw))
                }
            }
            if (keyWithdrawals.isNotEmpty()) {
                onStatus("Restocking dungeon keys (${keyWithdrawals.joinToString { "${it.quantity}x ${it.code}" }})...")
                helper.bankWithdrawItems(characterName, keyWithdrawals)
            }
        }

        // 6. Navigate back to boss tile (transition consumes keys automatically)
        onStatus("Restock complete — returning to ${task.monsterName}...")
        val monsterMap = helper.findNearest(char, "monster", task.monsterCode)
        if (monsterMap != null) {
            helper.navigateWithTeleport(characterName, helper.refreshCharacter(characterName), monsterMap)
        }
    }
}
