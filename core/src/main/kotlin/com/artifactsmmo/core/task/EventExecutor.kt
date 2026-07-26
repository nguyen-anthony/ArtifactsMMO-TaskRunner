package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsApiException
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
 * EventNpc: one-shot sell/buy sequence at an ephemeral NPC event tile, then reverts.
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
            char = helper.navigateToTile(characterName, eventTarget)
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
     * Execute the one-shot NPC event task:
     * 1. Withdraw ALL available quantity of each sell item from the bank.
     * 2. Navigate to the NPC event tile.
     * 3. Sell everything in inventory for each configured sell item.
     * 4. Buy items (quantity from config).
     * 5. Bank purchased items.
     * 6. Return [StepResult.QuickTaskComplete].
     *
     * Sell quantities in the task are ignored — the design intent is "sell everything
     * available". Bank is drained first so the full stack is present at the NPC.
     */
    suspend fun executeNpcStep(
        characterName: String,
        task: TaskType.EventNpc,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)

        // Withdraw all available bank stock for each sell item
        val itemsToWithdraw = mutableListOf<SimpleItem>()
        for (sell in task.itemsToSell) {
            val inBank = helper.getBankItemQuantity(sell.code)
            if (inBank > 0) {
                itemsToWithdraw.add(SimpleItem(sell.code, inBank))
            }
        }
        if (itemsToWithdraw.isNotEmpty()) {
            onStatus("Withdrawing sell items from bank for NPC event...")
            helper.bankWithdrawItems(characterName, itemsToWithdraw)
            char = helper.refreshCharacter(characterName)
        }

        // Navigate to NPC event tile
        val npcTarget = MapInfo(
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
            onStatus("Moving to NPC event: ${task.npcName}...")
            helper.navigateToTile(characterName, npcTarget)
            char = helper.refreshCharacter(characterName)
        }

        // Sell — sell everything currently in inventory for each configured item
        for (sell in task.itemsToSell) {
            val qty = helper.getItemQuantity(char, sell.code)
            if (qty > 0) {
                onStatus("Selling ${qty}x ${sell.code} to ${task.npcName}...")
                char = helper.npcSell(characterName, sell.code, qty)
            }
        }

        // Buy items (quantity is the configured per-event buy amount)
        for (buy in task.itemsToBuy) {
            if (buy.quantity > 0) {
                onStatus("Buying ${buy.quantity}x ${buy.code} from ${task.npcName}...")
                char = helper.npcBuyDirect(characterName, buy.code, buy.quantity)
            }
        }

        // Bank any purchased items or leftover inventory
        val refreshed = helper.refreshCharacter(characterName)
        val totalItems = refreshed.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking items after NPC event...")
            helper.bankDepositAll(characterName)
        }

        return StepResult.QuickTaskComplete
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

        // Navigate to event tile using stored coordinates — never uses map cache
        val eventTarget = com.artifactsmmo.client.models.MapInfo(
            mapId = 0, name = "", skin = "",
            x = task.eventMapX, y = task.eventMapY, layer = task.eventMapLayer,
            access = com.artifactsmmo.client.models.MapAccess(type = "standard"),
            interactions = com.artifactsmmo.client.models.MapInteraction()
        )
        if (!helper.isAt(char, task.eventMapX, task.eventMapY) || char.layer != task.eventMapLayer) {
            onStatus("Moving to event: ${task.monsterName}...")
            char = helper.navigateToTile(characterName, eventTarget)
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
