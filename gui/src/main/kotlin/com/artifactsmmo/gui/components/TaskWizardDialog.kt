package com.artifactsmmo.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artifactsmmo.client.models.CombatSimulationData
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.CraftMode
import com.artifactsmmo.core.task.FullInventoryStrategy
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.gui.state.AppState
import kotlinx.coroutines.launch

// ── Wizard steps ───────────────────────────────────────────────────────────────

private sealed class WizardStep {
    data object SelectType : WizardStep()

    // Gather
    data object GatherSkill : WizardStep()
    data class GatherResource(val skill: String, val resources: List<Resource>) : WizardStep()
    data class GatherStrategy(
        val skill: String,
        val resource: Resource,
        val resources: List<Resource>
    ) : WizardStep()

    // Fight
    data class FightMonster(val monsters: List<Monster>) : WizardStep()
    data class FightSim(
        val monsters: List<Monster>,
        val monster: Monster,
        val sim: CombatSimulationData?,
        val simError: String?
    ) : WizardStep()

    // Equipment Browser (from FightSim → Check Equipment)
    data class EquipmentBrowser(
        val monsters: List<Monster>,
        val monster: Monster,
        val originalSim: CombatSimulationData?,
        val char: Character,
        /** slot → item code selected by user (overrides current gear) */
        val slotOverrides: Map<String, String> = emptyMap(),
        /** Tracks source ("inventory"/"bank"/"craftable") per slot, used for EquipActions */
        val slotSources: Map<String, String> = emptyMap()
    ) : WizardStep()
    data class EquipmentSlotPicker(
        val monsters: List<Monster>,
        val monster: Monster,
        val originalSim: CombatSimulationData?,
        val char: Character,
        val slotOverrides: Map<String, String>,
        val slotSources: Map<String, String>,
        val slotInfo: ActionHelper.SlotInfo,
        val options: List<ActionHelper.EquipmentOption>
    ) : WizardStep()
    data class EquipmentResim(
        val monsters: List<Monster>,
        val monster: Monster,
        val originalSim: CombatSimulationData?,
        val char: Character,
        val newSim: CombatSimulationData,
        val slotOverrides: Map<String, String>,
        val equipActions: List<ActionHelper.EquipAction>
    ) : WizardStep()

    // Craft
    data object CraftCategory : WizardStep()
    data class CraftModeStep(val category: String, val isOther: Boolean) : WizardStep()
    data class CraftItem(
        val category: String,
        val isOther: Boolean,
        val mode: CraftMode,
        val items: List<ActionHelper.CraftableItemInfo>
    ) : WizardStep()
    data class CraftQty(
        val category: String,
        val isOther: Boolean,
        val mode: CraftMode,
        val info: ActionHelper.CraftableItemInfo,
        val allItems: List<ActionHelper.CraftableItemInfo>
    ) : WizardStep()

    // Task Master
    data class TaskMasterTypeStep(
        val existingType: String?,
        val existingDesc: String?
    ) : WizardStep()
}

