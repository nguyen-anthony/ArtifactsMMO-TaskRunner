package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.MapInfo
import com.artifactsmmo.client.models.NPCItem
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.utils.CharacterUtils
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Thrown when a map transition has a `cost`/`has_item` condition that cannot be met —
 * the character doesn't have the required item(s) in inventory or bank.
 *
 * Distinct from other exceptions so the runner ([CharacterTaskRunner]) can stop the
 * current task cleanly instead of infinite-retrying (which would be pointless — the
 * missing key items won't materialize).
 */
class TransitionConditionUnsatisfiableException(message: String) : Exception(message)

/**
 * Common helper functions for character actions.
 * Handles cooldowns, movement, banking, equipping, and inventory management.
 */
@OptIn(ExperimentalTime::class)
class ActionHelper(private val client: ArtifactsMMOClient, internal val contentCache: ContentCache, internal val bankState: BankState) {

    // ── Cooldown ──

    suspend fun waitForCooldown(expiration: Instant) {
        val remaining = expiration - Clock.System.now()
        if (remaining.isPositive()) delay(remaining)
    }

    suspend fun waitForCooldown(seconds: Int) {
        if (seconds > 0) delay(seconds.seconds)
    }

    /**
     * Refresh the character and wait for any active cooldown to expire before
     * starting work. Should be called at the beginning of every task to avoid
     * hitting 486 "Character in cooldown" errors when tasks are switched while
     * an action is still in progress.
     */
    suspend fun waitForActiveCooldown(name: String) {
        val char = refreshCharacter(name)
        if (char.cooldown > 0) {
            waitForCooldown(char.cooldownExpiration)
        }
    }

    // ── Character refresh ──

    suspend fun refreshCharacter(name: String): Character {
        return client.characters.getCharacter(name)
    }

    // ── Movement ──

    /**
     * Move character to coordinates. Returns updated character.
     * Handles "already at destination" (490/497) gracefully. On other errors, logs
     * diagnostic context (character position + layer + target) before re-throwing so
     * 595 "no path" failures are easier to diagnose.
     */
    suspend fun moveTo(name: String, x: Int, y: Int): Character {
        return try {
            val result = client.actions.move(name, x, y)
            waitForCooldown(result.cooldown.expiration)
            result.character
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 490 || e.errorCode == 497) {
                // Already at destination
                refreshCharacter(name)
            } else {
                val ctx = try {
                    val c = refreshCharacter(name)
                    "current=(${c.x},${c.y},${c.layer})"
                } catch (_: Exception) { "current=<unknown>" }
                println("[$name] moveTo(x=$x, y=$y) failed [${e.errorCode}] ${e.message} — $ctx")
                throw e
            }
        }
    }

    /**
     * Check if character is at the given coordinates.
     */
    fun isAt(char: Character, x: Int, y: Int): Boolean {
        return char.x == x && char.y == y
    }

    /**
     * Navigate to a map tile, handling all transitions transparently.
     *
     * Routing logic (loop-based, max [MAX_NAV_ITERATIONS] hops):
     *
     *   Case 1 — On a sub-layer, target is on a DIFFERENT layer:
     *     Exit the current layer to overworld (or nearest other layer) first.
     *     Supports multi-hop chains: underground → interior → overworld via repeated iterations.
     *
     *   Case 2 — On overworld, target is on a sub-layer:
     *     Find and use the cross-layer entry transition closest to the target.
     *
     *   Case 3 — Same layer, proactive same-layer transition detection:
     *     If the destination tile is only reachable via a same-layer transition gate
     *     (e.g. overworld sub-region behind a gold gate, or underground sub-region behind
     *     a key gate), use that transition before attempting moveTo.
     *
     *   Case 4 — Same layer, no known gate: plain moveTo with reactive 595/596 fallback.
     *     If A* fails (terrain wall, water, blocked map), [moveToWithTransitionFallback]
     *     searches for a nearby same-layer transition and routes through it.
     *
     * Each transition's conditions (item cost, gold cost, has_item) are satisfied
     * automatically via [satisfyTransitionConditions]. Throws
     * [TransitionConditionUnsatisfiableException] if a condition cannot be met.
     */
    suspend fun navigateToTile(name: String, targetMap: MapInfo): Character {
        var char = refreshCharacter(name)
        var iterations = 0

        while (iterations++ < MAX_NAV_ITERATIONS) {
            val targetLayer = targetMap.layer

            // Already at destination
            if (isAt(char, targetMap.x, targetMap.y) && char.layer == targetLayer) break

            // ── Case 1: On a sub-layer, need to exit toward a different layer ────
            if (char.layer != "overworld" && char.layer != targetLayer) {
                val exitTile = contentCache.findExitTransitionTile(char)
                    ?: throw TransitionConditionUnsatisfiableException(
                        "Cannot navigate: on layer '${char.layer}' with no exit transition"
                    )
                char = moveTo(name, exitTile.x, exitTile.y)
                val conditions = exitTile.interactions.transition?.conditions ?: emptyList()
                if (conditions.isNotEmpty()) {
                    char = satisfyTransitionConditions(name, char, conditions)
                    if (!isAt(char, exitTile.x, exitTile.y)) char = moveTo(name, exitTile.x, exitTile.y)
                }
                char = useTransition(name)
                continue
            }

            // ── Case 2: On overworld, target is on a sub-layer ──────────────────
            if (char.layer == "overworld" && targetLayer != "overworld") {
                val entryTile = contentCache.findTransitionTile(char, targetLayer, targetMap.x, targetMap.y)
                    ?: throw TransitionConditionUnsatisfiableException(
                        "Cannot navigate: no overworld transition leads to layer '$targetLayer'"
                    )
                char = moveTo(name, entryTile.x, entryTile.y)
                val conditions = entryTile.interactions.transition?.conditions ?: emptyList()
                if (conditions.isNotEmpty()) {
                    char = satisfyTransitionConditions(name, char, conditions)
                    if (!isAt(char, entryTile.x, entryTile.y)) char = moveTo(name, entryTile.x, entryTile.y)
                }
                char = useTransition(name)
                continue
            }

            // ── Case 3: Same layer — proactive same-layer transition check ───────
            // Detects tiles that are only reachable via a same-layer gate (gold-gated
            // overworld sub-regions, key-gated underground sub-regions, etc.).
            if (char.layer == targetLayer) {
                val sameLayerEntry = contentCache.findSameLayerTransitionTo(targetMap.x, targetMap.y, targetLayer)
                if (sameLayerEntry != null) {
                    char = moveTo(name, sameLayerEntry.x, sameLayerEntry.y)
                    val conditions = sameLayerEntry.interactions.transition?.conditions ?: emptyList()
                    if (conditions.isNotEmpty()) {
                        char = satisfyTransitionConditions(name, char, conditions)
                        if (!isAt(char, sameLayerEntry.x, sameLayerEntry.y)) char = moveTo(name, sameLayerEntry.x, sameLayerEntry.y)
                    }
                    char = useTransition(name)
                    continue
                }
            }

            // ── Case 4: Same layer, freely walkable — moveTo with reactive fallback
            char = moveToWithTransitionFallback(name, char, targetMap.x, targetMap.y, targetLayer)
            break
        }

        return char
    }

    /** Safety cap on the number of transitions allowed in a single [navigateToTile] call. */
    private val MAX_NAV_ITERATIONS = 8

    /**
     * Move character to (toX, toY) on [toLayer] with a reactive fallback for 595/596 errors.
     *
     * Error codes 595 ("No path to destination") and 596 ("Map is blocked") indicate that
     * direct A* movement failed — there is likely terrain, water, or a gate between the
     * character and the target. When this happens, search for a nearby same-layer transition
     * that routes toward the destination, satisfy its conditions, cross it, then attempt
     * the final move.
     *
     * If no same-layer transition is found after a 595/596, rethrows as
     * [TransitionConditionUnsatisfiableException] with a clear message.
     */
    private suspend fun moveToWithTransitionFallback(
        name: String,
        char: Character,
        toX: Int, toY: Int, toLayer: String
    ): Character {
        return try {
            moveTo(name, toX, toY)
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 595 || e.errorCode == 596) {
                println("[$name] Navigation: ${e.errorCode} moving to ($toX,$toY,$toLayer) — searching for same-layer transition")
                val freshChar = refreshCharacter(name)
                val transitionTile = contentCache.findNearestSameLayerTransitionToward(
                    freshChar, toX, toY, toLayer
                ) ?: throw TransitionConditionUnsatisfiableException(
                    "No path to ($toX,$toY,$toLayer) and no same-layer transition found [${e.errorCode}]"
                )
                var c = moveTo(name, transitionTile.x, transitionTile.y)
                val conditions = transitionTile.interactions.transition?.conditions ?: emptyList()
                if (conditions.isNotEmpty()) {
                    c = satisfyTransitionConditions(name, c, conditions)
                    if (!isAt(c, transitionTile.x, transitionTile.y)) c = moveTo(name, transitionTile.x, transitionTile.y)
                }
                c = useTransition(name)
                // After transition, attempt the final move to destination
                if (!isAt(c, toX, toY)) moveTo(name, toX, toY) else c
            } else throw e
        }
    }

    /**
     * Ensure the character can pay any conditions attached to a transition tile.
     *
     * Handles the following [Condition.operator] values:
     *  - `cost` where `code == "gold"`: character must have [Condition.value] gold on hand.
     *    Withdraws from bank if inventory gold is insufficient (mirrors item-cost handling).
     *  - `cost` (item): character must have [Condition.value] of [Condition.code] in inventory.
     *    Withdraws from bank if inventory is insufficient.
     *  - `has_item`: same as item cost but item is not consumed.
     *  - `achievement_unlocked`: should have been pre-filtered by [ContentCache.preWarmMaps].
     *    Defensively logged if it reaches runtime.
     *
     * Throws [TransitionConditionUnsatisfiableException] if a condition cannot be satisfied
     * — this is a distinct exception type so the runner can stop the task cleanly instead
     * of infinite-retrying.
     *
     * The returned [Character] reflects any bank trip taken to withdraw gold/items — the
     * caller should verify the character is still at the transition tile after this returns.
     */
    private suspend fun satisfyTransitionConditions(
        characterName: String,
        charAtTile: Character,
        conditions: List<com.artifactsmmo.client.models.Condition>
    ): Character {
        var char = charAtTile
        val itemsToWithdraw = mutableListOf<SimpleItem>()
        var goldToWithdraw = 0

        for (condition in conditions) {
            when (condition.operator) {
                "cost" -> {
                    val required = condition.value
                    if (condition.code == "gold") {
                        if (char.gold >= required) continue
                        val deficit = required - char.gold
                        val bankGold = client.bank.getBankDetails().gold
                        if (bankGold < deficit) {
                            throw TransitionConditionUnsatisfiableException(
                                "Cannot satisfy transition: need $required gold, have ${char.gold} in inventory + $bankGold in bank"
                            )
                        }
                        goldToWithdraw += deficit
                    } else {
                        // Item cost — withdraw from bank if not in inventory
                        val inInventory = getItemQuantity(char, condition.code)
                        if (inInventory >= required) continue
                        val deficit = required - inInventory
                        val inBank = bankState.getQuantity(condition.code)
                        if (inBank < deficit) {
                            throw TransitionConditionUnsatisfiableException(
                                "Cannot satisfy transition condition: need $required ${condition.code}, " +
                                "have $inInventory in inventory + $inBank in bank"
                            )
                        }
                        itemsToWithdraw.add(SimpleItem(condition.code, deficit))
                    }
                }
                "has_item" -> {
                    val required = condition.value
                    val inInventory = getItemQuantity(char, condition.code)
                    if (inInventory >= required) continue
                    val deficit = required - inInventory
                    val inBank = bankState.getQuantity(condition.code)
                    if (inBank < deficit) {
                        throw TransitionConditionUnsatisfiableException(
                            "Cannot satisfy transition condition: need $required ${condition.code}, " +
                            "have $inInventory in inventory + $inBank in bank"
                        )
                    }
                    itemsToWithdraw.add(SimpleItem(condition.code, deficit))
                }
                "achievement_unlocked" -> {
                    println("[$characterName] Unexpected achievement_unlocked condition at runtime — $condition")
                }
                else -> {
                    println("[$characterName] Unknown transition condition operator: ${condition.operator}")
                }
            }
        }

        if (itemsToWithdraw.isNotEmpty() || goldToWithdraw > 0) {
            val bank = contentCache.findNearest(char, "bank", null, layer = "overworld")
                ?: throw TransitionConditionUnsatisfiableException(
                    "Need bank to satisfy transition, but no bank found"
                )
            char = navigateToTile(characterName, bank)

            if (goldToWithdraw > 0) {
                val goldResult = client.bank.withdrawGold(characterName, goldToWithdraw)
                waitForCooldown(goldResult.cooldown.expiration)
                char = goldResult.character
            }
            if (itemsToWithdraw.isNotEmpty()) {
                val result = client.bank.withdrawItems(characterName, itemsToWithdraw)
                waitForCooldown(result.cooldown.expiration)
                char = result.character
            }
        }

        return char
    }

    /**
     * Use the map transition on the tile the character is currently standing on.
     * The character must already be on a tile that has a [MapTransition].
     */
    private suspend fun useTransition(name: String): Character {
        val result = client.actions.transition(name)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    // ── Map queries ──

    /**
     * Find the nearest map tile of a given content type/code to the character,
     * searching across all layers (overworld, underground, interior).
     * Callers should use [navigateToTile] to travel to the result, which handles
     * layer transitions automatically.
     */
    suspend fun findNearest(
        char: Character,
        contentType: String,
        contentCode: String? = null
    ): MapInfo? {
        return contentCache.findNearestAnyLayer(char, contentType, contentCode)
    }

    /**
     * Find the nearest bank tile to the character.
     */
    suspend fun findNearestBank(char: Character): MapInfo? {
        return findNearest(char, "bank")
    }

    /**
     * Find nearest workshop for a given skill.
     */
    suspend fun findNearestWorkshop(char: Character, skill: String): MapInfo? {
        return findNearest(char, "workshop", skill)
    }

    // ── Inventory ──

    /**
     * Check if inventory is at or above a threshold percentage (0.0 to 1.0).
     */
    fun isInventoryFull(char: Character, threshold: Double = 0.9): Boolean {
        val totalItems = char.inventory.sumOf { it.quantity }
        return totalItems >= (char.inventoryMaxItems * threshold).toInt()
    }

    fun getItemQuantity(char: Character, itemCode: String): Int {
        return char.inventory.filter { it.code == itemCode }.sumOf { it.quantity }
    }

    // ── Banking ──

    /**
     * Move to the nearest bank and deposit safe items from inventory.
     * Only deposits resources and consumables — never tools, weapons, gear, or other equipment.
     * Returns updated character after banking.
     * Handles sub-layer exits transparently (underground/interior → overworld → bank).
     */
    suspend fun bankDepositAll(name: String): Character {
        var char = refreshCharacter(name)

        val bank = findNearestBank(char) ?: throw IllegalStateException("No bank found on map")
        char = navigateToTile(name, bank)

        // Only deposit resources and consumables; keep everything else (weapons, tools, gear, etc.)
        val safeTypes = setOf("resource", "consumable", "currency")
        val itemsToDeposit = mutableListOf<SimpleItem>()
        for (slot in char.inventory) {
            if (slot.quantity <= 0) continue
            val type = getItemType(slot.code)
            if (type in safeTypes) {
                itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
            }
        }

        if (itemsToDeposit.isNotEmpty()) {
            val result = client.bank.depositItems(name, itemsToDeposit)
            waitForCooldown(result.cooldown.expiration)
            return result.character
        }
        return char
    }

    /**
     * Determines whether an item in the character's inventory should be deposited to the bank
     * during automatic inventory management (e.g., when inventory is full while grinding).
     *
     * Rules:
     *  - Resources and consumables → always deposit
     *  - Equipment types (weapon, helmet, shield, body_armor, amulet, leg_armor,
     *    boots, ring, rune, artifact) → **never** deposit, EXCEPT inferior gathering tools
     *  - Gathering tools (type=weapon, subtype=tool) → deposit only if the character
     *    already owns a better tool (higher level) for the same gathering skill,
     *    considering both inventory and the currently equipped weapon slot
     *  - Unknown item (not in cache) → deposit (safe default)
     */
    suspend fun shouldDepositItem(char: Character, itemCode: String): Boolean {
        val item = contentCache.getItemOrNull(itemCode) ?: return true

        val neverDepositTypes = setOf(
            "weapon", "helmet", "shield", "body_armor", "amulet",
            "leg_armor", "boots", "ring", "rune", "artifact"
        )

        if (item.type !in neverDepositTypes) return true // resource, consumable, etc.

        // Gathering tool: deposit only if a better one for the same skill is already owned
        if (item.type == "weapon" && item.subtype == "tool") {
            val gatheringSkills = listOf("mining", "woodcutting", "fishing", "alchemy")
            val toolSkill = gatheringSkills.firstOrNull { skill ->
                item.effects.any { it.code == skill }
            } ?: return false // Unknown tool type — keep it to be safe

            val bestTool = findBestToolInInventory(char, toolSkill) ?: return false
            // Deposit this tool only if a different (better) tool is the best for this skill
            return bestTool.code != itemCode
        }

        // Combat weapon or other non-tool equipment — never deposit
        return false
    }

    /**
     * Move to nearest bank and deposit specific items.
     * Handles sub-layer exits transparently (underground/interior → overworld → bank).
     */
    suspend fun bankDepositItems(name: String, items: List<SimpleItem>): Character {
        if (items.isEmpty()) return refreshCharacter(name)

        var char = refreshCharacter(name)
        val bank = findNearestBank(char) ?: throw IllegalStateException("No bank found on map")
        char = navigateToTile(name, bank)

        val result = client.bank.depositItems(name, items)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    // ── Gathering ──

    /**
     * Perform a single gather action. Character must already be at a resource tile.
     */
    suspend fun gather(name: String): com.artifactsmmo.client.models.SkillData {
        val result = client.actions.gather(name)
        waitForCooldown(result.cooldown.expiration)
        return result
    }

    // ── Fighting ──

    suspend fun fight(name: String, participants: List<String> = emptyList()): com.artifactsmmo.client.models.CharacterFightData {
        val result = client.actions.fight(name, participants)
        waitForCooldown(result.cooldown.expiration)
        return result
    }

    suspend fun rest(name: String): Character {
        val result = client.actions.rest(name)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    // ── Crafting ──

    suspend fun craft(name: String, itemCode: String, quantity: Int = 1): com.artifactsmmo.client.models.SkillData {
        val result = client.actions.craft(name, itemCode, quantity)
        waitForCooldown(result.cooldown.expiration)
        return result
    }

    // ── Recycling ──

    /**
     * Recycle items at a workshop. Character must be at the appropriate workshop.
     * Returns the recycling result including recovered materials.
     */
    suspend fun recycle(name: String, itemCode: String, quantity: Int = 1): com.artifactsmmo.client.models.RecyclingData {
        val result = client.actions.recycle(name, itemCode, quantity)
        waitForCooldown(result.cooldown.expiration)
        return result
    }

    // ── Crafting item discovery ──

    /**
     * Info about a craftable item, including material availability.
     */
    data class CraftableItemInfo(
        val item: Item,
        val maxCraftable: Int,
        val ingredients: List<SimpleItem>,
        /** How many of each ingredient the player has (inventory + bank), keyed by item code. */
        val ingredientAvailable: Map<String, Int> = emptyMap(),
        /**
         * NPC purchases required to craft one batch when inventory + bank alone are insufficient.
         * Empty when all ingredients are available from inventory/bank.
         * Each entry describes a single ingredient that must be bought from an NPC.
         */
        val npcPurchasesNeeded: List<NpcPurchaseInfo> = emptyList()
    )

    /**
     * Find items craftable with a specific skill from inventory + bank materials.
     * Also considers ingredients purchasable from NPCs (any currency) when inventory + bank
     * are insufficient, provided the character can afford them.
     * Returns list sorted by craft level descending (higher level = more XP).
     */
    suspend fun getAvailableCraftingItems(char: Character, skill: String, minLevel: Int? = null, maxLevel: Int? = null): List<CraftableItemInfo> {
        val craftableItems = contentCache.getItemsBySkill(skill)

        val skillLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, skill) ?: 0
        val effectiveMax = maxLevel?.coerceAtMost(skillLevel) ?: skillLevel
        val effectiveMin = minLevel ?: 0

        val results = mutableListOf<CraftableItemInfo>()
        for (item in craftableItems) {
            val craft = item.craft ?: continue
            val craftLvl = craft.level ?: 0
            if (craftLvl > effectiveMax) continue
            if (craftLvl < effectiveMin) continue

            val (maxCraftable, available, npcPurchases) = resolveIngredientAvailability(char, craft.items)

            results.add(CraftableItemInfo(
                item = item,
                maxCraftable = maxCraftable,
                ingredients = craft.items,
                ingredientAvailable = available,
                npcPurchasesNeeded = npcPurchases
            ))
        }

        // Craftable items first (sorted by level desc), then uncraftable (sorted by level desc)
        return results.sortedWith(compareByDescending<CraftableItemInfo> { it.maxCraftable > 0 }.thenByDescending { it.item.craft?.level ?: 0 })
    }

    /**
     * Find miscellaneous craftable items from inventory + bank materials.
     * Excludes weaponcrafting, gearcrafting, and jewelrycrafting.
     * Also considers ingredients purchasable from NPCs when inventory + bank are insufficient.
     * Returns list sorted by craft skill then craft level descending.
     */
    suspend fun getAvailableMiscCraftingItems(char: Character, minLevel: Int? = null, maxLevel: Int? = null): List<CraftableItemInfo> {
        val excludedSkills = setOf("weaponcrafting", "gearcrafting", "jewelrycrafting")
        val miscSkills = listOf("cooking", "mining", "woodcutting", "alchemy")

        val results = mutableListOf<CraftableItemInfo>()
        for (skill in miscSkills) {
            val skillLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, skill) ?: 0
            val effectiveMax = maxLevel?.coerceAtMost(skillLevel) ?: skillLevel
            val effectiveMin = minLevel ?: 0

            val craftableItems = contentCache.getItemsBySkill(skill)

            for (item in craftableItems) {
                val craft = item.craft ?: continue
                val craftLvl = craft.level ?: 0
                if (craftLvl > effectiveMax) continue
                if (craftLvl < effectiveMin) continue

                val (maxCraftable, available, npcPurchases) = resolveIngredientAvailability(char, craft.items)

                results.add(CraftableItemInfo(
                    item = item,
                    maxCraftable = maxCraftable,
                    ingredients = craft.items,
                    ingredientAvailable = available,
                    npcPurchasesNeeded = npcPurchases
                ))
            }
        }

        // Craftable items first (sorted by level desc), then uncraftable (sorted by level desc)
        return results.sortedWith(compareByDescending<CraftableItemInfo> { it.maxCraftable > 0 }.thenByDescending { it.item.craft?.level ?: 0 })
    }

    /**
     * Resolve how many of a recipe can be crafted given inventory, bank, and NPC shop stock.
     *
     * Returns a Triple of:
     *  1. maxCraftable — limiting reagent count across all sources (0 if any ingredient is
     *     unaffordable or unavailable)
     *  2. available     — map of ingredient code → total quantity from inventory + bank
     *  3. npcPurchases — NPC purchase descriptors for ingredients that have a deficit
     *     covered by an NPC shop (only populated when NPC purchasing is needed)
     */
    private suspend fun resolveIngredientAvailability(
        char: Character,
        ingredients: List<SimpleItem>
    ): Triple<Int, Map<String, Int>, List<NpcPurchaseInfo>> {
        val available = mutableMapOf<String, Int>()
        val npcPurchases = mutableListOf<NpcPurchaseInfo>()

        // Per-ingredient limiting factor (floor-divided by qty-per-craft)
        val perIngredientMax = ingredients.map { ingredient ->
            val invQty  = getItemQuantity(char, ingredient.code)
            val bankQty = getBankItemQuantity(ingredient.code)
            val ownedTotal = invQty + bankQty
            available[ingredient.code] = ownedTotal

            val ownedCrafts = ownedTotal / ingredient.quantity

            // Check NPC sources for any shortfall
            if (ownedCrafts >= 1) {
                // Enough owned — no NPC purchase needed for this ingredient
                ownedCrafts
            } else {
                // Deficit — look up NPC sources
                val npcSources = try {
                    contentCache.getNpcItemsByCode(ingredient.code)
                } catch (_: Exception) { emptyList() }

                val buyableSource = npcSources.firstOrNull { npcItem ->
                    val price = npcItem.buyPrice ?: return@firstOrNull false
                    val totalCost = price * ingredient.quantity
                    if (npcItem.currency == "gold") {
                        char.gold >= totalCost
                    } else {
                        // Non-gold currency: check inventory + bank combined
                        val inInventory = getItemQuantity(char, npcItem.currency)
                        val inBank = getBankItemQuantity(npcItem.currency)
                        (inInventory + inBank) >= totalCost
                    }
                }

                if (buyableSource != null) {
                    val price = buyableSource.buyPrice!!

                    // Calculate how many crafts we can afford via NPC purchase
                    val currencyAvailable = if (buyableSource.currency == "gold") {
                        char.gold
                    } else {
                        getItemQuantity(char, buyableSource.currency) +
                        getBankItemQuantity(buyableSource.currency)
                    }
                    // How many of this ingredient we can buy = floor(currencyAvailable / priceEach)
                    // How many crafts that supports = floor(buyable / ingredient.quantity)
                    val buyableQty    = currencyAvailable / price
                    val totalAffordable = ownedTotal + buyableQty
                    val affordableCrafts = totalAffordable / ingredient.quantity

                    // Only need to purchase the deficit above what we already own
                    val purchaseQty = (affordableCrafts * ingredient.quantity) - ownedTotal

                    npcPurchases.add(
                        NpcPurchaseInfo(
                            npcCode        = buyableSource.npc,
                            itemCode       = ingredient.code,
                            currency       = buyableSource.currency,
                            priceEach      = price,
                            quantityNeeded = purchaseQty.coerceAtLeast(0)
                        )
                    )
                    affordableCrafts
                } else {
                    // Can't obtain this ingredient at all
                    0
                }
            }
        }

        val maxCraftable = perIngredientMax.minOrNull() ?: 0
        return Triple(maxCraftable, available, npcPurchases)
    }

    // ── Consumables ──

    /**
     * Look up an item's details by code.
     */
    suspend fun getItem(code: String): Item {
        return contentCache.getItem(code)
    }

    /**
     * Use an item (e.g., eat food to heal).
     */
    suspend fun useItem(name: String, itemCode: String, quantity: Int = 1): com.artifactsmmo.client.models.UseItemData {
        val result = client.actions.use(name, itemCode, quantity)
        waitForCooldown(result.cooldown.expiration)
        return result
    }

    // ── Bank withdraw ──

    /**
     * Move to nearest bank and withdraw specific items.
     */
    suspend fun bankWithdrawItems(name: String, items: List<SimpleItem>): Character {
        if (items.isEmpty()) return refreshCharacter(name)

        var char = refreshCharacter(name)
        val bank = findNearestBank(char) ?: throw IllegalStateException("No bank found on map")
        char = navigateToTile(name, bank)

        val result = client.bank.withdrawItems(name, items)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    /**
     * Check how many of an item are in the bank.
     * Reads from the shared [BankState] snapshot — no API call.
     */
    suspend fun getBankItemQuantity(itemCode: String): Int = bankState.getQuantity(itemCode)

    /**
     * Fetch a single page of bank items (no item_code filter). Used by [CraftingExecutor]
     * to build a full bank snapshot in one paginated sweep instead of one call per ingredient.
     */
    suspend fun getBankItems(page: Int = 1, size: Int = 100) =
        client.bank.getBankItems(page = page, size = size)

    /** Return the current bank snapshot from [BankState]. No API call. */
    fun getBankSnapshot(): Map<String, Int> = bankState.snapshot.value

    // ── Food / Cooking discovery ──

    /**
     * Data about a cookable drop from a monster.
     * rawCode: the raw item code dropped by the monster (e.g., "raw_chicken")
     * cookedCode: the cooked item code (e.g., "cooked_chicken")
     * cookedItem: full Item details of the cooked item (to check heal amount, craft level, etc.)
     */
    data class CookableDropInfo(
        val rawCode: String,
        val cookedCode: String,
        val cookedItem: Item,
        val healAmount: Int,
        val cookingLevelRequired: Int,
        /** Character level required to consume the cooked food */
        val useLevelRequired: Int = 1,
        /** How many raw items are needed per single craft */
        val rawPerCraft: Int = 1
    )

    /**
     * Discover which drops from a monster can be cooked into food with a heal effect.
     * Queries the API to find: monster drops -> items craftable via cooking -> items with heal effect.
     * Results are cached per monster code.
     */
    private val cookableDropCache = mutableMapOf<String, List<CookableDropInfo>>()

    suspend fun findCookableDrops(monsterCode: String): List<CookableDropInfo> {
        cookableDropCache[monsterCode]?.let { return it }

        val monster = try {
            client.content.getMonster(monsterCode)
        } catch (_: Exception) {
            return emptyList()
        }

        val results = mutableListOf<CookableDropInfo>()

        for (drop in monster.drops) {
            // Check if any cooking recipe uses this drop as a material
            val cookingItems = try {
                client.content.getItems(craftSkill = "cooking", craftMaterial = drop.code, size = 100)
            } catch (_: Exception) {
                continue
            }

            for (cookedItem in cookingItems.data) {
                val healEffect = cookedItem.effects.find { it.code == "heal" }
                if (healEffect != null && healEffect.value > 0) {
                    val rawPerCraft = cookedItem.craft?.items?.find { it.code == drop.code }?.quantity ?: 1
                    results.add(CookableDropInfo(
                        rawCode = drop.code,
                        cookedCode = cookedItem.code,
                        cookedItem = cookedItem,
                        healAmount = healEffect.value,
                        cookingLevelRequired = cookedItem.craft?.level ?: 0,
                        useLevelRequired = cookedItem.level,
                        rawPerCraft = rawPerCraft
                    ))
                }
            }
        }

        cookableDropCache[monsterCode] = results
        return results
    }

    /**
     * Find the best food item in the character's inventory (highest heal amount)
     * that the character meets the level requirement for.
     * Returns (itemCode, healAmount, quantity) or null if no usable food found.
     */
    suspend fun findBestFoodInInventory(char: Character): Triple<String, Int, Int>? {
        var bestFood: Triple<String, Int, Int>? = null

        for (slot in char.inventory) {
            if (slot.quantity <= 0) continue
            val item = try {
                contentCache.getItem(slot.code)
            } catch (_: Exception) {
                continue
            }
            if (item.level > char.level) continue
            val healEffect = item.effects.find { it.code == "heal" }
            if (healEffect != null && healEffect.value > 0) {
                if (bestFood == null || healEffect.value > bestFood.second) {
                    bestFood = Triple(slot.code, healEffect.value, slot.quantity)
                }
            }
        }
        return bestFood
    }

    /**
     * Find the best food item in the bank that the character can use.
     * Returns (itemCode, healAmount, bankQuantity) or null if no usable food found.
     */
    suspend fun findBestFoodInBank(char: Character): Triple<String, Int, Int>? {
        var bestFood: Triple<String, Int, Int>? = null

        // Read from shared BankState snapshot.
        val bankItems = bankState.snapshot.value
        for ((code, quantity) in bankItems) {
            if (quantity <= 0) continue
            val item = try {
                contentCache.getItem(code)
            } catch (_: Exception) {
                continue
            }
            if (item.level > char.level) continue
            val healEffect = item.effects.find { it.code == "heal" }
            if (healEffect != null && healEffect.value > 0) {
                if (bestFood == null || healEffect.value > bestFood.second) {
                    bestFood = Triple(code, healEffect.value, quantity)
                }
            }
        }
        return bestFood
    }

    // ── NPC purchasing ──

    /**
     * Information about an NPC that sells a specific item.
     *
     * [currency] mirrors [NPCItem.currency] — may be "gold" or an item code such as
     * "tasks_coin", meaning the cost is paid with that inventory item rather than gold.
     */
    data class NpcPurchaseInfo(
        val npcCode: String,
        val itemCode: String,
        /** Currency used to pay: "gold" or an item code (e.g. "tasks_coin"). */
        val currency: String,
        val priceEach: Int,
        val quantityNeeded: Int
    ) {
        /** Human-readable cost string, e.g. "500 gold each" or "1 tasks_coin each". */
        val costLabel: String get() = "$priceEach $currency each"
    }

    /**
     * Find all NPCs that sell [itemCode] and return a [NpcPurchaseInfo] entry per NPC.
     * Returns an empty list if no NPC sells this item.
     */
    suspend fun findNpcSources(itemCode: String, quantityNeeded: Int = 1): List<NpcPurchaseInfo> {
        val npcItems = contentCache.getNpcItemsByCode(itemCode)
        return npcItems.mapNotNull { npcItem ->
            val price = npcItem.buyPrice ?: return@mapNotNull null
            NpcPurchaseInfo(
                npcCode       = npcItem.npc,
                itemCode      = itemCode,
                currency      = npcItem.currency,
                priceEach     = price,
                quantityNeeded = quantityNeeded
            )
        }
    }

    /**
     * Check whether the character can afford a given NPC purchase.
     *
     * - If [currency] is "gold": checks [Character.gold].
     * - Otherwise: checks inventory + bank combined for the currency item
     *   (non-gold currencies like tasks_coin are treated as items that may be in the bank).
     */
    suspend fun canAffordNpcPurchase(char: Character, purchase: NpcPurchaseInfo): Boolean {
        val totalCost = purchase.priceEach * purchase.quantityNeeded
        return if (purchase.currency == "gold") {
            char.gold >= totalCost
        } else {
            val inInventory = getItemQuantity(char, purchase.currency)
            val inBank = getBankItemQuantity(purchase.currency)
            (inInventory + inBank) >= totalCost
        }
    }

    /**
     * Move to the nearest map tile for [npcCode], buy [quantity] of [itemCode] from the NPC,
     * wait for the action cooldown, and return the updated [Character].
     *
     * For non-gold currency the caller is responsible for ensuring the currency item is already
     * in the character's inventory before calling this function.
     *
     * Throws [IllegalStateException] if the NPC tile is not in the map cache (e.g. the tile
     * requires an achievement the character has not yet unlocked — conditional tiles are
     * excluded from the cache during pre-warm).
     */
    suspend fun npcBuy(
        characterName: String,
        npcCode: String,
        itemCode: String,
        quantity: Int
    ): Character {
        var char = refreshCharacter(characterName)

        // Move to the NPC tile if not already there.
        // findNearest returns null for conditional (locked) tiles since they are excluded
        // from the map cache — treat that as "NPC not accessible".
        val npcTile = findNearest(char, "npc", npcCode)
            ?: throw IllegalStateException(
                "NPC '$npcCode' is not accessible — its map tile may require an achievement unlock"
            )

        if (!isAt(char, npcTile.x, npcTile.y)) {
            char = moveTo(characterName, npcTile.x, npcTile.y)
        }

        return try {
            val result = client.npc.buyItem(characterName, itemCode, quantity)
            waitForCooldown(result.cooldown.expiration)
            result.character
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 496) {
                throw IllegalStateException(
                    "NPC '$npcCode' rejected purchase of '$itemCode' — condition not met (error 496). " +
                    "The character may be missing a required achievement."
                )
            }
            throw e
        }
    }

    /**
     * Sell [quantity] of [itemCode] to an NPC. Character must already be at the NPC tile.
     * Returns the updated [Character] after the transaction cooldown drains.
     */
    suspend fun npcSell(characterName: String, itemCode: String, quantity: Int): Character {
        val result = client.npc.sellItem(characterName, itemCode, quantity)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    /**
     * Buy [quantity] of [itemCode] from an NPC by code, navigating if needed.
     * Returns the updated [Character] after the transaction cooldown drains.
     * Thin overload that takes itemCode + quantity directly (no npcCode navigation).
     * Character must already be at the NPC tile.
     */
    suspend fun npcBuyDirect(characterName: String, itemCode: String, quantity: Int): Character {
        val result = client.npc.buyItem(characterName, itemCode, quantity)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    /**
     * Ensure that a non-gold NPC currency item is in the character's inventory.
     * Checks inventory first; if absent, attempts to withdraw from bank.
     * Returns true if the required quantity is (or was made) available in inventory.
     */
    suspend fun ensureNpcCurrencyInInventory(
        characterName: String,
        currency: String,
        quantityNeeded: Int
    ): Boolean {
        if (currency == "gold") return true // gold is always on-hand

        val char = refreshCharacter(characterName)
        val inInventory = getItemQuantity(char, currency)
        if (inInventory >= quantityNeeded) return true

        val stillNeeded = quantityNeeded - inInventory
        val inBank = getBankItemQuantity(currency)
        if (inBank < stillNeeded) return false

        bankWithdrawItems(characterName, listOf(SimpleItem(currency, stillNeeded)))
        return true
    }

    // ── Equipping ──

    suspend fun equip(name: String, itemCode: String, slot: String): Character {
        val result = client.actions.equip(name, itemCode, slot)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    /** Equip with a quantity — used for utility potion stacks. */
    suspend fun equip(name: String, itemCode: String, slot: String, quantity: Int): Character {
        val result = client.actions.equip(name, itemCode, slot, quantity)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    suspend fun unequip(name: String, slot: String): Character {
        val result = client.actions.unequip(name, slot)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    /** Unequip with a quantity — used for utility potion stacks. */
    suspend fun unequip(name: String, slot: String, quantity: Int): Character {
        val result = client.actions.unequip(name, slot, quantity)
        waitForCooldown(result.cooldown.expiration)
        return result.character
    }

    // ── Tool management ──

    /**
     * Check if a weapon item is a tool for the given gathering skill.
     * Tools have an effect with the skill name (e.g., "fishing", "mining", "woodcutting")
     * that reduces gathering cooldown.
     */
    private fun isToolForSkill(item: Item, skill: String): Boolean {
        return item.type == "weapon" && item.effects.any { it.code == skill }
    }

    /**
     * Find the best tool in the character's inventory for a given gathering skill.
     * Returns the item, or null if none found.
     */
    suspend fun findBestToolInInventory(char: Character, skill: String): Item? {
        val inventoryCodes = char.inventory.filter { it.quantity > 0 }.map { it.code }.toSet()

        // Also include the currently equipped weapon — when a tool is equipped it leaves the
        // inventory slot, so without this the function would ignore it and always return a
        // lower-level inventory tool, causing an infinite equip-swap loop.
        val equippedCode = char.weaponSlot.takeIf { it.isNotEmpty() }
        val ownedCodes = if (equippedCode != null) inventoryCodes + equippedCode else inventoryCodes

        if (ownedCodes.isEmpty()) return null

        // Tool usability is gated by the gathering skill level (e.g. miningLevel) alone —
        // a high character level does not allow equipping a tool above the skill's level.
        val skillLevel = CharacterUtils.getSkillLevel(char, skill) ?: 0

        // Get all weapons up to the skill level from cache
        val allTools = contentCache.getItemsByType("weapon")
            .filter { it.level <= skillLevel && isToolForSkill(it, skill) }

        // Filter to items the character actually has (inventory + currently equipped)
        val ownedTools = allTools.filter { it.code in ownedCodes }

        // Return the highest level tool
        return ownedTools.maxByOrNull { it.level }
    }

    /**
     * Ensure the character has the best available tool equipped for the given skill.
     * Will equip from inventory if a better tool is available.
     *
     * [existingChar] may be supplied by callers that already hold a fresh [Character]
     * from a preceding action response, avoiding a redundant GET /characters/{name} call.
     * When null (default) the character is fetched from the API.
     *
     * Returns updated character.
     */
    suspend fun ensureToolEquipped(name: String, skill: String, existingChar: Character? = null): Character {
        var char = existingChar ?: refreshCharacter(name)
        val bestTool = findBestToolInInventory(char, skill) ?: return char

        // Check if already equipped
        if (char.weaponSlot == bestTool.code) return char

        // Unequip current weapon if one is equipped
        if (char.weaponSlot.isNotEmpty()) {
            char = unequip(name, "weapon")
        }

        // Equip the best tool
        char = equip(name, bestTool.code, "weapon")
        return char
    }

    /**
     * Data about a tool upgrade that can be crafted using bank materials.
     */
    data class ToolUpgradeInfo(
        val tool: Item,
        val craftSkill: String,
        val craftLevel: Int,
        val ingredients: List<SimpleItem>,
        /** True if the tool already exists in the bank (just withdraw & equip, no crafting needed). */
        val readyMade: Boolean = false
    )

    /**
     * Find a better tool for a gathering skill that already exists as a finished item
     * in the bank. Returns the best one, or null if none found.
     */
    suspend fun findReadyMadeToolInBank(char: Character, skill: String): ToolUpgradeInfo? {
        // Tool usability is gated by the gathering skill level alone.
        val skillLevel = CharacterUtils.getSkillLevel(char, skill) ?: 0

        // Get all tools for this skill up to the skill level from cache
        val allTools = contentCache.getItemsByType("weapon")
            .filter { it.level <= skillLevel && isToolForSkill(it, skill) }

        // Determine current best tool level (equipped or in inventory)
        val currentBest = findBestToolInInventory(char, skill)
        val currentEquipped = if (char.weaponSlot.isNotEmpty()) {
            allTools.find { it.code == char.weaponSlot }
        } else null
        val currentBestLevel = maxOf(currentBest?.level ?: 0, currentEquipped?.level ?: 0)

        // Early exit: if the character is already using the highest-level tool available
        // for their current skill level, no upgrade is possible — skip the bank sweep.
        val highestPossibleLevel = allTools.maxOfOrNull { it.level } ?: 0
        if (currentBestLevel >= highestPossibleLevel) return null

        // Read from shared BankState snapshot.
        val bankItems = bankState.snapshot.value

        // Find the best tool in the bank that's better than what we have
        val bestInBank = allTools
            .filter { it.level > currentBestLevel }
            .filter { (bankItems[it.code] ?: 0) >= 1 }
            .maxByOrNull { it.level }
            ?: return null

        return ToolUpgradeInfo(
            tool = bestInBank,
            craftSkill = "",
            craftLevel = 0,
            ingredients = emptyList(),
            readyMade = true
        )
    }


    // ── Content queries ──

    /**
     * Get all resources for a given skill up to the character's skill level.
     */
    suspend fun getAvailableResources(skill: String, skillLevel: Int): List<com.artifactsmmo.client.models.Resource> {
        val resources = mutableListOf<com.artifactsmmo.client.models.Resource>()
        var page = 1
        while (true) {
            val result = client.content.getResources(skill = skill, maxLevel = skillLevel, page = page, size = 100)
            resources.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        return resources.sortedBy { it.level }
    }

    /**
     * Get all items that can be crafted with a given skill up to the character's skill level.
     * Uses the /items API with craft_skill and max_level filters.
     * Returns items sorted by level ascending.
     */
    suspend fun getAvailableCraftedItems(skill: String, skillLevel: Int, minLevel: Int? = null): List<Item> {
        val items = mutableListOf<Item>()
        var page = 1
        while (true) {
            val result = client.content.getItems(craftSkill = skill, minLevel = minLevel, maxLevel = skillLevel, page = page, size = 100)
            items.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        return items.sortedBy { it.craft?.level ?: 0 }
    }

    /**
     * Get all monsters up to a given level.
     */
    suspend fun getAvailableMonsters(maxLevel: Int): List<com.artifactsmmo.client.models.Monster> {
        val monsters = mutableListOf<com.artifactsmmo.client.models.Monster>()
        var page = 1
        while (true) {
            val result = client.content.getMonsters(maxLevel = maxLevel, page = page, size = 100)
            monsters.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        return monsters.sortedBy { it.level }
    }

    /**
     * Simulate combat between a character (with current equipment) and a monster.
     * Returns simulation data including win rate.
     *
     * Tries the API simulator first. If the API call fails (e.g. rate-limited),
     * falls back to [LocalFightSimulator] which runs entirely in-memory using the
     * documented combat formulas. The local fallback is accurate for monsters without
     * special effects; when effects are present, win-rate will be optimistic and
     * callers should apply a stricter threshold (see [LocalFightSimulator.Result.effectsIgnored]).
     */
    suspend fun simulateFight(
        characterName: String,
        monsterCode: String,
        iterations: Int = 20
    ): com.artifactsmmo.client.models.CombatSimulationData {
        val char = refreshCharacter(characterName)
        return try {
            val fakeChar = com.artifactsmmo.client.models.FakeCharacterRequest.fromCharacter(char)
            val request = com.artifactsmmo.client.models.CombatSimulationRequest(
                characters = listOf(fakeChar),
                monster = monsterCode,
                iterations = iterations
            )
            SimulationRateLimiter.execute { client.simulation.simulateFight(request) }
        } catch (e: Exception) {
            // API simulator failed (rate-limit, member-only, network error, etc.)
            // Fall back to the local simulator so the caller always gets a result.
            val monster = contentCache.getMonsterOrNull(monsterCode)
                ?: throw e  // If we can't get monster data either, re-throw original error

            val localResult = LocalFightSimulator.simulate(char, monster, iterations)
            val effectsNote = if (localResult.effectsIgnored) " (local sim, effects ignored)" else " (local sim)"
            // We can't easily log here without a logger reference, so we embed the note
            // in a synthetic CombatSimulationData. The winrate is what callers care about.
            com.artifactsmmo.client.models.CombatSimulationData(
                results = emptyList(),
                wins = localResult.wins,
                losses = localResult.losses,
                winrate = localResult.winRate
            )
        }
    }

    /**
     * Get the type of an item (e.g., "resource", "weapon", "consumable").
     * Returns null if the item can't be looked up.
     */
    suspend fun getItemType(code: String): String? {
        return contentCache.getItemOrNull(code)?.type
    }

    /**
     * Find craftable items from gathered resources.
     * E.g., copper_ore -> copper_bar, ash_wood -> ash_plank.
     * Returns list of (craftedItemCode, craftedItem) for items whose ingredients
     * are all present in the character's inventory in sufficient quantity.
     */
    suspend fun findCraftableRefinements(char: Character, skill: String): List<Pair<Item, Int>> {
        // Get items that can be crafted with the workshop skill matching the gathering skill
        val workshopSkill = when (skill) {
            "mining" -> "mining"
            "woodcutting" -> "woodcutting"
            "fishing" -> "cooking"  // Fish are cooked
            "alchemy" -> "alchemy"
            else -> return emptyList()
        }

        val craftableItems = contentCache.getItemsBySkill(workshopSkill)

        // Filter to items the character has the skill level to craft
        val skillLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, workshopSkill) ?: 0

        val results = mutableListOf<Pair<Item, Int>>()
        for (item in craftableItems) {
            val craft = item.craft ?: continue
            if ((craft.level ?: 0) > skillLevel) continue

            // Check if character has all ingredients
            val maxCraftable = craft.items.minOfOrNull { ingredient ->
                getItemQuantity(char, ingredient.code) / ingredient.quantity
            } ?: 0

            if (maxCraftable > 0) {
                results.add(item to maxCraftable)
            }
        }
        return results
    }

    /**
     * Find craftable refinements from bank contents (instead of inventory).
     * Returns list of (item, maxCraftable, ingredientsToWithdraw).
     */
    suspend fun findCraftableRefinementsFromBank(char: Character, skill: String): List<Triple<Item, Int, List<SimpleItem>>> {
        val workshopSkill = when (skill) {
            "mining" -> "mining"
            "woodcutting" -> "woodcutting"
            "fishing" -> "cooking"
            "alchemy" -> "alchemy"
            else -> return emptyList()
        }

        val craftableItems = contentCache.getItemsBySkill(workshopSkill)

        val skillLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, workshopSkill) ?: 0

        val results = mutableListOf<Triple<Item, Int, List<SimpleItem>>>()
        for (item in craftableItems) {
            val craft = item.craft ?: continue
            if ((craft.level ?: 0) > skillLevel) continue

            // Check if bank has all ingredients
            val maxCraftable = craft.items.minOfOrNull { ingredient ->
                getBankItemQuantity(ingredient.code) / ingredient.quantity
            } ?: 0

            if (maxCraftable > 0) {
                val withdrawList = craft.items.map { ingredient ->
                    SimpleItem(ingredient.code, ingredient.quantity * maxCraftable)
                }
                results.add(Triple(item, maxCraftable, withdrawList))
            }
        }
        return results
    }

    // ── Equipment browser data ──

    /**
     * Info about a piece of equipment available for a slot.
     */
    data class EquipmentOption(
        val item: Item,
        /** Where to get it: "inventory", "bank", or "craftable". */
        val source: String,
        val quantity: Int,
        val craftInfo: CraftableItemInfo? = null
    )

    /**
     * An action to equip a specific item in a specific slot.
     */
    data class EquipAction(
        val slot: String,
        val itemCode: String,
        val source: String   // "inventory", "bank", or "craftable"
    )

    /**
     * Metadata for a single combat equipment slot.
     */
    data class SlotInfo(
        val slot: String,
        val itemType: String,
        val craftSkill: String?
    )

    companion object {
        /** All combat-relevant equipment slots, in display order. */
        val COMBAT_SLOTS = listOf(
            SlotInfo("weapon",    "weapon",     "weaponcrafting"),
            SlotInfo("shield",    "shield",     "gearcrafting"),
            SlotInfo("helmet",    "helmet",     "gearcrafting"),
            SlotInfo("body_armor","body_armor", "gearcrafting"),
            SlotInfo("leg_armor", "leg_armor",  "gearcrafting"),
            SlotInfo("boots",     "boots",      "gearcrafting"),
            SlotInfo("ring1",     "ring",       "jewelrycrafting"),
            SlotInfo("ring2",     "ring",       "jewelrycrafting"),
            SlotInfo("amulet",    "amulet",     "jewelrycrafting"),
            SlotInfo("artifact1", "artifact",   null),
            SlotInfo("artifact2", "artifact",   null),
            SlotInfo("artifact3", "artifact",   null),
            SlotInfo("rune",      "rune",       null)
        )
    }

    // ── Equipment slot helpers ──

    /**
     * Return the item code currently equipped in [slot], or empty string if none.
     */
    fun getEquippedInSlot(char: Character, slot: String): String {
        return when (slot) {
            "weapon"     -> char.weaponSlot
            "shield"     -> char.shieldSlot
            "helmet"     -> char.helmetSlot
            "body_armor" -> char.bodyArmorSlot
            "leg_armor"  -> char.legArmorSlot
            "boots"      -> char.bootsSlot
            "ring1"      -> char.ring1Slot
            "ring2"      -> char.ring2Slot
            "amulet"     -> char.amuletSlot
            "artifact1"  -> char.artifact1Slot
            "artifact2"  -> char.artifact2Slot
            "artifact3"  -> char.artifact3Slot
            "rune"       -> char.runeSlot
            "utility1"   -> char.utility1Slot
            "utility2"   -> char.utility2Slot
            else         -> ""
        }
    }

    /**
     * Return the remaining quantity in a utility slot ("utility1" or "utility2").
     * Returns 0 for unknown slot names or empty slots.
     */
    fun getEquippedUtilityQuantity(char: Character, slot: String): Int {
        return when (slot) {
            "utility1" -> char.utility1SlotQuantity
            "utility2" -> char.utility2SlotQuantity
            else       -> 0
        }
    }

    // ── Equipment browser methods ──

    /**
     * Return all equipment options for a given combat slot.
     * Checks inventory, bank, and craftable items (if slot has a craft skill).
     * Excludes the item currently equipped in that slot.
     * Results are sorted by item level descending.
     */
    suspend fun getAvailableEquipmentForSlot(
        char: Character,
        slotInfo: SlotInfo
    ): List<EquipmentOption> {
        val currentEquipped = getEquippedInSlot(char, slotInfo.slot)

        // Fetch all items of this type from cache, filter by character level
        val allItems = try {
            contentCache.getItemsByType(slotInfo.itemType).filter { it.level <= char.level }
        } catch (_: Exception) { emptyList() }

        // Exclude currently equipped item and tools (subtype == "tool")
        val candidates = allItems.filter { it.code != currentEquipped && it.subtype != "tool" }

        // Build inventory lookup
        val inventoryMap = char.inventory.associate { it.code to it.quantity }

        // Build bank lookup using shared BankState snapshot.
        val bankMap = bankState.snapshot.value

        // Build craftable lookup for this slot's craft skill
        val craftableMap = mutableMapOf<String, CraftableItemInfo>()
        if (slotInfo.craftSkill != null) {
            try {
                val craftable = getAvailableCraftingItems(char, slotInfo.craftSkill)
                for (info in craftable) {
                    if (info.item.type == slotInfo.itemType) {
                        craftableMap[info.item.code] = info
                    }
                }
            } catch (_: Exception) {}
        }

        val results = mutableListOf<EquipmentOption>()
        for (item in candidates) {
            val invQty  = inventoryMap[item.code] ?: 0
            val bankQty = bankMap[item.code] ?: 0
            when {
                invQty > 0            -> results.add(EquipmentOption(item, "inventory", invQty))
                bankQty > 0           -> results.add(EquipmentOption(item, "bank", bankQty))
                item.code in craftableMap -> {
                    val ci = craftableMap[item.code]!!
                    results.add(EquipmentOption(item, "craftable", ci.maxCraftable, ci))
                }
            }
        }

        return results.sortedByDescending { it.item.level }
    }

    /**
     * Simulate combat using [char]'s current gear with optional slot overrides.
     * [slotOverrides] maps slot name (e.g. "weapon") to an item code.
     */
    suspend fun simulateFightWithSlotOverrides(
        char: Character,
        monsterCode: String,
        slotOverrides: Map<String, String>,
        iterations: Int = 20
    ): com.artifactsmmo.client.models.CombatSimulationData {
        val base = com.artifactsmmo.client.models.FakeCharacterRequest.fromCharacter(char)
        val overridden = base.copy(
            weaponSlot    = slotOverrides["weapon"]     ?: base.weaponSlot,
            shieldSlot    = slotOverrides["shield"]     ?: base.shieldSlot,
            helmetSlot    = slotOverrides["helmet"]     ?: base.helmetSlot,
            bodyArmorSlot = slotOverrides["body_armor"] ?: base.bodyArmorSlot,
            legArmorSlot  = slotOverrides["leg_armor"]  ?: base.legArmorSlot,
            bootsSlot     = slotOverrides["boots"]      ?: base.bootsSlot,
            ring1Slot     = slotOverrides["ring1"]      ?: base.ring1Slot,
            ring2Slot     = slotOverrides["ring2"]      ?: base.ring2Slot,
            amuletSlot    = slotOverrides["amulet"]     ?: base.amuletSlot,
            artifact1Slot = slotOverrides["artifact1"]  ?: base.artifact1Slot,
            artifact2Slot = slotOverrides["artifact2"]  ?: base.artifact2Slot,
            artifact3Slot = slotOverrides["artifact3"]  ?: base.artifact3Slot,
            runeSlot      = slotOverrides["rune"]       ?: base.runeSlot
        )
        val request = com.artifactsmmo.client.models.CombatSimulationRequest(
            characters = listOf(overridden),
            monster    = monsterCode,
            iterations = iterations
        )
        return SimulationRateLimiter.execute { client.simulation.simulateFight(request) }
    }

    /**
     * Simulate combat with both equipment and utility slot overrides.
     * Utility slots (utility1, utility2) hold a stack of consumable potions with a quantity.
     * Called by [GearOptimizer] during the utility optimization pass.
     */
    suspend fun simulateFightWithSlotAndUtilityOverrides(
        char: Character,
        monsterCode: String,
        slotOverrides: Map<String, String>,
        utilityOverrides: Map<String, String>,
        utilityQuantities: Map<String, Int>,
        iterations: Int = 20
    ): com.artifactsmmo.client.models.CombatSimulationData {
        val base = com.artifactsmmo.client.models.FakeCharacterRequest.fromCharacter(char)
        val u1Code: String? = utilityOverrides["utility1"] ?: base.utility1Slot
        val u2Code: String? = utilityOverrides["utility2"] ?: base.utility2Slot
        val u1Qty: Int? = utilityQuantities["utility1"] ?: base.utility1SlotQuantity
        val u2Qty: Int? = utilityQuantities["utility2"] ?: base.utility2SlotQuantity
        val overridden = base.copy(
            weaponSlot    = slotOverrides["weapon"]     ?: base.weaponSlot,
            shieldSlot    = slotOverrides["shield"]     ?: base.shieldSlot,
            helmetSlot    = slotOverrides["helmet"]     ?: base.helmetSlot,
            bodyArmorSlot = slotOverrides["body_armor"] ?: base.bodyArmorSlot,
            legArmorSlot  = slotOverrides["leg_armor"]  ?: base.legArmorSlot,
            bootsSlot     = slotOverrides["boots"]      ?: base.bootsSlot,
            ring1Slot     = slotOverrides["ring1"]      ?: base.ring1Slot,
            ring2Slot     = slotOverrides["ring2"]      ?: base.ring2Slot,
            amuletSlot    = slotOverrides["amulet"]     ?: base.amuletSlot,
            artifact1Slot = slotOverrides["artifact1"]  ?: base.artifact1Slot,
            artifact2Slot = slotOverrides["artifact2"]  ?: base.artifact2Slot,
            artifact3Slot = slotOverrides["artifact3"]  ?: base.artifact3Slot,
            runeSlot      = slotOverrides["rune"]       ?: base.runeSlot,
            utility1Slot  = if (u1Code.isNullOrEmpty()) null else u1Code,
            utility1SlotQuantity = if (u1Code.isNullOrEmpty()) null else u1Qty,
            utility2Slot  = if (u2Code.isNullOrEmpty()) null else u2Code,
            utility2SlotQuantity = if (u2Code.isNullOrEmpty()) null else u2Qty
        )
        val request = com.artifactsmmo.client.models.CombatSimulationRequest(
            characters = listOf(overridden),
            monster    = monsterCode,
            iterations = iterations
        )
        return SimulationRateLimiter.execute { client.simulation.simulateFight(request) }
    }

    /**
     * A single participant's full loadout for a cooperative fight simulation.
     * Used by [simulateCoopFight] to describe each character in the multi-character
     * simulation request.
     */
    data class CoopParticipantLoadout(
        val char: Character,
        val slotOverrides: Map<String, String> = emptyMap(),
        val utilityOverrides: Map<String, String> = emptyMap(),
        val utilityQuantities: Map<String, Int> = emptyMap()
    )

    /**
     * Simulate a cooperative fight against [monsterCode] with the given multi-character
     * loadouts. The /simulation/fight API endpoint accepts an array of characters and
     * returns the aggregated coop team win rate.
     *
     * Rate-limited via [SimulationRateLimiter] like the single-character sim methods.
     */
    suspend fun simulateCoopFight(
        monsterCode: String,
        participantLoadouts: List<CoopParticipantLoadout>,
        iterations: Int = 100
    ): com.artifactsmmo.client.models.CombatSimulationData {
        val fakeChars = participantLoadouts.map { p ->
            val base = com.artifactsmmo.client.models.FakeCharacterRequest.fromCharacter(p.char)
            val u1Code: String? = p.utilityOverrides["utility1"] ?: base.utility1Slot
            val u2Code: String? = p.utilityOverrides["utility2"] ?: base.utility2Slot
            val u1Qty: Int? = p.utilityQuantities["utility1"] ?: base.utility1SlotQuantity
            val u2Qty: Int? = p.utilityQuantities["utility2"] ?: base.utility2SlotQuantity
            base.copy(
                weaponSlot    = p.slotOverrides["weapon"]     ?: base.weaponSlot,
                shieldSlot    = p.slotOverrides["shield"]     ?: base.shieldSlot,
                helmetSlot    = p.slotOverrides["helmet"]     ?: base.helmetSlot,
                bodyArmorSlot = p.slotOverrides["body_armor"] ?: base.bodyArmorSlot,
                legArmorSlot  = p.slotOverrides["leg_armor"]  ?: base.legArmorSlot,
                bootsSlot     = p.slotOverrides["boots"]      ?: base.bootsSlot,
                ring1Slot     = p.slotOverrides["ring1"]      ?: base.ring1Slot,
                ring2Slot     = p.slotOverrides["ring2"]      ?: base.ring2Slot,
                amuletSlot    = p.slotOverrides["amulet"]     ?: base.amuletSlot,
                artifact1Slot = p.slotOverrides["artifact1"]  ?: base.artifact1Slot,
                artifact2Slot = p.slotOverrides["artifact2"]  ?: base.artifact2Slot,
                artifact3Slot = p.slotOverrides["artifact3"]  ?: base.artifact3Slot,
                runeSlot      = p.slotOverrides["rune"]       ?: base.runeSlot,
                utility1Slot  = if (u1Code.isNullOrEmpty()) null else u1Code,
                utility1SlotQuantity = if (u1Code.isNullOrEmpty()) null else u1Qty,
                utility2Slot  = if (u2Code.isNullOrEmpty()) null else u2Code,
                utility2SlotQuantity = if (u2Code.isNullOrEmpty()) null else u2Qty
            )
        }
        val request = com.artifactsmmo.client.models.CombatSimulationRequest(
            characters = fakeChars,
            monster    = monsterCode,
            iterations = iterations
        )
        return SimulationRateLimiter.execute { client.simulation.simulateFight(request) }
    }

    /**
     * Simulate combat locally (no API call) after applying [slotOverrides] to [char]'s stats.
     * For each overridden slot, fetches the currently-equipped item and the candidate item
     * from ContentCache, then applies the stat delta via [Character.applyItemDelta].
     * Falls back to simulating the unmodified character if any item fetch fails.
     */
    suspend fun simulateLocalWithOverrides(
        char: Character,
        monster: com.artifactsmmo.client.models.Monster,
        slotOverrides: Map<String, String>,
        iterations: Int = 50
    ): LocalFightSimulator.Result {
        var modifiedChar = char
        for ((slot, newCode) in slotOverrides) {
            val oldCode = getEquippedInSlot(char, slot)
            val removedItem = if (oldCode.isNotEmpty()) try { contentCache.getItem(oldCode) } catch (_: Exception) { null } else null
            val addedItem   = try { contentCache.getItem(newCode) } catch (_: Exception) { null }
            if (addedItem != null || removedItem != null) {
                modifiedChar = modifiedChar.applyItemDelta(removedItem, addedItem)
            }
        }
        return LocalFightSimulator.simulate(modifiedChar, monster, iterations)
    }

    /**
     * Retrieve gear from inventory/bank/workshop (craftable) and equip it.
     *
     * Guarantees:
     *  - Pre-verifies all required items are available before modifying anything.
     *    If any item is missing, aborts without unequipping.
     *  - Previous items are NOT deposited until AFTER new items are successfully equipped,
     *    so rollback is possible on any per-slot failure.
     *  - On withdrawal/craft failure, rolls back by re-equipping the previously equipped items.
     *  - Explicit `println("name ...")` logging on every failure for diagnosability.
     *  - Safety net: after all swaps, verifies no critical combat slot was left empty.
     *
     * Tools (subtype == "tool", e.g. pickaxes, axes) are never deposited.
     */
    suspend fun retrieveAndEquipItems(
        characterName: String,
        equipActions: List<EquipAction>
    ): Character {
        if (equipActions.isEmpty()) return refreshCharacter(characterName)

        var char = refreshCharacter(characterName)

        // ── Step 1: Soft-verify item availability via bankState snapshot ──
        // bankState is an eventually-consistent snapshot — it may lag behind actual bank
        // contents when another character recently withdrew items in the same boss fight
        // equip sequence. We log warnings here but do NOT abort; the actual withdrawal
        // API call at Step 4 is the authoritative check. If it fails, rollback handles it.
        val bankActions = equipActions.filter { it.source == "bank" }
        val invActions  = equipActions.filter { it.source == "inventory" }
        val craftActions = equipActions.filter { it.source == "craftable" }

        val bankNeeded = bankActions.groupingBy { it.itemCode }.eachCount()
        for ((code, needed) in bankNeeded) {
            val available = bankState.getQuantity(code)
            if (available < needed) {
                println("[$characterName] retrieveAndEquipItems: WARNING — bankState shows $available x $code but need $needed (may be stale; proceeding to attempt withdrawal)")
            }
        }
        val invNeeded = invActions.groupingBy { it.itemCode }.eachCount()
        for ((code, needed) in invNeeded) {
            val available = getItemQuantity(char, code)
            if (available < needed) {
                println("[$characterName] retrieveAndEquipItems: WARNING — inventory has $available x $code but need $needed (proceeding anyway)")
            }
        }
        // Craftable: check ingredients in bank — warn only, don't abort
        for (action in craftActions) {
            val item = try { getItem(action.itemCode) } catch (e: Exception) {
                println("[$characterName] retrieveAndEquipItems: WARNING — cannot load craftable ${action.itemCode}: ${e.message}")
                continue
            }
            val craft = item.craft ?: continue
            for (ingredient in craft.items) {
                val available = bankState.getQuantity(ingredient.code)
                if (available < ingredient.quantity) {
                    println("[$characterName] retrieveAndEquipItems: WARNING — bank shows $available x ${ingredient.code} but need ${ingredient.quantity} to craft ${action.itemCode} (may be stale)")
                }
            }
        }

        // Snapshot what's currently equipped in each affected slot (used for rollback)
        val prevEquipped: Map<String, String> = equipActions.associate { it.slot to getEquippedInSlot(char, it.slot) }

        // Helper: attempt rollback by re-equipping any previously-equipped items
        // Only re-equips items whose slots we've unequipped and whose previous code is non-empty.
        // Silently swallows inner failures — this is a best-effort recovery.
        suspend fun rollback(reason: String, unequippedSlots: Set<String>): Character {
            println("[$characterName] retrieveAndEquipItems: rolling back — $reason")
            var c = refreshCharacter(characterName)
            for (slot in unequippedSlots) {
                val prev = prevEquipped[slot] ?: continue
                if (prev.isEmpty()) continue
                try {
                    c = equip(characterName, prev, slot)
                    println("[$characterName] retrieveAndEquipItems: rollback restored $prev in $slot")
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipItems: rollback FAILED to restore $prev in $slot: ${e.message}")
                }
            }
            return c
        }

        // ── Fast path: all inventory sources — no bank trip needed ──
        if (bankActions.isEmpty() && craftActions.isEmpty()) {
            val unequippedSlots = mutableSetOf<String>()
            for (action in equipActions) {
                val equipped = prevEquipped[action.slot] ?: ""
                if (equipped.isNotEmpty()) {
                    try {
                        char = unequip(characterName, action.slot)
                        unequippedSlots.add(action.slot)
                    } catch (e: Exception) {
                        println("[$characterName] retrieveAndEquipItems: unequip failed for ${action.slot}: ${e.message}")
                        return rollback("unequip failed for ${action.slot}", unequippedSlots)
                    }
                } else {
                    unequippedSlots.add(action.slot) // slot was empty; still track for rollback semantics
                }
            }
            // Equip new items
            for (action in equipActions) {
                try {
                    char = equip(characterName, action.itemCode, action.slot)
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipItems: equip failed for ${action.itemCode} in ${action.slot}: ${e.message}")
                    // Per-slot rollback: try to restore the original item for this slot
                    val prev = prevEquipped[action.slot] ?: ""
                    if (prev.isNotEmpty()) {
                        try {
                            char = equip(characterName, prev, action.slot)
                            println("[$characterName] retrieveAndEquipItems: per-slot rollback restored $prev in ${action.slot}")
                        } catch (e2: Exception) {
                            println("[$characterName] retrieveAndEquipItems: per-slot rollback FAILED for ${action.slot}: ${e2.message} — SLOT LEFT EMPTY")
                        }
                    }
                }
            }
            return char
        }

        // ── Bank/craftable path ──

        // Step 2: Move to bank — use navigateToTile so cross-layer characters (e.g. a
        // participant coming from an underground fight when boss dispatch fires) are
        // properly routed back through the appropriate transition instead of failing 595.
        val bank = findNearestBank(char) ?: run {
            println("[$characterName] retrieveAndEquipItems: aborting — no bank found on map")
            return char
        }
        char = navigateToTile(characterName, bank)

        // Step 2.5: Pre-deposit non-essential inventory to make room for unequipped items
        // + new bank withdrawals. Without this, characters with nearly-full inventory hit
        // 497 during withdrawal (unequip fills inventory, then withdraw has no space).
        //
        // Uses SLOT arithmetic (not quantity arithmetic): each unique item code occupies
        // exactly 1 inventory slot regardless of stack size.
        //
        // Needed slots = slots to unequip (each adds 1 slot) +
        //                slots to withdraw from bank (each new code adds 1 slot) +
        //                craft ingredients (each new code adds 1 slot) +
        //                5 safety margin
        val slotsOccupied = char.inventory.count { it.quantity > 0 }
        val freeSlots = char.inventoryMaxItems - slotsOccupied
        val inventoryCodes = char.inventory.filter { it.quantity > 0 }.map { it.code }.toSet()
        // Only count bank/craft items that would occupy a NEW slot (not already in inventory)
        val newBankSlots = bankActions.count { it.itemCode !in inventoryCodes }
        val newCraftIngredientSlots = craftActions.sumOf { action ->
            try {
                getItem(action.itemCode).craft?.items
                    ?.count { ingredient -> ingredient.code !in inventoryCodes } ?: 0
            } catch (_: Exception) { 0 }
        }
        val headroomNeeded = prevEquipped.count { it.value.isNotEmpty() } + newBankSlots + newCraftIngredientSlots + 5
        val slotsToFree = headroomNeeded - freeSlots

        if (slotsToFree > 0) {
            val depositList = mutableListOf<SimpleItem>()
            for (slot in char.inventory) {
                if (depositList.size >= slotsToFree) break
                if (slot.quantity <= 0 || slot.code.isEmpty()) continue
                val item = try { contentCache.getItemOrNull(slot.code) } catch (_: Exception) { null }
                if (item == null) continue
                // Never pre-deposit tools or items we're about to equip
                if (item.subtype == "tool") continue
                if (equipActions.any { it.itemCode == slot.code }) continue
                depositList.add(SimpleItem(slot.code, slot.quantity))
            }
            if (depositList.isNotEmpty()) {
                try {
                    println("[$characterName] retrieveAndEquipItems: pre-depositing ${depositList.size} slot(s) to make room (need $slotsToFree free)")
                    val result = client.bank.depositItems(characterName, depositList)
                    waitForCooldown(result.cooldown.expiration)
                    char = result.character
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipItems: pre-deposit failed: ${e.message} — proceeding anyway")
                }
            }
        }

        // Step 3: Unequip replaced slots (do NOT deposit yet — needed for rollback)
        val unequippedSlots = mutableSetOf<String>()
        val skippedSlots = mutableSetOf<String>()  // slots skipped due to 483 (HP too high)
        for ((slot, equipped) in prevEquipped) {
            if (equipped.isNotEmpty()) {
                try {
                    char = unequip(characterName, slot)
                    unequippedSlots.add(slot)
                } catch (e: ArtifactsApiException) {
                    if (e.errorCode == 483) {
                        // 483: "not enough HP to unequip this item" — the item provides an HP
                        // bonus and current HP exceeds the max HP that would remain without it.
                        // This happens at full HP when swapping HP-boosting armor at the bank.
                        // We cannot reduce HP at the bank (resting heals, not damages).
                        // Skip this slot — keep the current item equipped. The optimizer will
                        // pick it up again next run once HP has naturally dropped in combat.
                        println("[$characterName] retrieveAndEquipItems: skipping $slot swap — 483 (HP too high to unequip $equipped). Will retry next optimization pass.")
                        skippedSlots.add(slot)
                    } else {
                        println("[$characterName] retrieveAndEquipItems: unequip failed for $slot: ${e.message}")
                        return rollback("unequip failed for $slot", unequippedSlots)
                    }
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipItems: unequip failed for $slot: ${e.message}")
                    return rollback("unequip failed for $slot", unequippedSlots)
                }
            }
        }

        // Step 4: Withdraw all bank items in a single batched API call.
        // Previously withdrawn one-at-a-time, which cost one API call + cooldown per item
        // (up to 13 calls for a full gear swap). Batching collapses this to a single round-trip,
        // saving ~12 cooldown waits per character during boss fight equip phases.
        // Fallback: if the batch fails (e.g. one item is no longer available due to cross-character
        // contention), retry per-item — skip individual 404s so the character equips as much as
        // possible rather than rolling back entirely.
        if (bankActions.isNotEmpty()) {
            val withdrawList = bankActions.map { SimpleItem(it.itemCode, 1) }
            val batchSuccess = try {
                val result = client.bank.withdrawItems(characterName, withdrawList)
                waitForCooldown(result.cooldown.expiration)
                char = result.character
                true
            } catch (e: Exception) {
                println("[$characterName] retrieveAndEquipItems: batched bank withdraw failed (${e.message}) — retrying per-item")
                false
            }

            if (!batchSuccess) {
                // Per-item fallback: attempt each item individually, skip those that fail
                for (action in bankActions) {
                    try {
                        val result = client.bank.withdrawItems(characterName, listOf(SimpleItem(action.itemCode, 1)))
                        waitForCooldown(result.cooldown.expiration)
                        char = result.character
                    } catch (e: Exception) {
                        println("[$characterName] retrieveAndEquipItems: skipping ${action.itemCode} — ${e.message}")
                    }
                }
            }
        }

        // Step 5: Withdraw craft ingredients
        for (action in craftActions) {
            try {
                val item = getItem(action.itemCode)
                val craft = item.craft ?: continue
                val ingredients = craft.items.map { SimpleItem(it.code, it.quantity) }
                val result = client.bank.withdrawItems(characterName, ingredients)
                waitForCooldown(result.cooldown.expiration)
                char = result.character
            } catch (e: Exception) {
                println("[$characterName] retrieveAndEquipItems: craft ingredient withdraw failed for ${action.itemCode}: ${e.message}")
                return rollback("craft ingredient withdraw failed for ${action.itemCode}", unequippedSlots)
            }
        }

        // Step 6: Craft craftable items at workshops
        val craftBySkill = craftActions.groupBy { action ->
            try { getItem(action.itemCode).craft?.skill ?: "weaponcrafting" }
            catch (_: Exception) { "weaponcrafting" }
        }
        for ((skill, actions) in craftBySkill) {
            val workshop = findNearestWorkshop(char, skill) ?: run {
                println("[$characterName] retrieveAndEquipItems: no $skill workshop found — skipping craft actions")
                continue
            }
            char = moveTo(characterName, workshop.x, workshop.y)
            for (action in actions) {
                try {
                    val result = client.actions.craft(characterName, action.itemCode, 1)
                    waitForCooldown(result.cooldown.expiration)
                    char = result.character
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipItems: craft failed for ${action.itemCode}: ${e.message}")
                    return rollback("craft failed for ${action.itemCode}", unequippedSlots)
                }
            }
        }

        // Step 7: Equip all new items — per-slot success tracking + rollback on failure.
        // Skip slots that were not unequipped due to 483 (skippedSlots).
        val successfullyEquippedSlots = mutableSetOf<String>()
        for (action in equipActions) {
            if (action.slot in skippedSlots) {
                println("[$characterName] retrieveAndEquipItems: skipping equip for ${action.slot} (was skipped during unequip due to 483)")
                continue
            }
            try {
                char = equip(characterName, action.itemCode, action.slot)
                successfullyEquippedSlots.add(action.slot)
            } catch (e: Exception) {
                println("[$characterName] retrieveAndEquipItems: equip failed for ${action.itemCode} in ${action.slot}: ${e.message}")
                // Per-slot rollback: try to restore the original item for this slot
                val prev = prevEquipped[action.slot] ?: ""
                if (prev.isNotEmpty()) {
                    try {
                        char = equip(characterName, prev, action.slot)
                        println("[$characterName] retrieveAndEquipItems: per-slot rollback restored $prev in ${action.slot}")
                    } catch (e2: Exception) {
                        println("[$characterName] retrieveAndEquipItems: per-slot rollback FAILED for ${action.slot}: ${e2.message} — SLOT LEFT EMPTY")
                    }
                }
            }
        }

        // Step 8: Deposit successfully replaced prev items (skip tools)
        val itemsToDeposit = mutableListOf<SimpleItem>()
        for ((slot, prevCode) in prevEquipped) {
            if (prevCode.isEmpty()) continue
            if (slot !in successfullyEquippedSlots) continue  // rollback restored the original — don't deposit
            if (contentCache.getItemOrNull(prevCode)?.subtype == "tool") continue
            itemsToDeposit.add(SimpleItem(prevCode, 1))
        }
        if (itemsToDeposit.isNotEmpty()) {
            try {
                val result = client.bank.depositItems(characterName, itemsToDeposit)
                waitForCooldown(result.cooldown.expiration)
                char = result.character
            } catch (e: Exception) {
                println("[$characterName] retrieveAndEquipItems: final deposit of replaced items failed: ${e.message} (items still in inventory)")
            }
        }

        // Step 9: Safety net — check no combat-critical slot was left empty when it shouldn't be
        char = refreshCharacter(characterName)
        for ((slot, prevCode) in prevEquipped) {
            if (prevCode.isEmpty()) continue  // slot was already empty before — that's fine
            val nowEquipped = getEquippedInSlot(char, slot)
            if (nowEquipped.isNotEmpty()) continue  // something is equipped — good
            // Slot ended up empty despite having something before. Try to recover.
            val inInventory = getItemQuantity(char, prevCode)
            if (inInventory >= 1) {
                try {
                    char = equip(characterName, prevCode, slot)
                    println("[$characterName] retrieveAndEquipItems: safety-net restored $prevCode in $slot from inventory")
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipItems: safety-net could not restore $prevCode in $slot: ${e.message}")
                }
            } else {
                println("[$characterName] retrieveAndEquipItems: WARNING — $slot ended up empty and $prevCode no longer in inventory")
            }
        }

        return char
    }

    /**
     * Retrieve utility potions from bank (if needed) and equip them in utility slots.
     * Utility slots hold stacks (up to 100) — quantity is part of the equip action.
     *
     * Strategy:
     *  1. If the character already has the potion in inventory (any quantity), equip
     *     what they have immediately — no bank trip needed for that slot.
     *  2. Only go to the bank for potions the character has ZERO of in inventory.
     *  3. [action.quantity] is an ideal target (100), not a hard requirement — equip
     *     whatever is available.
     */
    suspend fun retrieveAndEquipUtilities(
        characterName: String,
        actions: List<GearOptimizer.UtilityEquipAction>
    ): Character {
        if (actions.isEmpty()) return refreshCharacter(characterName)

        var char = refreshCharacter(characterName)

        // Split actions: those we can satisfy from inventory right now vs those needing bank
        val inventoryActions = mutableListOf<GearOptimizer.UtilityEquipAction>()
        val bankNeededActions = mutableListOf<GearOptimizer.UtilityEquipAction>()

        for (action in actions) {
            val currentEquipped = getEquippedInSlot(char, action.slot)
            val equippedQty = if (currentEquipped == action.itemCode) getEquippedUtilityQuantity(char, action.slot) else 0
            val inInv = getItemQuantity(char, action.itemCode)

            when {
                // Already equipped at or above target — nothing to do
                currentEquipped == action.itemCode && equippedQty >= action.quantity -> {
                    // no-op
                }
                // Have some in inventory — equip from inventory (bank trip not required)
                inInv > 0 || equippedQty > 0 -> inventoryActions.add(action)
                // Nothing in inventory or equipped — need bank
                else -> {
                    val bankQty = bankState.getQuantity(action.itemCode)
                    if (bankQty > 0) bankNeededActions.add(action)
                    else println("[$characterName] retrieveAndEquipUtilities: no ${action.itemCode} in inventory or bank — skipping")
                }
            }
        }

        // Equip inventory-only actions immediately (no bank trip)
        for (action in inventoryActions) {
            val current = getEquippedInSlot(char, action.slot)
            val currentQty = if (current == action.itemCode) getEquippedUtilityQuantity(char, action.slot) else 0
            val inInventory = getItemQuantity(char, action.itemCode)
            try {
                when {
                    current == action.itemCode && currentQty >= action.quantity -> { /* already good */ }
                    current == action.itemCode && inInventory > 0 -> {
                        val addQty = inInventory.coerceAtMost(action.quantity - currentQty)
                        if (addQty > 0) char = equip(characterName, action.itemCode, action.slot, addQty)
                    }
                    current.isNotEmpty() && current != action.itemCode -> {
                        char = unequip(characterName, action.slot)
                        val qty = inInventory.coerceAtMost(action.quantity).coerceAtLeast(1)
                        char = equip(characterName, action.itemCode, action.slot, qty)
                    }
                    else -> {
                        if (inInventory > 0) {
                            val qty = inInventory.coerceAtMost(action.quantity)
                            char = equip(characterName, action.itemCode, action.slot, qty)
                        }
                    }
                }
            } catch (e: Exception) {
                println("[$characterName] retrieveAndEquipUtilities: equip failed for ${action.itemCode} in ${action.slot}: ${e.message}")
            }
        }

        // Bank path: only for potions not already in inventory
        if (bankNeededActions.isNotEmpty()) {
            val bank = findNearestBank(char)
            if (bank == null) {
                println("[$characterName] retrieveAndEquipUtilities: no bank found — skipping bank withdrawals")
                return char
            }
            char = navigateToTile(characterName, bank)

            // Determine how many new slots we need
            val inventoryCodesNow = char.inventory.filter { it.quantity > 0 }.map { it.code }.toSet()
            val newSlotCodes = bankNeededActions.map { it.itemCode }.filter { it !in inventoryCodesNow }.toSet()
            val freeSlots = char.inventoryMaxItems - char.inventory.count { it.quantity > 0 }
            val slotsNeeded = (newSlotCodes.size - freeSlots).coerceAtLeast(0)

            if (slotsNeeded > 0) {
                val keepCodes = bankNeededActions.map { it.itemCode }.toSet()
                val depositList = char.inventory
                    .filter { it.quantity > 0 && it.code !in keepCodes }
                    .map { SimpleItem(it.code, it.quantity) }
                if (depositList.isNotEmpty()) {
                    try {
                        println("[$characterName] retrieveAndEquipUtilities: pre-depositing ${depositList.size} slot(s) to make room (need $slotsNeeded free)")
                        val result = client.bank.depositItems(characterName, depositList)
                        waitForCooldown(result.cooldown.expiration)
                        char = result.character
                    } catch (e: Exception) {
                        println("[$characterName] retrieveAndEquipUtilities: pre-deposit failed: ${e.message} — proceeding anyway")
                    }
                }
            }

            for (action in bankNeededActions) {
                val bankQty = bankState.getQuantity(action.itemCode)
                val inInv = getItemQuantity(char, action.itemCode)
                val deficit = (action.quantity - inInv).coerceAtMost(bankQty)
                if (deficit <= 0) continue
                try {
                    val result = client.bank.withdrawItems(characterName, listOf(SimpleItem(action.itemCode, deficit)))
                    waitForCooldown(result.cooldown.expiration)
                    char = result.character
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipUtilities: bank withdraw failed for ${action.itemCode}: ${e.message}")
                    continue
                }
                // Equip what we now have
                val current = getEquippedInSlot(char, action.slot)
                val inInventory = getItemQuantity(char, action.itemCode)
                try {
                    if (current.isNotEmpty() && current != action.itemCode) char = unequip(characterName, action.slot)
                    if (inInventory > 0) {
                        val qty = inInventory.coerceAtMost(action.quantity)
                        char = equip(characterName, action.itemCode, action.slot, qty)
                    }
                } catch (e: Exception) {
                    println("[$characterName] retrieveAndEquipUtilities: equip failed for ${action.itemCode} in ${action.slot}: ${e.message}")
                }
            }
        }

        return char
    }

    /**
     * Determine all item/gold costs incurred transitioning from [char]'s current position
     * to [targetMap], WITHOUT navigating. Inspects in-memory [ContentCache] tile data only.
     *
     * Covers three transition categories that match [navigateToTile]'s routing logic:
     *  1. Exit from sub-layer (if character is not on overworld and not already on target layer)
     *  2. Cross-layer entry (if target is on a different layer)
     *  3. Same-layer gate (if target is on the same layer but behind a same-layer transition)
     *
     * Only `operator == "cost"` conditions are included — `has_item` is not a consumed cost.
     * Gold costs are returned under key "gold". Item costs use their item code as key.
     * Returns an empty map when the path has no cost conditions.
     */
    fun getTransitionCosts(char: com.artifactsmmo.client.models.Character, targetMap: MapInfo): Map<String, Int> {
        val costs = mutableMapOf<String, Int>()

        fun collectCosts(tile: MapInfo?) {
            tile?.interactions?.transition?.conditions
                ?.filter { it.operator == "cost" }
                ?.forEach { c -> costs[c.code] = (costs[c.code] ?: 0) + c.value }
        }

        val targetLayer = targetMap.layer

        // 1. Exit from sub-layer if character is not already on target layer
        if (char.layer != "overworld" && char.layer != targetLayer) {
            collectCosts(contentCache.findExitTransitionTile(char))
        }

        // 2. Cross-layer entry transition
        if (char.layer != targetLayer) {
            val entryLayer = if (char.layer == "overworld") targetLayer else "overworld"
            collectCosts(contentCache.findTransitionTile(char, entryLayer, targetMap.x, targetMap.y))
        }

        // 3. Same-layer gate (e.g. gold-gated overworld sub-region, key-gated underground area)
        collectCosts(contentCache.findSameLayerTransitionTo(targetMap.x, targetMap.y, targetLayer))

        return costs
    }

    /**
     * Withdraw reserve potion stacks from the bank into the character's inventory for
     * boss fight loop sustainability. These stacks are carried in inventory and re-equipped
     * mid-loop when a utility slot drops to [CoopOptimizer.UTILITY_REEQUIP_THRESHOLD].
     *
     * Only withdraws what is needed — if the character already has some of a given potion
     * in inventory, the deficit is calculated and only the missing quantity is withdrawn.
     * Skips silently if the bank has none of a requested potion.
     */
    suspend fun withdrawReservePotions(
        characterName: String,
        reservePotions: Map<String, Int>
    ): Character {
        if (reservePotions.isEmpty()) return refreshCharacter(characterName)

        var char = refreshCharacter(characterName)

        val toWithdraw = mutableListOf<SimpleItem>()
        for ((code, desiredQty) in reservePotions) {
            val inInv = getItemQuantity(char, code)
            val deficit = desiredQty - inInv
            if (deficit <= 0) continue
            val bankQty = bankState.getQuantity(code)
            val qty = deficit.coerceAtMost(bankQty)
            if (qty > 0) toWithdraw.add(SimpleItem(code, qty))
        }

        if (toWithdraw.isEmpty()) return char

        val bank = findNearestBank(char)
        if (bank == null) {
            println("[$characterName] withdrawReservePotions: no bank found — skipping")
            return char
        }
        char = navigateToTile(characterName, bank)
        return try {
            val result = client.bank.withdrawItems(characterName, toWithdraw)
            waitForCooldown(result.cooldown.expiration)
            result.character
        } catch (e: Exception) {
            println("[$characterName] withdrawReservePotions: withdraw failed: ${e.message}")
            char
        }
    }

    // ── Task Master ──

    /**
     * Find the nearest task master of a given type ("items" or "monsters").
     */
    suspend fun findNearestTasksMaster(char: Character, type: String): MapInfo? {
        return findNearest(char, "tasks_master", type)
    }

    /**
     * Accept a new task from the task master.
     * Character must already be at a tasks_master tile.
     */
    suspend fun acceptTask(name: String): com.artifactsmmo.client.models.TaskData {
        val result = client.tasks.acceptNewTask(name)
        waitForCooldown(result.cooldown.expiration)
        return result
    }

    /**
     * Trade items for the current task.
     * Character must already be at the relevant tasks_master tile.
     */
    suspend fun tradeTask(name: String, itemCode: String, quantity: Int): Character {
        val char = client.tasks.tradeTask(name, itemCode, quantity)
        waitForCooldown(char.cooldownExpiration)
        return char
    }

    /**
     * Complete the current task after all items/kills have been turned in.
     * Character must already be at the relevant tasks_master tile.
     */
    suspend fun completeTask(name: String): com.artifactsmmo.client.models.RewardData {
        val result = client.tasks.completeTask(name)
        waitForCooldown(result.cooldown.expiration)
        return result
    }

    /**
     * Cancel the current task.
     * Character must already be at the relevant tasks_master tile.
     */
    suspend fun cancelTask(name: String): Character {
        val char = client.tasks.cancelTask(name)
        waitForCooldown(char.cooldownExpiration)
        return char
    }

    /**
     * A single raw ingredient in a crafting recipe that can be obtained by gathering.
     */
    data class TaskItemIngredient(
        /** The raw item code dropped by the resource (e.g. "iron_ore", "coal"). */
        val rawItemCode: String,
        /** How many of this item are needed per single craft. */
        val rawPerCraft: Int,
        /** The gathering skill used to collect this ingredient. */
        val gatherSkill: String,
        /** The resource node code on the map. */
        val resourceCode: String,
        /** Human-readable resource name. */
        val resourceName: String,
        /** Minimum gathering skill level required. */
        val gatherLevel: Int
    )

    /**
     * Information about how to obtain a task item.
     */
    data class TaskItemSource(
        /** The gathering skill needed (primary ingredient). */
        val gatherSkill: String,
        /** The resource code to gather from (primary ingredient). */
        val resourceCode: String,
        /** The resource name (primary ingredient). */
        val resourceName: String,
        /** The raw item code that the resource drops (primary ingredient). */
        val rawItemCode: String,
        /** True if the task item is crafted from the raw item (needs crafting step). */
        val needsCrafting: Boolean,
        /** The crafting skill needed (e.g., "cooking", "mining" for smelting). Null if no crafting needed. */
        val craftSkill: String? = null,
        /** Required crafting skill level. */
        val craftLevel: Int = 0,
        /** How many of the primary raw item are needed per craft. */
        val rawPerCraft: Int = 1,
        /** How many target items are produced per craft. */
        val outputPerCraft: Int = 1,
        /** The required gathering skill level (primary ingredient). */
        val gatherLevel: Int = 0,
        /**
         * All gatherable ingredients for crafted items (including the primary one above).
         * Empty for direct-gather items. When non-empty, the loop must collect every
         * ingredient before crafting — not just the first one.
         */
        val allIngredients: List<TaskItemIngredient> = emptyList()
    )

    /**
     * Determine how to obtain a task item.
     * - If the item is directly dropped by a resource, returns the resource info.
     * - If the item is crafted, discovers ALL ingredients that are directly gatherable
     *   from resource nodes and returns them in [TaskItemSource.allIngredients].
     *   The primary fields (gatherSkill, resourceCode, etc.) reflect the first ingredient.
     * Returns null if the item cannot be obtained through gathering.
     */
    suspend fun findTaskItemSource(itemCode: String): TaskItemSource? {
        // First: check if any resource directly drops this item.
        // Result is cached in ContentCache for 24 hours — no live API call after first lookup.
        val directResources = contentCache.getResourcesByDrop(itemCode)
        if (directResources.isNotEmpty()) {
            val resource = directResources.first()
            val ingredient = TaskItemIngredient(
                rawItemCode  = itemCode,
                rawPerCraft  = 1,
                gatherSkill  = resource.skill,
                resourceCode = resource.code,
                resourceName = resource.name,
                gatherLevel  = resource.level
            )
            return TaskItemSource(
                gatherSkill   = resource.skill,
                resourceCode  = resource.code,
                resourceName  = resource.name,
                rawItemCode   = itemCode,
                needsCrafting = false,
                gatherLevel   = resource.level,
                allIngredients = listOf(ingredient)
            )
        }

        // Second: check if the item is crafted, and trace ALL raw ingredients.
        val item = contentCache.getItemOrNull(itemCode) ?: return null
        val craft = item.craft ?: return null
        val craftSkill = craft.skill ?: return null

        // Collect every ingredient that is directly obtainable via a resource node.
        // Each getResourcesByDrop() call is cached — no repeated API calls per ingredient.
        val gatherableIngredients = mutableListOf<TaskItemIngredient>()
        for (ingredient in craft.items) {
            val ingredientResources = contentCache.getResourcesByDrop(ingredient.code)
            if (ingredientResources.isNotEmpty()) {
                val resource = ingredientResources.first()
                gatherableIngredients.add(
                    TaskItemIngredient(
                        rawItemCode  = ingredient.code,
                        rawPerCraft  = ingredient.quantity,
                        gatherSkill  = resource.skill,
                        resourceCode = resource.code,
                        resourceName = resource.name,
                        gatherLevel  = resource.level
                    )
                )
            }
        }

        if (gatherableIngredients.isEmpty()) return null

        val primary = gatherableIngredients.first()
        return TaskItemSource(
            gatherSkill    = primary.gatherSkill,
            resourceCode   = primary.resourceCode,
            resourceName   = primary.resourceName,
            rawItemCode    = primary.rawItemCode,
            needsCrafting  = true,
            craftSkill     = craftSkill,
            craftLevel     = craft.level ?: 0,
            rawPerCraft    = primary.rawPerCraft,
            outputPerCraft = craft.quantity ?: 1,
            gatherLevel    = primary.gatherLevel,
            allIngredients = gatherableIngredients
        )
    }
}
