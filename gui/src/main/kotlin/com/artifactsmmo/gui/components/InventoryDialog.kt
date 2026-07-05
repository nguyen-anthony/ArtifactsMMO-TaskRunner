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
import com.artifactsmmo.client.models.SimpleItem
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
 *
 * Multi-select mode allows depositing multiple items in a single task.
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

    // Quantity prompt state (single-item)
    var quantityTarget by remember { mutableStateOf<InventoryItemDetail?>(null) }
    var quantityAction by remember { mutableStateOf<String>("deposit") } // "deposit" | "recycle"
    var quantityInput by remember { mutableStateOf("") }

    // Multi-select state
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<String>()) } // keyed by item code
    var selectedQuantities by remember { mutableStateOf(mapOf<String, String>()) } // itemCode -> qty string
    var showBulkConfirm by remember { mutableStateOf(false) }

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

    // Clear selection when exiting multi-select mode
    LaunchedEffect(multiSelectMode) {
        if (!multiSelectMode) {
            selectedItems = emptySet()
            selectedQuantities = emptyMap()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Inventory — $characterName")
                val selectLabel = if (multiSelectMode) "Cancel" else "Select"
                TextButton(onClick = { multiSelectMode = !multiSelectMode }) {
                    Text(selectLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
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
                                    isSelected = detail.item.code in selectedItems,
                                    isMultiSelectMode = multiSelectMode,
                                    onToggleSelect = {
                                        selectedItems = if (detail.item.code in selectedItems) {
                                            selectedItems - detail.item.code
                                        } else {
                                            selectedItems + detail.item.code
                                        }
                                        // Reset quantity to full inventory quantity when toggling
                                        if (detail.item.code in selectedItems) {
                                            selectedQuantities = selectedQuantities + (detail.item.code to detail.slot.quantity.toString())
                                        } else {
                                            selectedQuantities = selectedQuantities - detail.item.code
                                        }
                                    },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (multiSelectMode && selectedItems.isNotEmpty()) {
                    Button(onClick = { showBulkConfirm = true }) {
                        Text("Deposit ${selectedItems.size}")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )

    // Quantity sub-dialog (single-item)
    val target = quantityTarget
    if (target != null && !showBulkConfirm) {
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

    // Bulk deposit confirmation dialog (multi-item)
    if (showBulkConfirm && selectedItems.isNotEmpty()) {
        val itemsToDeposit = inventoryItems.filter { it.item.code in selectedItems }
        AlertDialog(
            onDismissRequest = { showBulkConfirm = false },
            title = { Text("Confirm Bulk Deposit (${itemsToDeposit.size} items)") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(itemsToDeposit) { detail ->
                        val currentQtyStr = selectedQuantities[detail.item.code] ?: detail.slot.quantity.toString()
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = detail.item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "In inventory: ${detail.slot.quantity}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = currentQtyStr,
                                onValueChange = { newVal ->
                                    selectedQuantities = selectedQuantities + (detail.item.code to newVal.filter { c -> c.isDigit() })
                                },
                                label = { Text("Qty") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val items = itemsToDeposit.mapNotNull { detail ->
                            val qtyStr = selectedQuantities[detail.item.code] ?: ""
                            val qty = qtyStr.toIntOrNull()?.coerceIn(1, detail.slot.quantity) ?: 0
                            if (qty > 0) SimpleItem(detail.item.code, qty) else null
                        }
                        if (items.isNotEmpty()) {
                            val task = TaskType.BulkInventoryDeposit(items = items)
                            scope.launch {
                                appState.taskManager.assignTask(characterName, task)
                            }
                            selectedItems = emptySet()
                            selectedQuantities = emptyMap()
                            showBulkConfirm = false
                            onDismiss()
                        }
                    }
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InventoryItemRow(
    detail: InventoryItemDetail,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onToggleSelect: () -> Unit,
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
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(24.dp)
                )
            }
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
            if (!isMultiSelectMode) {
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
}