// ── Main dialog ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskWizardDialog(
    characterNames: List<String>,
    appState: AppState,
    onDismiss: () -> Unit
) {
    val characterName = characterNames.first()   // used for data-loading calls
    var step by remember { mutableStateOf<WizardStep>(WizardStep.SelectType) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(message: String, block: suspend () -> Unit) {
        error = null
        isLoading = true
        loadingMessage = message
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            } finally {
                isLoading = false
            }
        }
    }

    fun assign(task: TaskType) {
        for (name in characterNames) appState.taskManager.assignTask(name, task)
        onDismiss()
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(520.dp).heightIn(min = 200.dp, max = 640.dp),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp
        ) {
            Box {
                Column(modifier = Modifier.fillMaxWidth()) {

                    // ── Title bar ─────────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (characterNames.size == 1)
                                "Assign Task — ${characterNames.first()}"
                            else
                                "Assign Task — ${characterNames.size} characters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("✕")
                        }
                    }

                    HorizontalDivider()

                    // ── Error banner ──────────────────────────────────────────
                    val err = error
                    if (err != null) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { error = null }) {
                                    Text("✕", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }

                    // ── Step content ──────────────────────────────────────────
                    when (val s = step) {
                        is WizardStep.SelectType -> StepSelectType(
                            onGather = { step = WizardStep.GatherSkill },
                            onFight = {
                                load("Loading monsters...") {
                                    val monsters = appState.taskManager.getAvailableMonsters(characterName)
                                    step = WizardStep.FightMonster(monsters)
                                }
                            },
                            onCraft = { step = WizardStep.CraftCategory },
                            onTaskMaster = {
                                load("Checking active tasks...") {
                                    val char = appState.taskManager.getCharacterDetails(characterName)
                                    val existingType = if (char.task.isNotEmpty()) char.taskType else null
                                    val existingDesc = if (char.task.isNotEmpty())
                                        "${char.taskProgress}/${char.taskTotal} ${char.task} (${char.taskType})"
                                    else null
                                    step = WizardStep.TaskMasterTypeStep(existingType, existingDesc)
                                }
                            }
                        )

                        is WizardStep.GatherSkill -> StepGatherSkill(
                            onBack = { step = WizardStep.SelectType },
                            onSkillSelected = { skill ->
                                load("Loading $skill resources...") {
                                    val resources = appState.taskManager.getAvailableResources(characterName, skill)
                                    step = WizardStep.GatherResource(skill, resources)
                                }
                            }
                        )

                        is WizardStep.GatherResource -> StepGatherResource(
                            step = s,
                            onBack = { step = WizardStep.GatherSkill },
                            onResourceSelected = { resource ->
                                step = WizardStep.GatherStrategy(s.skill, resource, s.resources)
                            }
                        )

                        is WizardStep.GatherStrategy -> StepGatherStrategy(
                            step = s,
                            onBack = { step = WizardStep.GatherResource(s.skill, s.resources) },
                            onConfirm = { strategy ->
                                assign(
                                    TaskType.Gather(
                                        skill = s.skill,
                                        resourceCode = s.resource.code,
                                        resourceName = s.resource.name,
                                        onFullInventory = strategy
                                    )
                                )
                            }
                        )

                        is WizardStep.FightMonster -> StepFightMonster(
                            step = s,
                            onBack = { step = WizardStep.SelectType },
                            onMonsterSelected = { monster ->
                                load("Simulating combat vs ${monster.name}...") {
                                    val simResult = try {
                                        appState.taskManager.simulateFight(characterName, monster.code, 20)
                                    } catch (e: Exception) {
                                        step = WizardStep.FightSim(s.monsters, monster, null, e.message)
                                        return@load
                                    }
                                    step = WizardStep.FightSim(s.monsters, monster, simResult, null)
                                }
                            }
                        )

                        is WizardStep.FightSim -> StepFightSim(
                            step = s,
                            onBack = { step = WizardStep.FightMonster(s.monsters) },
                            onConfirm = {
                                assign(
                                    TaskType.Fight(
                                        monsterCode = s.monster.code,
                                        monsterName = s.monster.name
                                    )
                                )
                            },
                            onCheckEquipment = {
                                load("Loading character equipment...") {
                                    val char = appState.taskManager.getCharacterDetails(characterName)
                                    step = WizardStep.EquipmentBrowser(s.monsters, s.monster, s.sim, char)
                                }
                            }
                        )

                        is WizardStep.EquipmentBrowser -> StepEquipmentBrowser(
                            step = s,
                            onBack = {
                                step = WizardStep.FightSim(s.monsters, s.monster, s.originalSim, null)
                            },
                            onBrowseSlot = { slotInfo ->
                                load("Loading options for ${slotInfo.slot}...") {
                                    val options = appState.taskManager.getAvailableEquipmentForSlot(characterName, slotInfo)
                                    step = WizardStep.EquipmentSlotPicker(
                                        s.monsters, s.monster, s.originalSim, s.char,
                                        s.slotOverrides, s.slotSources, slotInfo, options
                                    )
                                }
                            },
                            onResetSlot = { slot ->
                                step = s.copy(
                                    slotOverrides = s.slotOverrides - slot,
                                    slotSources   = s.slotSources   - slot
                                )
                            },
                            onSimulate = {
                                load("Simulating with new gear...") {
                                    val newSim = appState.taskManager.simulateFightWithOverrides(
                                        characterName, s.monster.code, s.slotOverrides, 20
                                    )
                                    val actions = s.slotOverrides.map { (slot, itemCode) ->
                                        ActionHelper.EquipAction(slot, itemCode, s.slotSources[slot] ?: "bank")
                                    }
                                    step = WizardStep.EquipmentResim(
                                        s.monsters, s.monster, s.originalSim, s.char, newSim, s.slotOverrides, actions
                                    )
                                }
                            }
                        )

                        is WizardStep.EquipmentSlotPicker -> StepEquipmentSlotPicker(
                            step = s,
                            onBack = {
                                step = WizardStep.EquipmentBrowser(
                                    s.monsters, s.monster, s.originalSim, s.char, s.slotOverrides, s.slotSources
                                )
                            },
                            onSelectOption = { option ->
                                val newOverrides = s.slotOverrides + (s.slotInfo.slot to option.item.code)
                                val newSources   = s.slotSources   + (s.slotInfo.slot to option.source)
                                step = WizardStep.EquipmentBrowser(
                                    s.monsters, s.monster, s.originalSim, s.char, newOverrides, newSources
                                )
                            }
                        )

                        is WizardStep.EquipmentResim -> StepEquipmentResim(
                            step = s,
                            onBack = {
                                step = WizardStep.EquipmentBrowser(
                                    s.monsters, s.monster, s.originalSim, s.char, s.slotOverrides,
                                    s.equipActions.associate { it.slot to it.source }
                                )
                            },
                            onEquipAndFight = {
                                assign(
                                    TaskType.Fight(
                                        monsterCode  = s.monster.code,
                                        monsterName  = s.monster.name,
                                        equipActions = s.equipActions
                                    )
                                )
                            }
                        )

                        is WizardStep.CraftCategory -> StepCraftCategory(
                            onBack = { step = WizardStep.SelectType },
                            onCategorySelected = { category, isOther ->
                                if (isOther) {
                                    load("Loading craftable items...") {
                                        val items = appState.taskManager.getAvailableMiscCraftingItems(characterName)
                                        step = WizardStep.CraftItem(category, true, CraftMode.SPECIFIC, items)
                                    }
                                } else {
                                    step = WizardStep.CraftModeStep(category, false)
                                }
                            }
                        )

                        is WizardStep.CraftModeStep -> StepCraftMode(
                            step = s,
                            onBack = { step = WizardStep.CraftCategory },
                            onModeSelected = { mode ->
                                load("Loading craftable items...") {
                                    val items = appState.taskManager.getAvailableCraftingItems(characterName, s.category)
                                    step = WizardStep.CraftItem(s.category, s.isOther, mode, items)
                                }
                            }
                        )

                        is WizardStep.CraftItem -> StepCraftItem(
                            step = s,
                            onBack = {
                                step = if (s.isOther) WizardStep.CraftCategory
                                       else WizardStep.CraftModeStep(s.category, s.isOther)
                            },
                            onItemSelected = { info ->
                                if (s.mode == CraftMode.SPECIFIC) {
                                    step = WizardStep.CraftQty(s.category, s.isOther, s.mode, info, s.items)
                                } else {
                                    val actualSkill = if (s.isOther) info.item.craft?.skill ?: "cooking" else s.category
                                    assign(
                                        TaskType.Craft(
                                            skill = actualSkill,
                                            itemCode = info.item.code,
                                            itemName = info.item.name,
                                            mode = CraftMode.LEVELING,
                                            targetQuantity = 0
                                        )
                                    )
                                }
                            }
                        )

                        is WizardStep.CraftQty -> StepCraftQty(
                            step = s,
                            onBack = { step = WizardStep.CraftItem(s.category, s.isOther, s.mode, s.allItems) },
                            onConfirm = { qty ->
                                val actualSkill = if (s.isOther) s.info.item.craft?.skill ?: "cooking" else s.category
                                assign(
                                    TaskType.Craft(
                                        skill = actualSkill,
                                        itemCode = s.info.item.code,
                                        itemName = s.info.item.name,
                                        mode = CraftMode.SPECIFIC,
                                        targetQuantity = qty
                                    )
                                )
                            }
                        )

                        is WizardStep.TaskMasterTypeStep -> StepTaskMasterType(
                            step = s,
                            onBack = { step = WizardStep.SelectType },
                            onTypeSelected = { type ->
                                assign(TaskType.TaskMaster(type = type))
                            }
                        )
                    }
                }

                // ── Loading overlay ───────────────────────────────────────────
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                text = loadingMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Shared helper composables ──────────────────────────────────────────────────

