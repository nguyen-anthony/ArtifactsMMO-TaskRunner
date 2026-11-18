package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.MapAccess
import com.artifactsmmo.client.models.MapInfo
import com.artifactsmmo.client.models.MapInteraction
import com.artifactsmmo.client.models.SimpleItem

/**
 * Executes event-driven task loops.
 *
 * EventGather: continuously gathers resources from an ephemeral event tile until the
 * event expires or the character's inventory is full (at which point it banks and returns).
 *
 * EventNpc: sell/buy at an ephemeral NPC event tile in bank ⇄ NPC trips, then reverts.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class EventExecutor(
    private val helper: ActionHelper,
    private val fightingExecutor: FightingExecutor
) {

    /**
     * Execute a single iteration of the event gather loop.
     *
     * Returns [StepResult.EventExpired] if the event is no longer active.
     * Returns [StepResult.Banked] after depositing a full inventory.
     * Returns [StepResult.Gathered] on a successful gather.
     * Returns [StepResult.Waiting] on a cooldown API error (486).
     * Returns [StepResult.EventExpired] on a map-content-not-found error (598).
     */
    suspend fun executeGatherStep(
        characterName: String,
        task: TaskType.EventGather,
        isEventActive: () -> Boolean,
        onStatus: (String) -> Unit,
        previousChar: com.artifactsmmo.client.models.Character? = null
    ): StepResult {
        if (!isEventActive()) return StepResult.EventExpired

        var char = previousChar ?: helper.refreshCharacter(characterName)

        char = helper.ensureToolEquipped(characterName, task.skill, existingChar = char)

        // Bank full inventory before gathering
        if (helper.isInventoryFull(char)) {
            onStatus("Inventory full, banking before event gather...")
            helper.bankDepositAll(characterName)
            return StepResult.Banked
        }

        // Build a MapInfo from the stored event coordinates
        val eventTarget = MapInfo(
            mapId = 0,
            name = "",
            skin = "",
            x = task.eventMapX,
            y = task.eventMapY,
            layer = task.eventMapLayer,
            access = MapAccess(type = "standard"),
            interactions = MapInteraction()
        )

        if (!helper.isAt(char, task.eventMapX, task.eventMapY) || char.layer != task.eventMapLayer) {
            onStatus("Moving to event: ${task.resourceName}...")
            char = helper.navigateWithTeleport(characterName, char, eventTarget)
        }

        onStatus("Gathering ${task.resourceName} (event)...")
        return try {
            val result = helper.gather(characterName)
            val items = result.details.items.map { it.code to it.quantity }
            StepResult.Gathered(result.details.xp, items, result.character)
        } catch (e: ArtifactsApiException) {
            when (e.errorCode) {
                486 -> StepResult.Waiting      // Cooldown
                598 -> StepResult.EventExpired  // Map content gone — event ended
                else -> throw e
            }
        }
    }

    /**
     * Execute the NPC event task as bank ⇄ NPC trips (inventory space is the limit):
     * 1. Bank the whole inventory (except non-gold buy currencies) to maximize space.
     * 2. Sell: each item's sell quantity is a "sell down to" floor — everything owned
     *    (bank + inventory) above it is sold; 0 sells all. Each trip withdraws as much as
     *    fits, walks to the NPC and sells it.
     * 3. Buy: each item's quantity is bought in inventory-sized chunks (capped by what's
     *    affordable — carried gold plus bank gold, withdrawn as needed), banking purchases between trips.
     * 4. Bank everything and return [StepResult.QuickTaskComplete], or
     *    [StepResult.EventExpired] if the NPC leaves mid-way.
     */
    suspend fun executeNpcStep(
        characterName: String,
        task: TaskType.EventNpc,
        isEventActive: () -> Boolean,
        onStatus: (String) -> Unit
    ): StepResult {
        if (!isEventActive()) return StepResult.EventExpired
        val npcTarget = MapInfo(
            mapId = 0, name = "", skin = "",
            x = task.eventMapX, y = task.eventMapY, layer = task.eventMapLayer,
            access = MapAccess(type = "standard"), interactions = MapInteraction()
        )
        return tradeWithNpc(
            characterName, task.npcCode, task.npcName, task.itemsToSell, task.itemsToBuy,
            target = npcTarget, travelLabel = "NPC event", isActive = isEventActive, onStatus = onStatus,
        )
    }

    /**
     * Buy from a permanent NPC (fixed map tile) with the same bank ⇄ NPC trips as
     * [executeNpcStep]. Fails up front if the NPC is unreachable (e.g. an achievement-locked
     * tile) or doesn't sell an item, and fails at the end if anything couldn't be bought.
     */
    suspend fun executeNpcBuyStep(
        characterName: String,
        task: TaskType.NpcBuy,
        onStatus: (String) -> Unit
    ): StepResult {
        val char = helper.refreshCharacter(characterName)
        val tile = helper.findNearest(char, "npc", task.npcCode)
            ?: return StepResult.Failed("${task.npcName} is not reachable (its map tile may require an achievement)")
        val catalog = runCatching { helper.contentCache.getNpcCatalog(task.npcCode) }.getOrDefault(emptyList())
        val notSold = task.itemsToBuy.filter { b -> catalog.none { it.code == b.code && it.buyPrice != null } }
        if (notSold.isNotEmpty()) {
            return StepResult.Failed("${task.npcName} doesn't sell ${notSold.joinToString { it.code }}")
        }
        val shortfalls = mutableListOf<String>()
        val result = tradeWithNpc(
            characterName, task.npcCode, task.npcName, emptyList(), task.itemsToBuy,
            target = tile, travelLabel = "NPC", isActive = { true }, onStatus = onStatus, shortfalls = shortfalls,
        )
        return when {
            result is StepResult.EventExpired -> StepResult.Failed("${task.npcName} is no longer at its tile")
            shortfalls.isNotEmpty() -> StepResult.Failed("Not everything was bought: ${shortfalls.joinToString("; ")}")
            else -> result
        }
    }

    /**
     * Shared sell/buy loop. [shortfalls] collects a reason for every buy that stopped early.
     * Returns [StepResult.EventExpired] if [isActive] turns false or the NPC disappears (598).
     */
    private suspend fun tradeWithNpc(
        characterName: String,
        npcCode: String,
        npcName: String,
        itemsToSell: List<SimpleItem>,
        itemsToBuy: List<SimpleItem>,
        target: MapInfo,
        travelLabel: String,
        isActive: () -> Boolean,
        onStatus: (String) -> Unit,
        shortfalls: MutableList<String> = mutableListOf(),
    ): StepResult {
        val isEventActive = isActive
        val npcItems = runCatching { helper.contentCache.getNpcCatalog(npcCode) }.getOrDefault(emptyList())
        val buyCurrencies = itemsToBuy.mapNotNull { b -> npcItems.firstOrNull { it.code == b.code }?.currency }
            .filter { it != "gold" }.toSet()

        suspend fun goToNpc(c: Character): Character =
            if (helper.isAt(c, target.x, target.y) && c.layer == target.layer) c
            else { onStatus("Moving to $travelLabel: $npcName..."); helper.navigateWithTeleport(characterName, c, target) }
        suspend fun finish(result: StepResult): StepResult {
            onStatus("Banking items after NPC trade...")
            runCatching { helper.depositInventoryExcept(characterName, emptySet()) }
            return result
        }

        var char = helper.depositInventoryExcept(characterName, buyCurrencies)

        // ── Sell ──
        runCatching { helper.bankState.refresh() }
        val toSell = itemsToSell.associate { sell ->
            val owned = helper.getBankItemQuantity(sell.code) + helper.getItemQuantity(char, sell.code)
            sell.code to sellDownTo(owned, sell.quantity)
        }.filterValues { it > 0 }.toMutableMap()
        var failedTrips = 0
        while (toSell.isNotEmpty()) {
            if (!isEventActive()) return finish(StepResult.EventExpired)
            char = helper.refreshCharacter(characterName)
            // Stock already carried (e.g. a previous trip's unsold leftovers) counts toward the batch.
            val carried = toSell.mapValues { (code, qty) -> minOf(qty, helper.getItemQuantity(char, code)) }
            // Keep room for an outbound + return teleport potion when there's space to spare.
            val batch = packBatch(toSell.mapValues { (code, qty) -> qty - carried.getValue(code) },
                withPotionRoom(freeQuantity(char)), withPotionRoom(freeSlots(char)))
            if (batch.isEmpty() && carried.values.all { it == 0 }) {
                if (++failedTrips > 2) break
                char = helper.depositInventoryExcept(characterName, buyCurrencies, char)
                continue
            }
            if (batch.isNotEmpty()) {
                onStatus("Withdrawing ${batch.joinToString { "${it.quantity}x ${it.code}" }} to sell...")
                val withdrawn = runCatching { helper.bankWithdrawItems(characterName, batch) }
                if (withdrawn.isFailure) {
                    // Bank snapshot was stale: re-read it and clamp what's left to sell.
                    onStatus("Withdraw failed (${withdrawn.exceptionOrNull()?.message}); re-checking bank...")
                    runCatching { helper.bankState.refresh() }
                    for (code in toSell.keys.toList()) {
                        toSell[code] = minOf(toSell.getValue(code), helper.getBankItemQuantity(code) + carried.getValue(code))
                    }
                    toSell.values.removeAll { it <= 0 }
                    if (++failedTrips > 2) break
                    continue
                }
            }
            char = goToNpc(helper.refreshCharacter(characterName))
            var soldAny = false
            for (code in toSell.keys.toList()) {
                val qty = minOf(toSell.getValue(code), helper.getItemQuantity(char, code))
                if (qty <= 0) continue
                onStatus("Selling ${qty}x $code to $npcName (${toSell.getValue(code) - qty} left after this)...")
                try {
                    char = helper.npcSell(characterName, code, qty)
                    soldAny = true
                    toSell[code] = toSell.getValue(code) - qty
                    if (toSell.getValue(code) <= 0) toSell.remove(code)
                } catch (e: ArtifactsApiException) {
                    if (e.errorCode == 598) return finish(StepResult.EventExpired)
                    onStatus("Could not sell $code: ${e.message} — skipping it")
                    toSell.remove(code)
                }
            }
            if (!soldAny && ++failedTrips > 2) break
            if (toSell.isNotEmpty()) char = helper.depositInventoryExcept(characterName, buyCurrencies, char)
        }

        // ── Buy ──
        for (buy in itemsToBuy) {
            val entry = npcItems.firstOrNull { it.code == buy.code && it.buyPrice != null }
            val price = entry?.buyPrice
            val currency = entry?.currency ?: "gold"
            var remaining = buy.quantity
            var stalls = 0
            var reason = "stopped"
            fun stop(msg: String) { reason = msg; onStatus(msg) }
            while (remaining > 0) {
                if (!isEventActive()) return finish(StepResult.EventExpired)
                char = helper.refreshCharacter(characterName)
                val currencyHeld = if (currency == "gold") 0 else helper.getItemQuantity(char, currency)
                val hasSlot = freeSlots(char) > 0 || helper.getItemQuantity(char, buy.code) > 0
                // Non-gold currency occupies inventory until spent, so leave room for it.
                val space = if (!hasSlot) 0
                    else if (currency == "gold" || price == null) freeQuantity(char)
                    else (freeQuantity(char) + currencyHeld) / (price + 1)
                val affordable = when {
                    price == null || price <= 0 -> remaining
                    // Bank gold only costs an API call when carried gold falls short.
                    currency == "gold" -> (char.gold + (if (char.gold >= remaining * price) 0
                        else runCatching { helper.getBankGold() }.getOrDefault(0))) / price
                    else -> (currencyHeld + helper.getBankItemQuantity(currency)) / price
                }
                val chunk = minOf(remaining, space, affordable)
                if (affordable <= 0) { stop("Can't afford more ${buy.code} ($remaining not bought)"); break }
                if (chunk <= 0) {
                    if (++stalls > 2) { stop("No inventory space to buy ${buy.code}"); break }
                    char = helper.depositInventoryExcept(characterName, buyCurrencies, char)
                    continue
                }
                if (currency != "gold" && price != null &&
                    !helper.ensureNpcCurrencyInInventory(characterName, currency, chunk * price)) {
                    stop("Not enough $currency to buy ${buy.code}"); break
                }
                if (currency == "gold" && price != null && char.gold < chunk * price) {
                    val deficit = chunk * price - char.gold
                    onStatus("Withdrawing $deficit gold from bank to buy ${buy.code}...")
                    try {
                        helper.bankWithdrawGold(characterName, deficit)
                    } catch (e: ArtifactsApiException) {
                        stop("Could not withdraw gold: ${e.message} ($remaining ${buy.code} not bought)"); break
                    }
                }
                char = goToNpc(helper.refreshCharacter(characterName))
                onStatus("Buying ${chunk}x ${buy.code} from $npcName (${remaining - chunk} left after this)...")
                try {
                    char = helper.npcBuyDirect(characterName, buy.code, chunk)
                    remaining -= chunk
                    stalls = 0
                } catch (e: ArtifactsApiException) {
                    if (e.errorCode == 598) return finish(StepResult.EventExpired)
                    stop("Could not buy ${buy.code}: ${e.message} — skipping it")
                    break
                }
                if (remaining > 0) char = helper.depositInventoryExcept(characterName, buyCurrencies, char)
            }
            if (remaining > 0) shortfalls += "$remaining ${buy.code} ($reason)"
        }

        return finish(StepResult.QuickTaskComplete)
    }

    private fun freeQuantity(char: Character): Int = (char.inventoryMaxItems - char.inventory.sumOf { it.quantity }).coerceAtLeast(0)
    private fun freeSlots(char: Character): Int = char.inventory.count { it.code.isEmpty() || it.quantity == 0 }
    private fun withPotionRoom(free: Int): Int = if (free > POTION_ROOM) free - POTION_ROOM else free

    companion object {
        /** Inventory slots/units left free on sell trips for teleport potions (outbound + return). */
        const val POTION_ROOM = 2

        /** How many to sell so that [keep] remain; a keep of 0 (or less) sells everything. */
        fun sellDownTo(owned: Int, keep: Int): Int = (owned - keep.coerceAtLeast(0)).coerceAtLeast(0)

        /**
         * Fill one inventory load from [wanted] (code → quantity still to move), in order,
         * bounded by [freeQuantity] items and [freeSlots] distinct codes.
         */
        fun packBatch(wanted: Map<String, Int>, freeQuantity: Int, freeSlots: Int): List<SimpleItem> {
            var room = freeQuantity
            var slots = freeSlots
            val out = mutableListOf<SimpleItem>()
            for ((code, qty) in wanted) {
                if (room <= 0 || slots <= 0) break
                if (qty <= 0) continue
                val take = minOf(qty, room)
                out += SimpleItem(code, take)
                room -= take
                slots--
            }
            return out
        }
    }

    /**
     * Execute a single iteration of the event fight loop.
     *
     * Does NOT delegate to [FightingExecutor.executeStep] because that method calls
     * [helper.findNearest] to locate the monster tile, which fails for event monsters —
     * the map cache is pre-warmed at startup and never reflects ephemeral event overlays.
     *
     * Instead, this method handles the full fight tick directly using the event tile
     * coordinates stored on the task, bypassing the map cache entirely.
     */
    suspend fun executeEventFightStep(
        characterName: String,
        task: TaskType.EventFight,
        isEventActive: () -> Boolean,
        onStatus: (String) -> Unit,
        previousChar: com.artifactsmmo.client.models.Character? = null
    ): StepResult {
        if (!isEventActive()) return StepResult.EventExpired

        var char = previousChar ?: helper.refreshCharacter(characterName)

        // Inventory full — bank before fighting
        if (helper.isInventoryFull(char)) {
            onStatus("Inventory full, banking before event fight...")
            helper.bankDepositAll(characterName)
            return StepResult.Banked
        }

        // Heal if HP is low
        if (!com.artifactsmmo.client.utils.CharacterUtils.hasEnoughHP(char, 0.90)) {
            // Delegate healing to FightingExecutor's existing handler via a minimal Fight task
            val fightTask = TaskType.Fight(
                monsterCode = task.monsterCode,
                monsterName = task.monsterName,
                dropStrategies = task.dropStrategies,
                defaultDropStrategy = task.defaultDropStrategy
            )
            return fightingExecutor.handleEventHealing(characterName, char, fightTask, onStatus)
        }

        char = fightingExecutor.refillPlannedUtilities(characterName, char, task.utilityActions, onStatus)

        // Navigate to event tile using stored coordinates — never uses map cache
        val eventTarget = com.artifactsmmo.client.models.MapInfo(
            mapId = 0, name = "", skin = "",
            x = task.eventMapX, y = task.eventMapY, layer = task.eventMapLayer,
            access = com.artifactsmmo.client.models.MapAccess(type = "standard"),
            interactions = com.artifactsmmo.client.models.MapInteraction()
        )
        if (!helper.isAt(char, task.eventMapX, task.eventMapY) || char.layer != task.eventMapLayer) {
            onStatus("Moving to event: ${task.monsterName}...")
            char = helper.navigateWithTeleport(characterName, char, eventTarget)
        }

        onStatus("Fighting ${task.monsterName} (event)... (HP: ${char.hp}/${char.maxHp})")
        return try {
            val result = helper.fight(characterName)
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
                onStatus("Lost fight against ${task.monsterName} (event)")
                StepResult.FightLost("Lost to ${task.monsterName}")
            }
        } catch (e: ArtifactsApiException) {
            when (e.errorCode) {
                486 -> StepResult.Waiting
                598 -> StepResult.EventExpired  // tile changed — event ended
                else -> throw e
            }
        }
    }
}
