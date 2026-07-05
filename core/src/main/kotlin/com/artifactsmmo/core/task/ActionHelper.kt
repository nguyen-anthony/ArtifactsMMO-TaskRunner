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

/**
 * Common helper functions for character actions.
 * Handles cooldowns, movement, banking, equipping, and inventory management.
 */
class ActionHelper(private val client: ArtifactsMMOClient, private val contentCache: ContentCache) {

    // ── Cooldown ──

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
            waitForCooldown(char.cooldown)
        }
    }

    // ── Character refresh ──

    suspend fun refreshCharacter(name: String): Character {
        return client.characters.getCharacter(name)
    }

    // ── Movement ──

    /**
     * Move character to coordinates. Returns updated character.
     * Handles "already at destination" (497) gracefully.
     */
    suspend fun moveTo(name: String, x: Int, y: Int): Character {
        return try {
            val result = client.actions.move(name, x, y)
            waitForCooldown(result.cooldown.totalSeconds)
            result.character
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 490 || e.errorCode == 497) {
                // Already at destination
                refreshCharacter(name)
            } else throw e
        }
    }

    /**
     * Check if character is at the given coordinates.
     */
    fun isAt(char: Character, x: Int, y: Int): Boolean {
        return char.x == x && char.y == y
    }

    /**
     * Navigate to a map tile, handling layer transitions transparently.
     *
     * The game has three layers: "overworld" (the default), "underground", and "interior".
     * Resources, monsters, and other content may exist on any layer. Travelling between
     * layers requires:
     *   1. Moving to the transition tile on the current layer.
     *   2. POSTing to /action/transition to cross to the other layer.
     *   3. Moving to the final destination on the target layer.
     *
     * This method handles all three steps automatically:
     * - If [targetMap] is on the same layer as the character → plain [moveTo].
     * - If the character is on a sub-layer (underground/interior) but the target is
     *   on a different layer → find and use the exit transition first to return to
     *   the overworld, then navigate from there.
     * - If the target is on a sub-layer → find the overworld transition tile leading
     *   to that layer, move there, cross, then move to the final destination.
     *
     * Returns the updated [Character] after all movement and transitions are complete.
     */
    suspend fun navigateToTile(name: String, targetMap: MapInfo): Character {
        var char = refreshCharacter(name)
        val targetLayer = targetMap.layer

        // ── Step 1: If on a sub-layer and the target is not on the same layer,
        //            exit back to the overworld first ──────────────────────────
        if (char.layer != "overworld" && char.layer != targetLayer) {
            val exitTile = contentCache.findExitTransitionTile(char)
                ?: throw IllegalStateException(
                    "Cannot navigate: character is on layer '${char.layer}' with no exit transition"
                )
            char = moveTo(name, exitTile.x, exitTile.y)
            char = useTransition(name)
        }

        // ── Step 2: If the target is on a sub-layer, cross to it ─────────────
        if (targetLayer != "overworld" && char.layer == "overworld") {
            val entryTile = contentCache.findTransitionTile(char, targetLayer, targetMap.x, targetMap.y)
                ?: throw IllegalStateException(
                    "Cannot navigate: no accessible overworld transition tile leads to layer '$targetLayer'"
                )
            char = moveTo(name, entryTile.x, entryTile.y)
            char = useTransition(name)
        }

        // ── Step 3: Move to the final destination on the correct layer ────────
        if (!isAt(char, targetMap.x, targetMap.y)) {
            char = moveTo(name, targetMap.x, targetMap.y)
        }

        return char
    }

    /**
     * Use the map transition on the tile the character is currently standing on.
     * The character must already be on a tile that has a [MapTransition].
     */
    private suspend fun useTransition(name: String): Character {
        val result = client.actions.transition(name)
        waitForCooldown(result.cooldown.totalSeconds)
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
            waitForCooldown(result.cooldown.totalSeconds)
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
        waitForCooldown(result.cooldown.totalSeconds)
        return result.character
    }

    // ── Gathering ──

    /**
     * Perform a single gather action. Character must already be at a resource tile.
     */
    suspend fun gather(name: String): com.artifactsmmo.client.models.SkillData {
        val result = client.actions.gather(name)
        waitForCooldown(result.cooldown.totalSeconds)
        return result
    }

    // ── Fighting ──

    suspend fun fight(name: String, participants: List<String> = emptyList()): com.artifactsmmo.client.models.CharacterFightData {
        val result = client.actions.fight(name, participants)
        waitForCooldown(result.cooldown.totalSeconds)
        return result
    }

    suspend fun rest(name: String): Character {
        val result = client.actions.rest(name)
        waitForCooldown(result.cooldown.totalSeconds)
        return result.character
    }

    // ── Crafting ──

    suspend fun craft(name: String, itemCode: String, quantity: Int = 1): com.artifactsmmo.client.models.SkillData {
        val result = client.actions.craft(name, itemCode, quantity)
        waitForCooldown(result.cooldown.totalSeconds)
        return result
    }

    // ── Recycling ──

    /**
     * Recycle items at a workshop. Character must be at the appropriate workshop.
     * Returns the recycling result including recovered materials.
     */
    suspend fun recycle(name: String, itemCode: String, quantity: Int = 1): com.artifactsmmo.client.models.RecyclingData {
        val result = client.actions.recycle(name, itemCode, quantity)
        waitForCooldown(result.cooldown.totalSeconds)
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
        waitForCooldown(result.cooldown.totalSeconds)
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
        waitForCooldown(result.cooldown.totalSeconds)
        return result.character
    }

    /**
     * Check how many of an item are in the bank.
     */
    suspend fun getBankItemQuantity(itemCode: String): Int {
        return try {
            val result = client.bank.getBankItems(itemCode)
            result.data.firstOrNull()?.quantity ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Fetch a single page of bank items (no item_code filter). Used by [CraftingExecutor]
     * to build a full bank snapshot in one paginated sweep instead of one call per ingredient.
     */
    suspend fun getBankItems(page: Int = 1, size: Int = 100) =
        client.bank.getBankItems(page = page, size = size)

    // ── Bank snapshot cache ──

    /**
     * Short-lived in-memory snapshot of bank contents: item code → quantity.
     *
     * TTL of [BANK_SNAPSHOT_TTL_MS] (10 seconds). All bank-reading functions
     * (tool upgrade checks, food search, weapon selection, crafting) share this
     * snapshot so that multiple functions executing within the same logical step
     * issue at most one paginated GET /my/bank/items sweep instead of one each.
     *
     * 10 seconds is safely shorter than any action cooldown, so the snapshot is
     * always stale by the time the next task iteration starts.
     */
    private var bankSnapshot: Map<String, Int> = emptyMap()
    private var bankSnapshotTimestamp: Long = 0L
    private val BANK_SNAPSHOT_TTL_MS = 10_000L

    /**
     * Return the cached bank snapshot if it is still within [BANK_SNAPSHOT_TTL_MS],
     * otherwise fetch all bank pages, update the snapshot, and return it.
     */
    suspend fun getOrRefreshBankSnapshot(): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (now - bankSnapshotTimestamp < BANK_SNAPSHOT_TTL_MS) return bankSnapshot
        val fresh = mutableMapOf<String, Int>()
        var page = 1
        while (true) {
            val result = try {
                client.bank.getBankItems(page = page, size = 100)
            } catch (_: Exception) { break }
            for (item in result.data) {
                fresh[item.code] = (fresh[item.code] ?: 0) + item.quantity
            }
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        bankSnapshot = fresh
        bankSnapshotTimestamp = now
        return bankSnapshot
    }

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

        // Use shared bank snapshot instead of an independent paginated sweep.
        val bankItems = getOrRefreshBankSnapshot()
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
            waitForCooldown(result.cooldown.totalSeconds)
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
        waitForCooldown(result.cooldown.totalSeconds)
        return result.character
    }

    suspend fun unequip(name: String, slot: String): Character {
        val result = client.actions.unequip(name, slot)
        waitForCooldown(result.cooldown.totalSeconds)
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

        // Use shared bank snapshot instead of an independent paginated sweep.
        val bankItems = getOrRefreshBankSnapshot()

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
            client.simulation.simulateFight(request)
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
            else         -> ""
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

        // Build bank lookup using shared snapshot instead of an independent paginated sweep.
        val bankMap = getOrRefreshBankSnapshot()

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
        return client.simulation.simulateFight(request)
    }

    /**
     * Find the best combat weapon available to the character for fighting a specific monster.
     *
     * Strategy (single-phase, no simulation):
     *
     * Candidates: all weapons the character owns (inventory, bank, or currently equipped)
     * that are within their character level and are NOT gathering tools (subtype != "tool").
     *
     * If the character has no combat weapons at all, returns an [EquipAction] with an empty
     * itemCode ("") — the caller should treat this as "unequip current weapon" so the
     * character doesn't fight with a gathering tool.
     *
     * Scoring per weapon:
     *   score = baseDmg + elementalBonus
     *
     * where:
     *   baseDmg      = the weapon's "dmg" effect value (or 0 if absent) — ensures higher-level
     *                  weapons without elemental attacks are not incorrectly ranked below
     *                  lower-level elemental weapons.
     *   elementalBonus = Σ attack_element * -(res_element - meanRes)
     *                  Uses mean-subtracted resistances so scoring only captures relative
     *                  elemental advantage, not absolute resistance levels. When all resistances
     *                  are equal the bonus is 0 and level/damage alone decides the ranking.
     *
     * The weapon with the highest score is selected. Ties are broken by item level descending.
     * Returns null if the best weapon is already equipped (no swap needed) or if no weapons
     * at all can be determined (monster data unavailable and no combat weapons owned).
     */
    suspend fun findBestCombatWeapon(
        char: Character,
        monsterCode: String
    ): EquipAction? {
        // All weapons in cache, filtered to character's level, excluding gathering tools
        val allWeapons = try {
            contentCache.getItemsByType("weapon").filter {
                it.level <= char.level && it.subtype != "tool"
            }
        } catch (_: Exception) { return null }

        // Fetch monster resistances for elemental scoring (cached in ContentCache via getItem)
        val monster = try {
            client.content.getMonster(monsterCode)
        } catch (_: Exception) { null }

        // Build inventory + bank availability map
        val inventoryCodes = char.inventory
            .filter { it.quantity > 0 }
            .associate { it.code to "inventory" }

        val bankCodes = getOrRefreshBankSnapshot()
            .filter { it.value > 0 }
            .mapValues { "bank" }

        val currentWeaponCode = char.weaponSlot.takeIf { it.isNotEmpty() }

        // Build candidate list: owned combat weapons (inventory, bank, or currently equipped)
        val ownedCombatCandidates = allWeapons.filter { weapon ->
            weapon.code in inventoryCodes ||
            weapon.code in bankCodes ||
            weapon.code == currentWeaponCode
        }

        // If the character has a tool equipped but no combat weapons anywhere, unequip the tool
        // so they don't fight with a fishing net / pickaxe / etc.
        if (ownedCombatCandidates.isEmpty()) {
            return if (currentWeaponCode != null) {
                // Signal "unequip" by returning an action with an empty itemCode
                EquipAction(slot = "weapon", itemCode = "", source = "unequip")
            } else {
                null // Nothing equipped, nothing to do
            }
        }

        // ── Scoring ──────────────────────────────────────────────────────────
        fun score(weapon: Item): Double {
            // Base damage contribution — keeps higher-level weapons ranked above
            // lower-level elemental weapons when the elemental bonus is small
            val effectMap = weapon.effects.associate { it.code to it.value }
            val baseDmg = (effectMap["dmg"] ?: effectMap["attack"] ?: 0).toDouble()

            // Elemental bonus using mean-subtracted resistances
            val elementalBonus = if (monster != null) {
                val meanRes = (monster.resFire + monster.resEarth + monster.resWater + monster.resAir) / 4.0
                val fireContrib  = (effectMap["attack_fire"]  ?: 0) * (-(monster.resFire  - meanRes))
                val earthContrib = (effectMap["attack_earth"] ?: 0) * (-(monster.resEarth - meanRes))
                val waterContrib = (effectMap["attack_water"] ?: 0) * (-(monster.resWater - meanRes))
                val airContrib   = (effectMap["attack_air"]   ?: 0) * (-(monster.resAir   - meanRes))
                fireContrib + earthContrib + waterContrib + airContrib
            } else 0.0

            return baseDmg + elementalBonus
        }

        // Select best: primary sort by score descending, tiebreaker by level descending
        val best = ownedCombatCandidates
            .sortedWith(compareByDescending<Item> { score(it) }.thenByDescending { it.level })
            .first()

        // No swap needed if best is already equipped
        if (best.code == currentWeaponCode) return null

        val source = when {
            best.code in inventoryCodes -> "inventory"
            best.code in bankCodes      -> "bank"
            else                        -> return null
        }

        return EquipAction(slot = "weapon", itemCode = best.code, source = source)
    }

    /**
     * Retrieve gear from inventory/bank/workshop (craftable) and equip it.
     *
     * Flow:
     *  1. Go to bank; unequip slots that are being replaced and deposit them.
     *     Tools (subtype == "tool", e.g. pickaxes, axes) are never deposited —
     *     they stay in inventory so characters keep their gathering tools.
     *  2. Withdraw bank items and craftable ingredients.
     *  3. Visit workshops and craft craftable items.
     *  4. Equip all new items.
     */
    suspend fun retrieveAndEquipItems(
        characterName: String,
        equipActions: List<EquipAction>
    ): Character {
        if (equipActions.isEmpty()) return refreshCharacter(characterName)

        var char = refreshCharacter(characterName)

        // Fast path: if all equip actions are from inventory, skip bank entirely
        if (equipActions.all { it.source == "inventory" }) {
            // Unequip current items in each slot
            for (action in equipActions) {
                val equipped = getEquippedInSlot(char, action.slot)
                if (equipped.isNotEmpty()) {
                    char = unequip(characterName, action.slot)
                }
            }
            // Equip new items
            for (action in equipActions) {
                try {
                    char = equip(characterName, action.itemCode, action.slot)
                } catch (_: Exception) {}
            }
            return char
        }

        // Snapshot what's currently equipped in each affected slot
        val prevEquipped = equipActions.associate { it.slot to getEquippedInSlot(char, it.slot) }

        // ── Step 1: Go to bank, unequip replaced slots, deposit them ──
        val bank = findNearestBank(char) ?: throw IllegalStateException("No bank found on map")
        char = moveTo(characterName, bank.x, bank.y)

        for ((slot, equipped) in prevEquipped) {
            if (equipped.isNotEmpty()) {
                char = unequip(characterName, slot)
            }
        }

        // Deposit unequipped items — but keep tools (pickaxes, axes, etc.) in inventory
        val itemsToDeposit = mutableListOf<SimpleItem>()
        for (code in prevEquipped.values) {
            if (code.isEmpty()) continue
            if (contentCache.getItemOrNull(code)?.subtype != "tool") {
                itemsToDeposit.add(SimpleItem(code, 1))
            }
        }
        if (itemsToDeposit.isNotEmpty()) {
            val result = client.bank.depositItems(characterName, itemsToDeposit)
            waitForCooldown(result.cooldown.totalSeconds)
            char = result.character
        }

        // ── Step 2: Withdraw bank items ──
        for (action in equipActions.filter { it.source == "bank" }) {
            try {
                val result = client.bank.withdrawItems(characterName, listOf(SimpleItem(action.itemCode, 1)))
                waitForCooldown(result.cooldown.totalSeconds)
                char = result.character
            } catch (_: Exception) {}
        }

        // ── Step 3: Withdraw ingredients for craftable items ──
        for (action in equipActions.filter { it.source == "craftable" }) {
            try {
                val item = getItem(action.itemCode)
                val craft = item.craft ?: continue
                val ingredients = craft.items.map { SimpleItem(it.code, it.quantity) }
                val result = client.bank.withdrawItems(characterName, ingredients)
                waitForCooldown(result.cooldown.totalSeconds)
                char = result.character
            } catch (_: Exception) {}
        }

        // ── Step 4: Craft craftable items at workshops ──
        val craftableActions = equipActions.filter { it.source == "craftable" }
        val craftBySkill = craftableActions.groupBy { action ->
            try { getItem(action.itemCode).craft?.skill ?: "weaponcrafting" }
            catch (_: Exception) { "weaponcrafting" }
        }
        for ((skill, actions) in craftBySkill) {
            val workshop = findNearestWorkshop(char, skill) ?: continue
            char = moveTo(characterName, workshop.x, workshop.y)
            for (action in actions) {
                try {
                    val result = client.actions.craft(characterName, action.itemCode, 1)
                    waitForCooldown(result.cooldown.totalSeconds)
                    char = result.character
                } catch (_: Exception) {}
            }
        }

        // ── Step 5: Equip all new items ──
        for (action in equipActions) {
            try {
                char = equip(characterName, action.itemCode, action.slot)
            } catch (_: Exception) {}
        }

        return char
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
        waitForCooldown(result.cooldown.totalSeconds)
        return result
    }

    /**
     * Trade items for the current task.
     * Character must already be at the relevant tasks_master tile.
     */
    suspend fun tradeTask(name: String, itemCode: String, quantity: Int): Character {
        val char = client.tasks.tradeTask(name, itemCode, quantity)
        waitForCooldown(char.cooldown)
        return char
    }

    /**
     * Complete the current task after all items/kills have been turned in.
     * Character must already be at the relevant tasks_master tile.
     */
    suspend fun completeTask(name: String): com.artifactsmmo.client.models.RewardData {
        val result = client.tasks.completeTask(name)
        waitForCooldown(result.cooldown.totalSeconds)
        return result
    }

    /**
     * Cancel the current task.
     * Character must already be at the relevant tasks_master tile.
     */
    suspend fun cancelTask(name: String): Character {
        val char = client.tasks.cancelTask(name)
        waitForCooldown(char.cooldown)
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
