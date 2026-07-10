package com.artifactsmmo.gui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.artifactsmmo.client.models.EventDefinition
import com.artifactsmmo.client.models.NPCItem
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.gui.state.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventConfigScreen(
    appState: AppState,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var definitions by remember { mutableStateOf<List<EventDefinition>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Editable copy of configs
    var editedConfigs by remember { mutableStateOf<List<EventConfig>>(appState.taskManager.getEventConfigs()) }

    val characterNames = remember { appState.taskManager.getCharacterNames() }

    LaunchedEffect(Unit) {
        try {
            definitions = appState.taskManager.getEventDefinitions()
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load events"
        } finally {
            isLoading = false
        }
    }

    fun configFor(eventCode: String): EventConfig =
        editedConfigs.find { it.eventCode == eventCode }
            ?: EventConfig(eventCode = eventCode)

    fun updateConfig(updated: EventConfig) {
        val existing = editedConfigs.indexOfFirst { it.eventCode == updated.eventCode }
        editedConfigs = if (existing >= 0)
            editedConfigs.toMutableList().also { it[existing] = updated }
        else
            editedConfigs + updated
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(580.dp).heightIn(min = 200.dp, max = 700.dp),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Title bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Event Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(4.dp)) { Text("✕") }
                }
                HorizontalDivider()

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Loading events...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (loadError != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Error: $loadError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val defs = definitions ?: emptyList()
                    val resourceEvents = defs.filter { it.content.type == "resource" }
                    val npcEvents = defs.filter { it.content.type == "npc" }
                    val monsterEvents = defs.filter { it.content.type == "monster" }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        // ── Resource events ──────────────────────────────────
                        if (resourceEvents.isNotEmpty()) {
                            item {
                                SectionHeader("Resource Events")
                            }
                            items(resourceEvents) { def ->
                                val cfg = configFor(def.code)
                                ResourceEventCard(
                                    definition = def,
                                    config = cfg,
                                    characterNames = characterNames,
                                    onUpdate = { updateConfig(it) }
                                )
                                HorizontalDivider()
                            }
                        }

                        // ── NPC events ───────────────────────────────────────
                        if (npcEvents.isNotEmpty()) {
                            item {
                                SectionHeader("NPC Events")
                            }
                            items(npcEvents) { def ->
                                val cfg = configFor(def.code)
                                NpcEventCard(
                                    definition = def,
                                    config = cfg,
                                    characterNames = characterNames,
                                    appState = appState,
                                    onUpdate = { updateConfig(it) }
                                )
                                HorizontalDivider()
                            }
                        }

                        // ── Monster events (disabled / coming soon) ──────────
                        if (monsterEvents.isNotEmpty()) {
                            item {
                                SectionHeader("Monster Events")
                            }
                            items(monsterEvents) { def ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "${def.name} — coming soon (requires gear optimisation)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }

                HorizontalDivider()
                // Footer buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        appState.saveEventConfigs(editedConfigs)
                        onDismiss()
                    }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ResourceEventCard(
    definition: EventDefinition,
    config: EventConfig,
    characterNames: List<String>,
    onUpdate: (EventConfig) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(definition.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Switch(checked = config.enabled, onCheckedChange = { onUpdate(config.copy(enabled = it)) })
        }

        if (config.enabled) {
            Text(
                text = if (config.eligibleCharacters.isEmpty()) "All qualifying characters"
                       else "Selected: ${config.eligibleCharacters.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Character chip row
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (name in characterNames) {
                    val selected = config.eligibleCharacters.contains(name)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val updated = if (selected)
                                config.eligibleCharacters - name
                            else
                                config.eligibleCharacters + name
                            onUpdate(config.copy(eligibleCharacters = updated))
                        },
                        label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NpcEventCard(
    definition: EventDefinition,
    config: EventConfig,
    characterNames: List<String>,
    appState: AppState,
    onUpdate: (EventConfig) -> Unit
) {
    // NPC catalogue — loaded once when the card is enabled/expanded
    var npcSellableItems by remember(definition.content.code) { mutableStateOf<List<NPCItem>?>(null) }
    var npcBuyableItems  by remember(definition.content.code) { mutableStateOf<List<NPCItem>?>(null) }
    var catalogueError   by remember(definition.content.code) { mutableStateOf<String?>(null) }

    if (config.enabled && npcSellableItems == null && catalogueError == null) {
        LaunchedEffect(definition.content.code) {
            try {
                val items = appState.taskManager.getNpcItems(definition.content.code)
                npcBuyableItems  = items.filter { it.buyPrice  != null }
                npcSellableItems = items.filter { it.sellPrice != null }
            } catch (e: Exception) {
                catalogueError = e.message ?: "Failed to load NPC items"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header row with enable toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(definition.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Switch(checked = config.enabled, onCheckedChange = { onUpdate(config.copy(enabled = it)) })
        }

        if (config.enabled) {
            // Designated trader dropdown
            var traderExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Trader:", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { traderExpanded = true }) {
                        Text(config.designatedTrader ?: "None", style = MaterialTheme.typography.bodySmall)
                    }
                    DropdownMenu(expanded = traderExpanded, onDismissRequest = { traderExpanded = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = {
                            onUpdate(config.copy(designatedTrader = null))
                            traderExpanded = false
                        })
                        for (name in characterNames) {
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                onUpdate(config.copy(designatedTrader = name))
                                traderExpanded = false
                            })
                        }
                    }
                }
            }

            // NPC catalogue loading state
            when {
                catalogueError != null -> {
                    Text(
                        "Could not load NPC items: $catalogueError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                npcSellableItems == null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Loading NPC catalogue...", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    val sellable = npcSellableItems ?: emptyList()
                    val buyable  = npcBuyableItems  ?: emptyList()

                    // ── Sell to NPC (items the NPC buys from the player) ──────
                    if (sellable.isNotEmpty()) {
                        Text(
                            "Sell to NPC",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        for (npcItem in sellable) {
                            val isChecked = config.itemsToSell.any { it.code == npcItem.code }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(npcItem.code, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "sells for ${npcItem.sellPrice} gold",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { nowChecked ->
                                        val updated = if (nowChecked)
                                            config.itemsToSell + SimpleItem(npcItem.code, 0)
                                        else
                                            config.itemsToSell.filter { it.code != npcItem.code }
                                        onUpdate(config.copy(itemsToSell = updated))
                                    }
                                )
                            }
                        }
                    }

                    // ── Buy from NPC (items the NPC sells to the player) ──────
                    if (buyable.isNotEmpty()) {
                        Text(
                            "Buy from NPC",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        for (npcItem in buyable) {
                            val existing = config.itemsToBuy.find { it.code == npcItem.code }
                            val isChecked = existing != null
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(npcItem.code, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "costs ${npcItem.buyPrice} gold",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isChecked) {
                                    OutlinedTextField(
                                        value = (existing?.quantity ?: 1).toString(),
                                        onValueChange = { newQty ->
                                            val qty = newQty.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                            val updated = config.itemsToBuy.map {
                                                if (it.code == npcItem.code) it.copy(quantity = qty) else it
                                            }
                                            onUpdate(config.copy(itemsToBuy = updated))
                                        },
                                        label = { Text("Qty") },
                                        modifier = Modifier.width(80.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { nowChecked ->
                                        val updated = if (nowChecked)
                                            config.itemsToBuy + SimpleItem(npcItem.code, 1)
                                        else
                                            config.itemsToBuy.filter { it.code != npcItem.code }
                                        onUpdate(config.copy(itemsToBuy = updated))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
