package com.artifactsmmo.app.ui

import com.artifactsmmo.core.task.*
import com.artifactsmmo.client.models.Character
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.rendering.BorderType
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Interactive terminal UI for managing character tasks.
 * Uses Mordant for styled output and raw stdin for non-blocking input.
 */
class TerminalUI(
    private val taskManager: TaskManager,
    private val scope: CoroutineScope
) {
    private val terminal = Terminal()
    private val reader = BufferedReader(InputStreamReader(System.`in`))

    /**
     * Helper to create a table with ASCII borders for cross-platform compatibility.
     */
    private inline fun styledTable(crossinline block: com.github.ajalt.mordant.table.TableBuilder.() -> Unit) =
        table {
            borderType = BorderType.ASCII
            block()
        }

    // ── Main loop ──

    suspend fun run() {
        terminal.println(bold(cyan("=== ArtifactsMMO Task Runner ===")))
        terminal.println(gray("Type a command and press Enter. Type 'help' for available commands."))
        terminal.println()

        showDashboard()

        while (currentCoroutineContext().isActive) {
            terminal.print(bold(green("> ")))
            val input = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            val trimmed = input.trim().lowercase()

            when {
                trimmed.isEmpty() -> showDashboard()
                trimmed == "help" || trimmed == "h" -> showHelp()
                trimmed == "status" || trimmed == "s" -> showDashboard()
                trimmed == "quit" || trimmed == "q" -> {
                    terminal.println(yellow("Stopping all tasks..."))
                    taskManager.stopAll()
                    break
                }
                trimmed.startsWith("select ") || trimmed.startsWith("sel ") -> {
                    val arg = trimmed.substringAfter(" ").trim()
                    selectCharacter(arg)
                }
                trimmed.matches(Regex("\\d+")) -> {
                    selectCharacter(trimmed)
                }
                trimmed == "stop all" -> {
                    taskManager.stopAll()
                    terminal.println(yellow("All tasks stopped."))
                }
                trimmed == "assign all" || trimmed == "aa" -> {
                    val names = taskManager.getCharacterNames()
                    assignTaskToMultiple(names)
                }
                trimmed.startsWith("assign ") -> {
                    val args = trimmed.substringAfter("assign ").trim().split(Regex("\\s+"))
                    val names = taskManager.getCharacterNames()
                    val selected = args.mapNotNull { arg ->
                        val idx = arg.toIntOrNull()?.let { it - 1 }
                        when {
                            idx != null && idx in names.indices -> names[idx]
                            arg in names -> arg
                            else -> names.find { it.lowercase().startsWith(arg.lowercase()) }
                        }
                    }.distinct()
                    if (selected.isEmpty()) {
                        terminal.println(red("No valid characters matched. Use numbers from the dashboard or character names."))
                    } else {
                        assignTaskToMultiple(selected)
                    }
                }
                trimmed == "logs" || trimmed == "l" -> showLogs(null)
                trimmed.startsWith("logs ") -> {
                    val charArg = trimmed.substringAfter("logs ").trim()
                    showLogs(charArg)
                }
                else -> {
                    terminal.println(red("Unknown command: '$trimmed'. Type 'help' for commands."))
                }
            }
        }
    }

    // ── Help ──

    private fun showHelp() {
        terminal.println(bold("Commands:"))
        terminal.println("  ${cyan("status")} / ${cyan("s")} / ${cyan("Enter")}  - Show dashboard")
        terminal.println("  ${cyan("<number>")} / ${cyan("select <n>")}  - Select character by number")
        terminal.println("  ${cyan("assign all")} / ${cyan("aa")}        - Assign same task to all characters")
        terminal.println("  ${cyan("assign 1 3 5")}            - Assign same task to characters 1, 3, 5")
        terminal.println("  ${cyan("stop all")}                - Stop all tasks")
        terminal.println("  ${cyan("logs")} / ${cyan("l")}              - Show recent task logs")
        terminal.println("  ${cyan("logs <name>")}            - Show logs for a character")
        terminal.println("  ${cyan("quit")} / ${cyan("q")}              - Stop all and exit")
        terminal.println("  ${cyan("help")} / ${cyan("h")}              - Show this help")
        terminal.println()
    }

    // ── Dashboard ──

    private fun showDashboard() {
        val statuses = taskManager.getAllStatuses()
        terminal.println()
        terminal.println(bold(cyan("=== Character Dashboard ===")))
        terminal.println(
            styledTable {
                header {
                    row("#", "Character", "Lv", "Task", "Status", "Gathers", "Fights", "Crafts", "Banks")
                }
                body {
                    for ((i, s) in statuses.withIndex()) {
                        val task = s.task
                        val taskStr = when (task) {
                            is TaskType.Idle -> gray("IDLE")
                            is TaskType.Gather -> green("${task.skill}: ${task.resourceName}")
                            is TaskType.Fight -> red("Fight: ${task.monsterName}")
                            is TaskType.Craft -> {
                                val modeStr = if (task.mode == CraftMode.RECYCLE) "recycle" else {
                                    "${task.craftedSoFar}/${task.targetQuantity}"
                                }
                                yellow("${task.skill}: ${task.itemName} ($modeStr)")
                            }
                            is TaskType.TaskMaster -> {
                                val tasksStr = if (s.tasksCompleted > 0) " [${s.tasksCompleted} done]" else ""
                                magenta("Tasks: ${task.type}$tasksStr")
                            }
                            is TaskType.BankWithdraw     -> cyan("Withdraw: ${task.quantity}x ${task.itemName}")
                            is TaskType.BankRecycle      -> cyan("Recycle(bank): ${task.itemName}")
                            is TaskType.InventoryDeposit -> cyan("Deposit: ${task.quantity}x ${task.itemName}")
                            is TaskType.InventoryRecycle -> cyan("Recycle(inv): ${task.itemName}")
                        }
                        val craftsStr = if (s.recycleCount > 0) {
                            "${s.craftCount} (${s.recycleCount}r)"
                        } else {
                            "${s.craftCount}"
                        }
                        val statusStr = if (s.lastError != null) red(s.statusMessage) else s.statusMessage
                        row(
                            "${i + 1}",
                            bold(s.characterName),
                            "${s.characterLevel}",
                            taskStr,
                            statusStr.toString().take(40),
                            "${s.gatherCount}",
                            "${s.fightCount}",
                            craftsStr,
                            "${s.bankTrips}"
                        )
                    }
                }
            }
        )
        terminal.println(gray("Select a character by number, or type 'help'."))
        terminal.println()
    }

    // ── Character selection ──

    private suspend fun selectCharacter(arg: String) {
        val names = taskManager.getCharacterNames()
        val index = arg.toIntOrNull()?.let { it - 1 }

        // Try by number first, then by name
        val characterName = when {
            index != null && index in names.indices -> names[index]
            arg in names -> arg
            else -> {
                // Try partial name match
                names.find { it.lowercase().startsWith(arg.lowercase()) }
            }
        }

        if (characterName == null) {
            terminal.println(red("Character not found: '$arg'"))
            return
        }

        showCharacterMenu(characterName)
    }

    private suspend fun showCharacterMenu(characterName: String) {
        // Fetch fresh character data
        val char = try {
            taskManager.getCharacterDetails(characterName)
        } catch (e: Exception) {
            terminal.println(red("Failed to fetch character data: ${e.message}"))
            return
        }

        val status = taskManager.getStatus(characterName) ?: return

        terminal.println()
        terminal.println(bold(cyan("=== ${char.name} ===")))
        terminal.println(
            styledTable {
                body {
                    row("Level", "${char.level} (${char.xp}/${char.maxXp} XP)")
                    row("HP", "${char.hp}/${char.maxHp}")
                    row("Gold", "${char.gold}")
                    row("Position", "(${char.x}, ${char.y}) [${char.layer}]")
                    row("Current Task", describeTask(status.task))
                }
            }
        )

        // Skills
        terminal.println(bold("Skills:"))
        terminal.println(
            styledTable {
                header { row("Skill", "Level", "XP") }
                body {
                    row("Mining", "${char.miningLevel}", "${char.miningXp}/${char.miningMaxXp}")
                    row("Woodcutting", "${char.woodcuttingLevel}", "${char.woodcuttingXp}/${char.woodcuttingMaxXp}")
                    row("Fishing", "${char.fishingLevel}", "${char.fishingXp}/${char.fishingMaxXp}")
                    row("Weaponcrafting", "${char.weaponcraftingLevel}", "${char.weaponcraftingXp}/${char.weaponcraftingMaxXp}")
                    row("Gearcrafting", "${char.gearcraftingLevel}", "${char.gearcraftingXp}/${char.gearcraftingMaxXp}")
                    row("Jewelrycrafting", "${char.jewelrycraftingLevel}", "${char.jewelrycraftingXp}/${char.jewelrycraftingMaxXp}")
                    row("Cooking", "${char.cookingLevel}", "${char.cookingXp}/${char.cookingMaxXp}")
                    row("Alchemy", "${char.alchemyLevel}", "${char.alchemyXp}/${char.alchemyMaxXp}")
                }
            }
        )

        // Inventory
        if (char.inventory.isNotEmpty()) {
            terminal.println(bold("Inventory (${char.inventory.sumOf { it.quantity }}/${char.inventoryMaxItems}):"))
            terminal.println(
                styledTable {
                    header { row("Item", "Qty") }
                    body {
                        for (slot in char.inventory.sortedBy { it.code }) {
                            row(slot.code, "${slot.quantity}")
                        }
                    }
                }
            )
        } else {
            terminal.println(gray("Inventory: empty"))
        }

        // Equipment
        terminal.println(bold("Equipment:"))
        terminal.println("  Weapon: ${char.weaponSlot.ifEmpty { gray("none").toString() }}")
        terminal.println("  Shield: ${char.shieldSlot.ifEmpty { gray("none").toString() }}")
        terminal.println("  Helmet: ${char.helmetSlot.ifEmpty { gray("none").toString() }}")
        terminal.println("  Body: ${char.bodyArmorSlot.ifEmpty { gray("none").toString() }}")
        terminal.println("  Legs: ${char.legArmorSlot.ifEmpty { gray("none").toString() }}")
        terminal.println("  Boots: ${char.bootsSlot.ifEmpty { gray("none").toString() }}")
        terminal.println()

        // Actions
        terminal.println(bold("Actions:"))
        terminal.println("  ${cyan("1")} - Assign gathering task")
        terminal.println("  ${cyan("2")} - Assign fighting task")
        terminal.println("  ${cyan("3")} - Assign crafting task")
        terminal.println("  ${cyan("4")} - Assign task master")
        terminal.println("  ${cyan("5")} - Stop current task")
        terminal.println("  ${cyan("b")} - Back to dashboard")
        terminal.println()

        terminal.print(bold(green("> ")))
        val input = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return

        when (input) {
            "1" -> assignGatheringTask(characterName, char)
            "2" -> assignFightingTask(characterName, char)
            "3" -> assignCraftingTask(characterName)
            "4" -> assignTaskMasterTask(characterName)
            "5" -> {
                taskManager.stopTask(characterName)
                terminal.println(yellow("Task stopped for ${characterName}."))
            }
            "b", "" -> { showDashboard(); return }
            else -> terminal.println(red("Invalid choice."))
        }
    }

    // ── Logs ──

    private fun showLogs(charFilter: String?) {
        val resolvedName = if (charFilter != null) {
            val names = taskManager.getCharacterNames()
            names.find { it.equals(charFilter, ignoreCase = true) }
                ?: names.find { it.lowercase().startsWith(charFilter.lowercase()) }
                ?: charFilter // pass through, logger will just filter by it
        } else null

        val entries = taskManager.logger.getRecent(50, resolvedName)
        if (entries.isEmpty()) {
            terminal.println(gray("No log entries${if (resolvedName != null) " for '$resolvedName'" else ""}."))
            return
        }

        terminal.println()
        terminal.println(bold(cyan("=== Task Logs${if (resolvedName != null) " ($resolvedName)" else ""} ===")))
        for (entry in entries) {
            val color = if (entry.message.contains("Error", ignoreCase = true) || entry.message.contains("Lost")) red else gray
            terminal.println(color(entry.formatted()))
        }
        terminal.println()
    }

    // ── Multi-character task assignment ──

    private suspend fun assignTaskToMultiple(characterNames: List<String>) {
        terminal.println()
        terminal.println(bold("Assigning task to: ${characterNames.joinToString(", ")}"))
        terminal.println()
        terminal.println(bold("Select task type:"))
        terminal.println("  ${cyan("1")} - Gathering")
        terminal.println("  ${cyan("2")} - Fighting")
        terminal.println("  ${cyan("3")} - Crafting")
        terminal.println("  ${cyan("4")} - Task Master")
        terminal.println("  ${cyan("b")} - Back")
        terminal.println()

        terminal.print(bold(green("> ")))
        val input = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return

        val task: TaskType? = when (input) {
            "1" -> buildGatheringTask(characterNames.first())
            "2" -> buildFightingTask(characterNames.first())
            "3" -> buildCraftingTask(characterNames.first())
            "4" -> buildTaskMasterTask()
            "b" -> null
            else -> { terminal.println(red("Invalid choice.")); null }
        }

        if (task != null) {
            for (name in characterNames) {
                taskManager.assignTask(name, task)
            }
            terminal.println(green("Assigned ${characterNames.size} character(s) to ${describeTask(task)}."))
            terminal.println()
        }
    }

    /**
     * Build a Gather TaskType through the interactive menu. Returns null if cancelled.
     */
    private suspend fun buildGatheringTask(referenceCharacter: String): TaskType.Gather? {
        val gatherSkills = listOf("mining", "woodcutting", "fishing", "alchemy")

        terminal.println()
        terminal.println(bold("Select gathering skill:"))
        for ((i, skill) in gatherSkills.withIndex()) {
            terminal.println("  ${cyan("${i + 1}")} - ${skill.replaceFirstChar { it.uppercase() }}")
        }
        terminal.println("  ${cyan("b")} - Back")
        terminal.println()

        terminal.print(bold(green("> ")))
        val skillInput = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null

        if (skillInput == "b") return null
        val skillIndex = skillInput.toIntOrNull()?.let { it - 1 }
        if (skillIndex == null || skillIndex !in gatherSkills.indices) {
            terminal.println(red("Invalid choice."))
            return null
        }

        val skill = gatherSkills[skillIndex]

        // Fetch available resources using the reference character's level
        terminal.println(gray("Fetching available resources..."))
        val resources = try {
            taskManager.getAvailableResources(referenceCharacter, skill)
        } catch (e: Exception) {
            terminal.println(red("Failed to fetch resources: ${e.message}"))
            return null
        }

        if (resources.isEmpty()) {
            terminal.println(yellow("No resources available for $skill."))
            return null
        }

        terminal.println()
        terminal.println(bold("Available resources for ${skill.replaceFirstChar { it.uppercase() }}:"))
        for ((i, res) in resources.withIndex()) {
            val drops = res.drops.joinToString(", ") { it.code }
            terminal.println("  ${cyan("${i + 1}")} - ${res.name} (Lv.${res.level}) [drops: $drops]")
        }
        terminal.println("  ${cyan("b")} - Back")
        terminal.println()

        terminal.print(bold(green("> ")))
        val resInput = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null

        if (resInput == "b") return null
        val resIndex = resInput.toIntOrNull()?.let { it - 1 }
        if (resIndex == null || resIndex !in resources.indices) {
            terminal.println(red("Invalid choice."))
            return null
        }

        val resource = resources[resIndex]

        // Ask about inventory strategy
        terminal.println()
        terminal.println(bold("When inventory is full:"))
        terminal.println("  ${cyan("1")} - Bank only (deposit all items)")
        terminal.println()

        terminal.print(bold(green("> ")))
        withContext(Dispatchers.IO) { reader.readLine() }?.trim() ?: return null

        return TaskType.Gather(
            skill = skill,
            resourceCode = resource.code,
            resourceName = resource.name
        )
    }

    /**
     * Build a Fight TaskType through the interactive menu. Returns null if cancelled.
     */
    private suspend fun buildFightingTask(referenceCharacter: String): TaskType.Fight? {
        terminal.println(gray("Fetching available monsters..."))
        val monsters = try {
            taskManager.getAvailableMonsters(referenceCharacter)
        } catch (e: Exception) {
            terminal.println(red("Failed to fetch monsters: ${e.message}"))
            return null
        }

        if (monsters.isEmpty()) {
            terminal.println(yellow("No monsters found."))
            return null
        }

        while (true) {
            terminal.println()
            terminal.println(bold("Available monsters:"))
            for ((i, mon) in monsters.withIndex()) {
                val drops = mon.drops.take(3).joinToString(", ") { it.code }
                val moreDrops = if (mon.drops.size > 3) " ..." else ""
                terminal.println("  ${cyan("${i + 1}")} - ${mon.name} (Lv.${mon.level}, HP:${mon.hp}) [drops: $drops$moreDrops]")
            }
            terminal.println("  ${cyan("b")} - Back")
            terminal.println()

            terminal.print(bold(green("> ")))
            val input = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null

            if (input == "b") return null
            val index = input.toIntOrNull()?.let { it - 1 }
            if (index == null || index !in monsters.indices) {
                terminal.println(red("Invalid choice."))
                continue
            }

            val monster = monsters[index]

            // Run combat simulation
            terminal.println(gray("Simulating combat vs ${monster.name} (100 iterations)..."))
            val simResult = try {
                taskManager.simulateFight(referenceCharacter, monster.code, 100)
            } catch (e: Exception) {
                terminal.println(yellow("Simulation failed: ${e.message}"))
                terminal.println(yellow("Proceeding without simulation data."))
                // Allow confirming without simulation
                terminal.println()
                terminal.println("  ${cyan("y")} - Confirm ${monster.name}")
                terminal.println("  ${cyan("n")} - Pick another monster")
                terminal.println("  ${cyan("b")} - Back")
                terminal.print(bold(green("> ")))
                val confirm = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null
                when (confirm) {
                    "y" -> return TaskType.Fight(monsterCode = monster.code, monsterName = monster.name)
                    "b" -> return null
                    else -> continue
                }
            }

            // Display simulation results
            terminal.println()
            val winrate = simResult.winrate
            val winColor = when {
                winrate >= 90.0 -> green
                else -> red
            }
            terminal.println(bold("Combat Simulation: ${monster.name} (Lv.${monster.level})"))
            terminal.println("  Win rate: ${winColor("${"%.0f".format(winrate)}% (${simResult.wins}/${simResult.wins + simResult.losses})")}")

            // Show avg turns for wins and losses
            val winTurns = simResult.results.filter { it.result == "win" }
            val lossTurns = simResult.results.filter { it.result == "loss" }
            if (winTurns.isNotEmpty()) {
                terminal.println("  Avg turns (wins): ${gray("%.1f".format(winTurns.map { it.turns }.average()))}")
            }
            if (lossTurns.isNotEmpty()) {
                terminal.println("  Avg turns (losses): ${gray("%.1f".format(lossTurns.map { it.turns }.average()))}")
            }

            // Show monster elemental info for context
            val attacks = listOfNotNull(
                if (monster.attackFire > 0) "Fire:${monster.attackFire}" else null,
                if (monster.attackEarth > 0) "Earth:${monster.attackEarth}" else null,
                if (monster.attackWater > 0) "Water:${monster.attackWater}" else null,
                if (monster.attackAir > 0) "Air:${monster.attackAir}" else null
            )
            val resists = listOfNotNull(
                if (monster.resFire != 0) "Fire:${monster.resFire}%" else null,
                if (monster.resEarth != 0) "Earth:${monster.resEarth}%" else null,
                if (monster.resWater != 0) "Water:${monster.resWater}%" else null,
                if (monster.resAir != 0) "Air:${monster.resAir}%" else null
            )
            if (attacks.isNotEmpty()) terminal.println("  Monster attacks: ${gray(attacks.joinToString(", "))}")
            if (resists.isNotEmpty()) terminal.println("  Monster resists: ${gray(resists.joinToString(", "))}")
            if (monster.effects.isNotEmpty()) {
                terminal.println("  Monster effects: ${gray(monster.effects.joinToString(", ") { "${it.code}(${it.value})" })}")
            }

            terminal.println()
            terminal.println("  ${cyan("y")} - Confirm ${monster.name}")
            terminal.println("  ${cyan("n")} - Pick another monster")
            terminal.println("  ${cyan("b")} - Back")
            terminal.print(bold(green("> ")))
            val confirm = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null
            when (confirm) {
                "y" -> return TaskType.Fight(monsterCode = monster.code, monsterName = monster.name)
                "b" -> return null
                else -> continue // Loop back to monster list
            }
        }
    }

    /**
     * Build a Craft TaskType through the interactive menu. Returns null if cancelled.
     */
    private suspend fun buildCraftingTask(referenceCharacter: String): TaskType.Craft? {
        val craftSkills = listOf("weaponcrafting", "gearcrafting", "jewelrycrafting", "other")

        terminal.println()
        terminal.println(bold("Select crafting category:"))
        terminal.println("  ${cyan("1")} - Weaponcrafting")
        terminal.println("  ${cyan("2")} - Gearcrafting")
        terminal.println("  ${cyan("3")} - Jewelrycrafting")
        terminal.println("  ${cyan("4")} - Other (cooking, refining, etc.)")
        terminal.println("  ${cyan("b")} - Back")
        terminal.println()

        terminal.print(bold(green("> ")))
        val catInput = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null

        if (catInput == "b") return null
        val catIndex = catInput.toIntOrNull()?.let { it - 1 }
        if (catIndex == null || catIndex !in craftSkills.indices) {
            terminal.println(red("Invalid choice."))
            return null
        }

        val isOther = catIndex == 3
        val selectedSkill = craftSkills[catIndex] // "weaponcrafting", "gearcrafting", "jewelrycrafting", or "other"

        // Fetch available craftable items from bank + inventory
        terminal.println(gray("Checking available materials..."))
        val craftableItems = try {
            if (isOther) {
                taskManager.getAvailableMiscCraftingItems(referenceCharacter)
            } else {
                taskManager.getAvailableCraftingItems(referenceCharacter, selectedSkill)
            }
        } catch (e: Exception) {
            terminal.println(red("Failed to fetch craftable items: ${e.message}"))
            return null
        }

        if (craftableItems.isEmpty()) {
            terminal.println(yellow("No items can be crafted with the available materials."))
            return null
        }

        // Display craftable items
        terminal.println()
        if (isOther) {
            terminal.println(bold("Available items to craft (misc):"))
        } else {
            terminal.println(bold("Available ${selectedSkill} items:"))
        }

        for ((i, info) in craftableItems.withIndex()) {
            val craftLevel = info.item.craft?.level ?: 0
            val ingredients = info.ingredients.joinToString(", ") { "${it.quantity}x ${it.code}" }
            val skillLabel = if (isOther) " [${info.item.craft?.skill ?: "?"}]" else ""
            terminal.println("  ${cyan("${i + 1}")} - ${info.item.name} (Lv.$craftLevel) — max ${info.maxCraftable}$skillLabel [$ingredients]")
        }
        terminal.println("  ${cyan("b")} - Back")
        terminal.println()

        terminal.print(bold(green("> ")))
        val itemInput = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null

        if (itemInput == "b") return null
        val itemIndex = itemInput.toIntOrNull()?.let { it - 1 }
        if (itemIndex == null || itemIndex !in craftableItems.indices) {
            terminal.println(red("Invalid choice."))
            return null
        }

        val selectedItem = craftableItems[itemIndex]

        // Determine the actual craft skill for the item (important for "other" category)
        val actualSkill = if (isOther) {
            selectedItem.item.craft?.skill ?: "cooking"
        } else {
            selectedSkill
        }

        // Ask quantity (all categories)
        terminal.println()
        terminal.println(bold("How many to craft? (max ${selectedItem.maxCraftable})"))
        terminal.print(bold(green("> ")))
        val qtyInput = withContext(Dispatchers.IO) { reader.readLine() }?.trim() ?: return null

        if (qtyInput.lowercase() == "b") return null
        val qty = qtyInput.toIntOrNull()
        if (qty == null || qty <= 0) {
            terminal.println(red("Invalid quantity."))
            return null
        }
        val targetQuantity = minOf(qty, selectedItem.maxCraftable)
        if (targetQuantity < qty) {
            terminal.println(yellow("Capped at $targetQuantity (max available from materials)."))
        }

        // For weaponcrafting/gearcrafting/jewelrycrafting: ask Bank or Recycle
        // For "other": always bank (no recycling misc items)
        val mode: CraftMode
        if (isOther) {
            mode = CraftMode.BANK
        } else {
            terminal.println()
            terminal.println(bold("What to do with crafted items?"))
            terminal.println("  ${cyan("1")} - Bank (deposit crafted items)")
            terminal.println("  ${cyan("2")} - Recycle (craft & recycle for XP)")
            terminal.println("  ${cyan("b")} - Back")
            terminal.println()

            terminal.print(bold(green("> ")))
            val modeInput = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null

            mode = when (modeInput) {
                "1" -> CraftMode.BANK
                "2" -> CraftMode.RECYCLE
                "b" -> return null
                else -> { terminal.println(red("Invalid choice.")); return null }
            }
        }

        return TaskType.Craft(
            skill = actualSkill,
            itemCode = selectedItem.item.code,
            itemName = selectedItem.item.name,
            mode = mode,
            targetQuantity = targetQuantity,
            craftedSoFar = 0
        )
    }

    // ── Single-character task assignments ──

    private suspend fun assignGatheringTask(characterName: String, char: Character) {
        val task = buildGatheringTask(characterName) ?: return
        taskManager.assignTask(characterName, task)
        terminal.println(green("Assigned ${characterName} to gather ${task.resourceName} (${task.skill})."))
        terminal.println()
    }

    private suspend fun assignFightingTask(characterName: String, char: Character) {
        val task = buildFightingTask(characterName) ?: return
        taskManager.assignTask(characterName, task)
        terminal.println(green("Assigned ${characterName} to fight ${task.monsterName}."))
        terminal.println()
    }

    private suspend fun assignCraftingTask(characterName: String) {
        val task = buildCraftingTask(characterName) ?: return
        taskManager.assignTask(characterName, task)
        val modeStr = if (task.mode == CraftMode.RECYCLE) "recycle" else "craft ${task.targetQuantity}x"
        terminal.println(green("Assigned ${characterName} to $modeStr ${task.itemName} (${task.skill})."))
        terminal.println()
    }

    private suspend fun assignTaskMasterTask(characterName: String) {
        val task = buildTaskMasterTask(characterName) ?: return
        taskManager.assignTask(characterName, task)
        terminal.println(green("Assigned ${characterName} to task master (${task.type})."))
        terminal.println()
    }

    /**
     * Build a TaskMaster TaskType through the interactive menu. Returns null if cancelled.
     * If the character already has an active task master task, offers a resume option.
     */
    private suspend fun buildTaskMasterTask(characterName: String? = null): TaskType.TaskMaster? {
        // Check if the character already has an active task from a task master
        var existingTaskType: String? = null
        var existingTaskDesc: String? = null
        if (characterName != null) {
            try {
                val char = taskManager.getCharacterDetails(characterName)
                if (char.task.isNotEmpty()) {
                    existingTaskType = char.taskType
                    existingTaskDesc = "${char.taskProgress}/${char.taskTotal} ${char.task} (${char.taskType})"
                }
            } catch (_: Exception) {}
        }

        terminal.println()
        terminal.println(bold("Select task master type:"))
        terminal.println("  ${cyan("1")} - Items (gather/craft items)")
        terminal.println("  ${cyan("2")} - Monsters (kill monsters)")
        if (existingTaskType != null) {
            terminal.println("  ${cyan("3")} - Resume current task: ${yellow(existingTaskDesc!!)}")
        }
        terminal.println("  ${cyan("b")} - Back")
        terminal.println()

        terminal.print(bold(green("> ")))
        val input = withContext(Dispatchers.IO) { reader.readLine() }?.trim()?.lowercase() ?: return null

        return when (input) {
            "1" -> TaskType.TaskMaster(type = "items")
            "2" -> TaskType.TaskMaster(type = "monsters")
            "3" -> if (existingTaskType != null) {
                TaskType.TaskMaster(type = existingTaskType)
            } else {
                terminal.println(red("Invalid choice.")); null
            }
            "b" -> null
            else -> { terminal.println(red("Invalid choice.")); null }
        }
    }

    // ── Helpers ──

    private fun describeTask(task: TaskType): String {
        return when (task) {
            is TaskType.Idle -> "IDLE"
            is TaskType.Gather -> "Gathering ${task.resourceName} (${task.skill})"
            is TaskType.Fight -> "Fighting ${task.monsterName}"
            is TaskType.Craft -> {
                val modeStr = if (task.mode == CraftMode.RECYCLE) "recycle" else "${task.craftedSoFar}/${task.targetQuantity}"
                "Crafting ${task.itemName} ($modeStr) [${task.skill}]"
            }
            is TaskType.TaskMaster       -> "Task Master (${task.type})"
            is TaskType.BankWithdraw     -> "Withdraw ${task.quantity}x ${task.itemName} from bank"
            is TaskType.BankRecycle      -> "Recycle ${task.quantity}x ${task.itemName} (bank)"
            is TaskType.InventoryDeposit -> "Deposit ${task.quantity}x ${task.itemName} to bank"
            is TaskType.InventoryRecycle -> "Recycle ${task.quantity}x ${task.itemName} (inventory)"
        }
    }
}