@Composable
private fun WizardButton(
    title: String,
    subtitle: String = "",
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun WizardRadioCard(
    title: String,
    subtitle: String = "",
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val contentColor = if (selected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = contentColor)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.8f))
                }
            }
        }
    }
}

@Composable
private fun WizardNavRow(
    onBack: () -> Unit,
    confirmLabel: String = "Confirm",
    onConfirm: (() -> Unit)? = null,
    extraButton: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.weight(1f))
        if (extraButton != null) {
            extraButton()
            Spacer(Modifier.width(8.dp))
        }
        if (onConfirm != null) {
            Button(onClick = onConfirm) { Text(confirmLabel) }
        }
    }
}

// ── Step: Select Type ──────────────────────────────────────────────────────────

@Composable
private fun StepSelectType(
    onGather: () -> Unit,
    onFight: () -> Unit,
    onCraft: () -> Unit,
    onTaskMaster: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Select task type:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WizardButton("Gathering", "Mine, woodcut, fish, or distill resources", onClick = onGather)
        WizardButton("Fighting", "Fight monsters in a loop", onClick = onFight)
        WizardButton("Crafting", "Craft items for XP or to deposit to bank", onClick = onCraft)
        WizardButton("Task Master", "Complete tasks from an NPC task master", onClick = onTaskMaster)
    }
}

