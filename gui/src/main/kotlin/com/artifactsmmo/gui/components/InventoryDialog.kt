package com.artifactsmmo.gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.InventorySlot
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.gui.state.AppState
import kotlinx.coroutines.launch

private val RECYCLE_SKILLS_INV = setOf("weaponcrafting", "gearcrafting", "jewelrycrafting")

/**
 * Detail record for an inventory slot with full item metadata.
 */
private data class InventoryItemDetail(
    val slot: InventorySlot,
    val item: Item
)

/**
 * Dialog showing the character's current inventory contents.
 *
 * Each item row has:
 * - Item name, code, quantity
 * - "Deposit" button → quantity prompt → assigns InventoryDeposit quick task
 * - "Recycle" button (only for weapon/gear/jewelry craft items) → quantity prompt → assigns InventoryRecycle
 */
@Composable
fun InventoryDialog(
    characterName: String,
    character: Character,
    appState: AppState,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Fetch item details for all non-empty inventory slots
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var inventoryItems by remember { mutableStateOf<List<InventoryItemDetail>>(emptyList()) }

    // Quantity prompt state
    var quantityTarget by remember { mutableStateOf<InventoryItemDetail?>(null) }
    var quantityAction by remember { mutableStateOf<String>("deposit") } // "deposit" | "recycle"
    var quantityInput by remember { mutableStateOf("") }

    val nonEmptySlots = remember(character) {
        character.inventory.filter { it.quantity > 0 && it.code.isNotEmpty() }
    }

    LaunchedEffect(character) {
        isLoading = true
        loadError = null
        try {
            val details = nonEmptySlots.mapNotNull { slot ->
                runCatching { appState.client.content.getItem(slot.code) }
                    .getOrNull()
                    ?.let { InventoryItemDetail(slot, it) }
            }
            inventoryItems = details
        } catch (e: Exception) {
            loadError = e.message ?: "Unknown error"
        } finally {
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inventory — $characterName") },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp)) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    loadError != null -> {
                        Text(
                            text = "Failed to load inventory details: $loadError",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    inventoryItems.isEmpty() -> {
                        Text(
                            text = "Inventory is empty.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(inventoryItems) { detail ->
                                InventoryItemRow(
                                    detail = detail,
                                    onDeposit = {
                                        quantityTarget = detail
                                        quantityAction = "deposit"
                                        quantityInput = detail.slot.quantity.toString()
                                    },
                                    onRecycle = {
                                        quantityTarget = detail
                                        quantityAction = "recycle"
                                        quantityInput = detail.slot.quantity.toString()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    // Quantity sub-dialog
    val target = quantityTarget
    if (target != null) {
        val maxQty = target.slot.quantity
        val craftSkill = target.item.craft?.skill ?: ""
        AlertDialog(
            onDismissRequest = { quantityTarget = null },
            title = {
                Text(
                    if (quantityAction == "deposit")
                        "Deposit ${target.item.name}"
                    else
                        "Recycle ${target.item.name}"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("In inventory: $maxQty")
                    OutlinedTextField(
                        value = quantityInput,
                        onValueChange = { quantityInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val qty = quantityInput.toIntOrNull()?.coerceIn(1, maxQty) ?: 1
                        val task = if (quantityAction == "deposit") {
                            TaskType.InventoryDeposit(
                                itemCode = target.item.code,
                                itemName = target.item.name,
                                quantity = qty
                            )
                        } else {
                            TaskType.InventoryRecycle(
                                itemCode = target.item.code,
                                itemName = target.item.name,
                                quantity = qty,
                                craftSkill = craftSkill
                            )
                        }
                        scope.launch {
                            appState.taskManager.assignTask(characterName, task)
                        }
                        quantityTarget = null
                        onDismiss()
                    }
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { quantityTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InventoryItemRow(
    detail: InventoryItemDetail,
    onDeposit: () -> Unit,
    onRecycle: () -> Unit
) {
    val craftSkill = detail.item.craft?.skill
    val canRecycle = craftSkill != null && craftSkill in RECYCLE_SKILLS_INV

    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${detail.item.code}  •  qty: ${detail.slot.quantity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onDeposit,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Deposit", style = MaterialTheme.typography.labelSmall)
            }
            if (canRecycle) {
                OutlinedButton(
                    onClick = onRecycle,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Recycle", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
