package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.ToolAllocator
import com.artifactsmmo.core.task.ToolEffects
import com.artifactsmmo.core.task.ToolHolder
import com.artifactsmmo.core.task.ToolKitPlan

/** [ToolHolder] for [char]: its tool skill levels and the tools in its inventory and weapon slot. */
suspend fun toolHolderOf(char: Character, itemOf: suspend (String) -> Item?): ToolHolder {
    val tools = mutableMapOf<String, Int>()
    val held = char.inventory.filter { it.quantity > 0 && it.code.isNotEmpty() }.map { it.code to it.quantity } +
        listOfNotNull(char.weaponSlot.takeIf { it.isNotEmpty() }?.let { it to 1 })
    for ((code, qty) in held) {
        val item = itemOf(code) ?: continue
        if (ToolEffects.skillOf(item) != null) tools[code] = (tools[code] ?: 0) + qty
    }
    val levels = ToolEffects.TOOL_SKILLS.associateWith { CharacterUtils.getSkillLevel(char, it) ?: 0 }
    return ToolHolder(char.name, levels, tools, char.weaponSlot)
}

/**
 * `toolkit_sync`: one bank visit that banks the character's tools assigned to someone else (or
 * spare copies) and withdraws its assigned tools that are in the bank. Re-derives the actions
 * from the character's live state against the current plan, so a stale task is harmless.
 */
class ToolKitSyncExecutor(
    private val helper: ActionHelper,
    private val plan: () -> ToolKitPlan? = { helper.toolKitPlan },
) : TaskExecutor {

    override suspend fun step(ctx: ExecContext): StepOutcome {
        val p = plan() ?: return StepOutcome.Complete("Tool kit is disabled")
        val name = ctx.character
        val assigned = p.assignments[name] ?: return StepOutcome.Complete("$name is not in the tool kit plan yet")

        var char = helper.refreshCharacter(name)
        val itemOf: suspend (String) -> Item? = { helper.contentCache.getItemOrNull(it) }
        val holder = toolHolderOf(char, itemOf)
        val skills = holder.tools.keys.associateWith { code -> itemOf(code)?.let(ToolEffects::skillOf) }
        val actions = ToolAllocator.actionsFor(holder, assigned, p.skills) { skills[it] }
        val bank = helper.getBankSnapshot()
        val withdraw = actions.withdraw.filter { (bank[it] ?: 0) > 0 }
        if (actions.deposit.isEmpty() && withdraw.isEmpty()) return StepOutcome.Complete("Tool kit already in place")

        ctx.status("Syncing tool kit at the bank...")
        char = helper.navigateToBank(name, char)

        // The equipped copy has to come off first if the inventory copies don't cover the deposit.
        val weapon = holder.weapon
        val unequipped = weapon.isNotEmpty() &&
            (actions.deposit[weapon] ?: 0) > helper.getItemQuantity(char, weapon)
        if (unequipped) char = helper.unequip(name, "weapon")

        if (actions.deposit.isNotEmpty()) {
            char = helper.bankDepositItems(name, actions.deposit.map { (code, qty) -> SimpleItem(code, qty) })
        }

        val withdrawn = mutableListOf<String>()
        for (code in withdraw) {
            // One at a time: another character may have taken a copy since the plan was made.
            runCatching { char = helper.bankWithdrawItems(name, listOf(SimpleItem(code, 1))) }
                .onSuccess { withdrawn += code }
                .onFailure { rethrowCancellation(it); ctx.status("Could not withdraw $code: ${it.message}") }
        }

        if (unequipped) {
            skills[weapon]?.let { skill -> char = helper.ensureToolEquipped(name, skill, char) }
        }

        val parts = listOfNotNull(
            actions.deposit.takeIf { it.isNotEmpty() }?.let { d -> "banked " + d.entries.joinToString { "${it.value}x ${it.key}" } },
            withdrawn.takeIf { it.isNotEmpty() }?.let { "took " + it.joinToString() },
        )
        return StepOutcome.Complete("Tool kit synced: ${parts.joinToString("; ").ifEmpty { "nothing to do" }}")
    }
}