// ── Step: Gather — select skill ────────────────────────────────────────────────

@Composable
private fun StepGatherSkill(
    onBack: () -> Unit,
    onSkillSelected: (String) -> Unit
) {
    val skills = listOf("mining", "woodcutting", "fishing", "alchemy")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Select gathering skill:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (skill in skills) {
            WizardButton(skill.replaceFirstChar { it.uppercase() }, onClick = { onSkillSelected(skill) })
        }
        TextButton(onClick = onBack) { Text("← Back") }
    }
}

// ── Step: Gather — select resource ────────────────────────────────────────────

@Composable
private fun StepGatherResource(
    step: WizardStep.GatherResource,
    onBack: () -> Unit,
    onResourceSelected: (Resource) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "Select resource (${step.skill.replaceFirstChar { it.uppercase() }}):",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (step.resources.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No resources available at your level.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(step.resources) { res ->
                    val drops = res.drops.take(4).joinToString(", ") { it.code }
                    val more = if (res.drops.size > 4) " ..." else ""
                    ListItem(
                        headlineContent = {
                            Text(res.name, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(
                                "Lv.${res.level}  •  drops: $drops$more",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        modifier = Modifier.clickable { onResourceSelected(res) }
                    )
                    HorizontalDivider()
                }
            }
        }
        WizardNavRow(onBack = onBack)
    }
}

// ── Step: Gather — select strategy ────────────────────────────────────────────

@Composable
private fun StepGatherStrategy(
    step: WizardStep.GatherStrategy,
    onBack: () -> Unit,
    onConfirm: (FullInventoryStrategy) -> Unit
) {
    var selected by remember { mutableStateOf(FullInventoryStrategy.BANK_ONLY) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Gathering: ${step.resource.name}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "When inventory is full:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WizardRadioCard(
            title = "Bank Only",
            subtitle = "Go to bank and deposit all items",
            selected = selected == FullInventoryStrategy.BANK_ONLY,
            onClick = { selected = FullInventoryStrategy.BANK_ONLY }
        )
        WizardRadioCard(
            title = "Craft then Bank",
            subtitle = "Refine raw materials at a workshop first, then deposit",
            selected = selected == FullInventoryStrategy.CRAFT_THEN_BANK,
            onClick = { selected = FullInventoryStrategy.CRAFT_THEN_BANK }
        )
        WizardNavRow(onBack = onBack, confirmLabel = "Confirm", onConfirm = { onConfirm(selected) })
    }
}

// ── Step: Fight — select monster ──────────────────────────────────────────────

