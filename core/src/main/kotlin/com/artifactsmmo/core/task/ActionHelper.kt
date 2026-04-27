package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.MapInfo
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

    // ── Map queries ──

    /**
     * Find the nearest map tile of a given content type/code to the character.
     */
    suspend fun findNearest(
        char: Character,
        contentType: String,
        contentCode: String? = null
    ): MapInfo? {
        return contentCache.findNearest(char, contentType, contentCode)
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
     */
    suspend fun bankDepositAll(name: String): Character {
        var char = refreshCharacter(name)

        val bank = findNearestBank(char) ?: throw IllegalStateException("No bank found on map")
        char = moveTo(name, bank.x, bank.y)

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
     */
    suspend fun bankDepositItems(name: String, items: List<SimpleItem>): Character {
        if (items.isEmpty()) return refreshCharacter(name)

        var char = refreshCharacter(name)
        val bank = findNearestBank(char) ?: throw IllegalStateException("No bank found on map")
        char = moveTo(name, bank.x, bank.y)

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

    suspend fun fight(name: String): com.artifactsmmo.client.models.CharacterFightData {
        val result = client.actions.fight(name)
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
     * Info about an item that can be crafted from available materials (inventory + bank).
     */
    data class CraftableItemInfo(
        val item: Item,
        val maxCraftable: Int,
        val ingredients: List<SimpleItem>
    )

    /**
     * Find items craftable with a specific skill from inventory + bank materials.
     * Returns list sorted by craft level descending (higher level = more XP).
     */
    suspend fun getAvailableCraftingItems(char: Character, skill: String): List<CraftableItemInfo> {
        val craftableItems = contentCache.getItemsBySkill(skill)

        val skillLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, skill) ?: 0

        val results = mutableListOf<CraftableItemInfo>()
        for (item in craftableItems) {
            val craft = item.craft ?: continue
            if ((craft.level ?: 0) > skillLevel) continue

            val maxCraftable = craft.items.minOfOrNull { ingredient ->
                val invQty = getItemQuantity(char, ingredient.code)
                val bankQty = getBankItemQuantity(ingredient.code)
                (invQty + bankQty) / ingredient.quantity
            } ?: 0

            if (maxCraftable > 0) {
                results.add(CraftableItemInfo(
                    item = item,
                    maxCraftable = maxCraftable,
                    ingredients = craft.items
                ))
            }
        }

        return results.sortedByDescending { it.item.craft?.level ?: 0 }
    }

    /**
     * Find miscellaneous craftable items from inventory + bank materials.
     * Excludes weaponcrafting, gearcrafting, and jewelrycrafting.
     * Returns list sorted by craft skill then craft level descending.
     */
    suspend fun getAvailableMiscCraftingItems(char: Character): List<CraftableItemInfo> {
        val excludedSkills = setOf("weaponcrafting", "gearcrafting", "jewelrycrafting")
        val miscSkills = listOf("cooking", "mining", "woodcutting", "alchemy")

        val results = mutableListOf<CraftableItemInfo>()
        for (skill in miscSkills) {
            val skillLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, skill) ?: 0

            val craftableItems = contentCache.getItemsBySkill(skill)

            for (item in craftableItems) {
                val craft = item.craft ?: continue
                if ((craft.level ?: 0) > skillLevel) continue

                val maxCraftable = craft.items.minOfOrNull { ingredient ->
                    val invQty = getItemQuantity(char, ingredient.code)
                    val bankQty = getBankItemQuantity(ingredient.code)
                    (invQty + bankQty) / ingredient.quantity
                } ?: 0

                if (maxCraftable > 0) {
                    results.add(CraftableItemInfo(
                        item = item,
                        maxCraftable = maxCraftable,
                        ingredients = craft.items
                    ))
                }
            }
        }

        return results.sortedByDescending { it.item.craft?.level ?: 0 }
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
        char = moveTo(name, bank.x, bank.y)

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

        // Page through all bank items
        var page = 1
        while (true) {
            val bankPage = try {
                client.bank.getBankItems(page = page, size = 100)
            } catch (_: Exception) {
                break
            }

            for (slot in bankPage.data) {
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

            if (page >= (bankPage.pages ?: Int.MAX_VALUE)) break
            if (bankPage.data.size < 100) break
            page++
        }
        return bestFood
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
     * Returns updated character.
     */
    suspend fun ensureToolEquipped(name: String, skill: String): Character {
        var char = refreshCharacter(name)
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

        // Get bank items
        val bankItems = mutableMapOf<String, Int>()
        var bankPage = 1
        while (true) {
            val result = client.bank.getBankItems(page = bankPage, size = 100)
            for (item in result.data) {
                bankItems[item.code] = (bankItems[item.code] ?: 0) + item.quantity
            }
            if (bankPage >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            bankPage++
        }

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

    /**
     * Find the best tool for a gathering skill that the character doesn't own but could
     * craft using materials currently in the bank.
     *
     * Returns null if no upgrade is available, or a ToolUpgradeInfo with the best
     * craftable upgrade and the ingredients to withdraw.
     */
    suspend fun findBestCraftableToolFromBank(char: Character, skill: String): ToolUpgradeInfo? {
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

        // Filter to tools better than what we have, sorted best first
        val upgradeCandidates = allTools
            .filter { it.level > currentBestLevel }
            .filter { it.craft != null }
            .sortedByDescending { it.level }

        // Get all bank items (paginated)
        val bankItems = mutableMapOf<String, Int>()
        var bankPage = 1
        while (true) {
            val result = client.bank.getBankItems(page = bankPage, size = 100)
            for (item in result.data) {
                bankItems[item.code] = (bankItems[item.code] ?: 0) + item.quantity
            }
            if (bankPage >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            bankPage++
        }

        // Check each candidate from best to worst
        for (tool in upgradeCandidates) {
            val craft = tool.craft ?: continue
            val craftSkill = craft.skill ?: continue
            val craftLevel = craft.level ?: 0

            // Check if character has the crafting skill level
            val charCraftLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, craftSkill) ?: 0
            if (charCraftLevel < craftLevel) continue

            // Check if bank has all ingredients
            val hasAllIngredients = craft.items.all { ingredient ->
                (bankItems[ingredient.code] ?: 0) >= ingredient.quantity
            }

            if (hasAllIngredients) {
                return ToolUpgradeInfo(
                    tool = tool,
                    craftSkill = craftSkill,
                    craftLevel = craftLevel,
                    ingredients = craft.items.map { SimpleItem(it.code, it.quantity) }
                )
            }
        }

        return null
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
    suspend fun getAvailableCraftedItems(skill: String, skillLevel: Int): List<Item> {
        val items = mutableListOf<Item>()
        var page = 1
        while (true) {
            val result = client.content.getItems(craftSkill = skill, maxLevel = skillLevel, page = page, size = 100)
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
     */
    suspend fun simulateFight(
        characterName: String,
        monsterCode: String,
        iterations: Int = 20
    ): com.artifactsmmo.client.models.CombatSimulationData {
        val char = refreshCharacter(characterName)
        val fakeChar = com.artifactsmmo.client.models.FakeCharacterRequest.fromCharacter(char)
        val request = com.artifactsmmo.client.models.CombatSimulationRequest(
            characters = listOf(fakeChar),
            monster = monsterCode,
            iterations = iterations
        )
        return client.simulation.simulateFight(request)
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

        // Build bank lookup (paginated)
        val bankMap = mutableMapOf<String, Int>()
        var bankPage = 1
        while (true) {
            val result = try {
                client.bank.getBankItems(page = bankPage, size = 100)
            } catch (_: Exception) { break }
            for (item in result.data) {
                bankMap[item.code] = (bankMap[item.code] ?: 0) + item.quantity
            }
            if (bankPage >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            bankPage++
        }

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
        // First: check if any resource directly drops this item
        val directResources = client.content.getResources(drop = itemCode, size = 100)
        if (directResources.data.isNotEmpty()) {
            val resource = directResources.data.first()
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

        // Second: check if the item is crafted, and trace ALL raw ingredients
        val item = contentCache.getItemOrNull(itemCode) ?: return null
        val craft = item.craft ?: return null
        val craftSkill = craft.skill ?: return null

        // Collect every ingredient that is directly obtainable via a resource node
        val gatherableIngredients = mutableListOf<TaskItemIngredient>()
        for (ingredient in craft.items) {
            val ingredientResources = client.content.getResources(drop = ingredient.code, size = 100)
            if (ingredientResources.data.isNotEmpty()) {
                val resource = ingredientResources.data.first()
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