@Composable
private fun StepFightMonster(
    step: WizardStep.FightMonster,
    onBack: () -> Unit,
    onMonsterSelected: (Monster) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "Select a monster:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
            items(step.monsters) { mon ->
                val drops = mon.drops.take(3).joinToString(", ") { it.code }
                val more = if (mon.drops.size > 3) " ..." else ""
                ListItem(
                    headlineContent = {
                        Text(
                            "${mon.name}  Lv.${mon.level}",
                            fontWeight = FontWeight.Medium
                        )
                    },
                    supportingContent = {
                        Text(
                            "HP: ${mon.hp}  •  drops: $drops$more",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.clickable { onMonsterSelected(mon) }
                )
                HorizontalDivider()
            }
        }
        WizardNavRow(onBack = onBack)
    }
}

// ── Step: Fight — sim result ───────────────────────────────────────────────────

@Composable
private fun StepFightSim(
    step: WizardStep.FightSim,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onCheckEquipment: () -> Unit
) {
    val mon = step.monster
    val sim = step.sim

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Combat: ${mon.name} (Lv.${mon.level})",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )

        // Sim error banner
        if (step.simError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Simulation failed: ${step.simError}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Sim results
        if (sim != null) {
            val winColor = when {
                sim.winrate >= 70.0 -> Color(0xFF388E3C)
                sim.winrate >= 40.0 -> Color(0xFFF57C00)
                else                -> MaterialTheme.colorScheme.error
            }
            Surface(
                color = winColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${"%.0f".format(sim.winrate)}%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = winColor
                        )
                        Text(
                            "win rate",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${sim.wins} wins / ${sim.losses} losses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val winTurns  = sim.results.filter { it.result == "win" }
                    val lossTurns = sim.results.filter { it.result == "loss" }
                    if (winTurns.isNotEmpty()) {
                        Text(
                            "Avg turns (win):  ${"%.1f".format(winTurns.map { it.turns }.average())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (lossTurns.isNotEmpty()) {
                        Text(
                            "Avg turns (loss): ${"%.1f".format(lossTurns.map { it.turns }.average())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Monster elemental info
        HorizontalDivider()
        Text(
            "Monster info:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val attacks = listOfNotNull(
            if (mon.attackFire  > 0) "Fire: ${mon.attackFire}"   else null,
            if (mon.attackEarth > 0) "Earth: ${mon.attackEarth}" else null,
            if (mon.attackWater > 0) "Water: ${mon.attackWater}" else null,
            if (mon.attackAir   > 0) "Air: ${mon.attackAir}"     else null
        )
        val resists = listOfNotNull(
            if (mon.resFire  != 0) "Fire: ${mon.resFire}%"   else null,
            if (mon.resEarth != 0) "Earth: ${mon.resEarth}%" else null,
            if (mon.resWater != 0) "Water: ${mon.resWater}%" else null,
            if (mon.resAir   != 0) "Air: ${mon.resAir}%"     else null
        )
        if (attacks.isNotEmpty()) {
            Text(
                "ATK — ${attacks.joinToString("  •  ")}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (resists.isNotEmpty()) {
            Text(
                "RES — ${resists.joinToString("  •  ")}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (mon.effects.isNotEmpty()) {
            Text(
                "Effects: ${mon.effects.joinToString(", ") { "${it.code}(${it.value})" }}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        WizardNavRow(
            onBack = onBack,
            extraButton = {
                OutlinedButton(onClick = onCheckEquipment) { Text("Check Equipment") }
            },
            confirmLabel = "Fight!",
            onConfirm = onConfirm
        )
    }
}

// ── Step: Craft — select category ─────────────────────────────────────────────

@Composable
private fun StepCraftCategory(
    onBack: () -> Unit,
    onCategorySelected: (category: String, isOther: Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Select crafting category:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WizardButton("Weaponcrafting",   onClick = { onCategorySelected("weaponcrafting",  false) })
        WizardButton("Gearcrafting",     onClick = { onCategorySelected("gearcrafting",    false) })
        WizardButton("Jewelrycrafting",  onClick = { onCategorySelected("jewelrycrafting", false) })
        WizardButton("Other", "Cooking, refining, alchemy, etc.",
            onClick = { onCategorySelected("other", true) })
        TextButton(onClick = onBack) { Text("← Back") }
    }
}

// ── Step: Craft — select mode ─────────────────────────────────────────────────

@Composable
private fun StepCraftMode(
    step: WizardStep.CraftModeStep,
    onBack: () -> Unit,
    onModeSelected: (CraftMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Category: ${step.category.replaceFirstChar { it.uppercase() }}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Crafting purpose:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WizardButton("Leveling", "Craft & recycle repeatedly for XP",
            onClick = { onModeSelected(CraftMode.LEVELING) })
        WizardButton("Specific Items", "Craft a set quantity and deposit to bank",
            onClick = { onModeSelected(CraftMode.SPECIFIC) })
        TextButton(onClick = onBack) { Text("← Back") }
    }
}

// ── Step: Craft — select item ─────────────────────────────────────────────────

@Composable
private fun StepCraftItem(
    step: WizardStep.CraftItem,
    onBack: () -> Unit,
    onItemSelected: (ActionHelper.CraftableItemInfo) -> Unit
) {
    val label     = if (step.isOther) "misc" else step.category
    val modeLabel = if (step.mode == CraftMode.LEVELING) " — Leveling" else ""

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "${label.replaceFirstChar { it.uppercase() }}$modeLabel — select an item:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (step.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No items can be crafted with available materials.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(step.items) { info ->
                    val craftLevel   = info.item.craft?.level ?: 0
                    val ingredients  = info.ingredients.joinToString("  •  ") { "${it.quantity}× ${it.code}" }
                    val skillLabel   = if (step.isOther) "  [${info.item.craft?.skill ?: "?"}]" else ""
                    ListItem(
                        headlineContent = {
                            Text(info.item.name, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "Lv.$craftLevel  •  max ${info.maxCraftable}$skillLabel",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    ingredients,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.clickable { onItemSelected(info) }
                    )
                    HorizontalDivider()
                }
            }
        }
        WizardNavRow(onBack = onBack)
    }
}

// ── Step: Craft — enter quantity ──────────────────────────────────────────────

@Composable
private fun StepCraftQty(
    step: WizardStep.CraftQty,
    onBack: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var qtyText by remember { mutableStateOf("") }
    var qtyError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = step.info.item.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Max craftable: ${step.info.maxCraftable}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val ingredients = step.info.ingredients.joinToString("  •  ") { "${it.quantity}× ${it.code}" }
        Text(
            text = "Ingredients: $ingredients",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = qtyText,
            onValueChange = { qtyText = it; qtyError = null },
            label = { Text("Quantity") },
            isError = qtyError != null,
            supportingText = if (qtyError != null) { { Text(qtyError!!) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        WizardNavRow(
            onBack = onBack,
            confirmLabel = "Confirm",
            onConfirm = {
                val qty = qtyText.trim().toIntOrNull()
                when {
                    qty == null || qty <= 0 -> qtyError = "Enter a positive number"
                    else -> onConfirm(minOf(qty, step.info.maxCraftable))
                }
            }
        )
    }
}

// ── Step: Task Master — select type ───────────────────────────────────────────

@Composable
private fun StepTaskMasterType(
    step: WizardStep.TaskMasterTypeStep,
    onBack: () -> Unit,
    onTypeSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Task Master type:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WizardButton("Items",    "Gather or craft items for the task master",  onClick = { onTypeSelected("items") })
        WizardButton("Monsters", "Kill monsters for the task master",          onClick = { onTypeSelected("monsters") })
        if (step.existingType != null) {
            WizardButton(
                title    = "Resume Current",
                subtitle = step.existingDesc ?: step.existingType,
                onClick  = { onTypeSelected(step.existingType) }
            )
        }
        TextButton(onClick = onBack) { Text("← Back") }
    }
}

// ── Step: Equipment Browser ────────────────────────────────────────────────────

@Composable
private fun StepEquipmentBrowser(
    step: WizardStep.EquipmentBrowser,
    onBack: () -> Unit,
    onBrowseSlot: (ActionHelper.SlotInfo) -> Unit,
    onResetSlot: (String) -> Unit,
    onSimulate: () -> Unit
) {
    val char = step.char
    val overrides = step.slotOverrides
    val hasOverrides = overrides.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "Equipment for ${step.monster.name}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Browse slots to try different gear. Overridden slots shown in accent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
            items(ActionHelper.COMBAT_SLOTS) { slotInfo ->
                val currentCode = overrides[slotInfo.slot]
                    ?: when (slotInfo.slot) {
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
                val isOverridden = slotInfo.slot in overrides
                val displayColor = if (isOverridden)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface

                ListItem(
                    headlineContent = {
                        Text(
                            text = slotInfo.slot.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = if (currentCode.isEmpty()) "(empty)" else currentCode,
                            style = MaterialTheme.typography.bodySmall,
                            color = displayColor
                        )
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onBrowseSlot(slotInfo) }) { Text("Browse") }
                            if (isOverridden) {
                                TextButton(onClick = { onResetSlot(slotInfo.slot) }) { Text("Reset") }
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }

        WizardNavRow(
            onBack = onBack,
            confirmLabel = "Simulate",
            onConfirm = if (hasOverrides) onSimulate else null
        )
    }
}

// ── Step: Equipment Slot Picker ────────────────────────────────────────────────

@Composable
private fun StepEquipmentSlotPicker(
    step: WizardStep.EquipmentSlotPicker,
    onBack: () -> Unit,
    onSelectOption: (ActionHelper.EquipmentOption) -> Unit
) {
    val slotLabel = step.slotInfo.slot.replace('_', ' ').replaceFirstChar { it.uppercase() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "Choose: $slotLabel",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (step.options.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No equipment options available for this slot.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Group by source
            val bySource = step.options.groupBy { it.source }
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                val sourceOrder = listOf("inventory", "bank", "craftable")
                for (src in sourceOrder) {
                    val group = bySource[src] ?: continue
                    item {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                text = src.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                    items(group) { option ->
                        val subtitle = when (src) {
                            "inventory" -> "Lv.${option.item.level}  •  in inventory (${option.quantity})"
                            "bank"      -> "Lv.${option.item.level}  •  in bank (${option.quantity})"
                            "craftable" -> {
                                val ing = option.craftInfo?.ingredients
                                    ?.joinToString(", ") { "${it.quantity}× ${it.code}" } ?: ""
                                "Lv.${option.item.level}  •  craftable × ${option.quantity}  •  needs: $ing"
                            }
                            else -> "Lv.${option.item.level}"
                        }
                        ListItem(
                            headlineContent = {
                                Text(option.item.name, fontWeight = FontWeight.Medium)
                            },
                            supportingContent = {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                            },
                            modifier = Modifier.clickable { onSelectOption(option) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        WizardNavRow(onBack = onBack)
    }
}

// ── Step: Equipment Re-simulation ─────────────────────────────────────────────

@Composable
private fun StepEquipmentResim(
    step: WizardStep.EquipmentResim,
    onBack: () -> Unit,
    onEquipAndFight: () -> Unit
) {
    val orig = step.originalSim
    val new  = step.newSim

    fun winColor(rate: Double): Color = when {
        rate >= 70.0 -> Color(0xFF388E3C)
        rate >= 40.0 -> Color(0xFFF57C00)
        else         -> Color(0xFFD32F2F)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Simulation: ${step.monster.name}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )

        // Side-by-side win rates
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Original
            Surface(
                color = if (orig != null) winColor(orig.winrate).copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Current gear",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (orig != null) {
                        Text(
                            text = "${"%.0f".format(orig.winrate)}%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = winColor(orig.winrate)
                        )
                        Text(
                            "${orig.wins}W / ${orig.losses}L",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("N/A", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }

            // New
            val delta = if (orig != null) new.winrate - orig.winrate else null
            val deltaColor = when {
                delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
                delta > 0    -> Color(0xFF388E3C)
                delta < 0    -> Color(0xFFD32F2F)
                else         -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                color = winColor(new.winrate).copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "New gear",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${"%.0f".format(new.winrate)}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = winColor(new.winrate)
                    )
                    Text(
                        "${new.wins}W / ${new.losses}L",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (delta != null) {
                        Text(
                            text = "${if (delta >= 0) "+" else ""}${"%.0f".format(delta)}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = deltaColor
                        )
                    }
                }
            }
        }

        // Gear changes list
        if (step.slotOverrides.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Gear changes:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for ((slot, itemCode) in step.slotOverrides) {
                val slotLabel = slot.replace('_', ' ').replaceFirstChar { it.uppercase() }
                Text(
                    "$slotLabel  →  $itemCode",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        WizardNavRow(
            onBack = onBack,
            confirmLabel = "Equip & Fight!",
            onConfirm = onEquipAndFight
        )
    }
}
