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
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.core.task.TaskManager
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.gui.state.AppState
import kotlinx.coroutines.launch

private val RECYCLE_SKILLS = setOf("weaponcrafting", "gearcrafting", "jewelrycrafting")

/**
 * Dialog showing the character's bank contents.
 *
 * Quantities come from the live [AppState.bankSnapshot] StateFlow so they update
 * automatically as WebSocket deltas or periodic syncs change the snapshot.
 * Item metadata (names, craft skills) is loaded once on open and cached in memory.
 */
@Composable
fun BankDialog(
    characterName: String,
    appState: AppState,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Item metadata keyed by code — loaded once on open, never stale
    var itemMeta by remember { mutableStateOf<Map<String, Item>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Live bank quantities from the StateFlow — updates automatically
    val bankSnapshot by appState.bankSnapshot.collectAsState()

    // Quantity prompt state (single-item)
    var quantityTarget by remember { mutableStateOf<TaskManager.BankItemDetail?>(null) }
    var quantityAction by remember { mutableStateOf<String>("withdraw") }
    var quantityInput by remember { mutableStateOf("") }

    // Multi-select state
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<String>()) }
    var selectedQuantities by remember { mutableStateOf(mapOf<String, String>()) }
    var showBulkConfirm by remember { mutableStateOf(false) }

    // Load metadata once — quantities come from the live snapshot above
    LaunchedEffect(Unit) {
        isLoading = true
        loadError = null
        try {
            val details = appState.taskManager.getBankItemsWithDetails()
            itemMeta = details.associate { it.item.code to it.item }
        } catch (e: Exception) {
            loadError = e.message ?: "Unknown error"
        } finally {
            isLoading = false
        }
    }

    // Build the display list: merge live quantities with loaded metadata.
    // Items that appeared in the snapshot after open (new deposits) are shown
    // if their metadata is already cached; otherwise they appear on next open.
    val bankItems: List<TaskManager.BankItemDetail> = remember(bankSnapshot, itemMeta) {
        bankSnapshot
            .filter { (code, qty) -> qty > 0 }
            .mapNotNull { (code, qty) ->
                itemMeta[code]?.let { item ->
                    TaskManager.BankItemDetail(SimpleItem(code, qty), item)
                }
            }
            .sortedBy { it.item.name }
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
                Text("Bank — $characterName")
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
                            text = "Failed to load bank: $loadError",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    bankItems.isEmpty() -> {
                        Text(
                            text = "Bank is empty.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(bankItems) { detail ->
                                BankItemRow(
                                    detail = detail,
                                    isSelected = detail.item.code in selectedItems,
                                    isMultiSelectMode = multiSelectMode,
                                    onToggleSelect = {
                                        selectedItems = if (detail.item.code in selectedItems) {
                                            selectedItems - detail.item.code
                                        } else {
                                            selectedItems + detail.item.code
                                        }
                                        // Reset quantity to full bank quantity when toggling
                                        if (detail.item.code in selectedItems) {
                                            selectedQuantities = selectedQuantities + (detail.item.code to detail.bankItem.quantity.toString())
                                        } else {
                                            selectedQuantities = selectedQuantities - detail.item.code
                                        }
                                    },
                                    onWithdraw = {
                                        quantityTarget = detail
                                        quantityAction = "withdraw"
                                        quantityInput = detail.bankItem.quantity.toString()
                                    },
                                    onRecycle = {
                                        quantityTarget = detail
                                        quantityAction = "recycle"
                                        quantityInput = detail.bankItem.quantity.toString()
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
                        Text("Withdraw ${selectedItems.size}")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )

    // Quantity sub-dialog (single-item)
    val target = quantityTarget
    if (target != null && !showBulkConfirm) {
        val maxQty = target.bankItem.quantity
        val craftSkill = target.item.craft?.skill ?: ""
        AlertDialog(
            onDismissRequest = { quantityTarget = null },
            title = {
                Text(
                    if (quantityAction == "withdraw")
                        "Withdraw ${target.item.name}"
                    else
                        "Recycle ${target.item.name}"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Available in bank: $maxQty")
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
                        val task = if (quantityAction == "withdraw") {
                            TaskType.BankWithdraw(
                                itemCode = target.item.code,
                                itemName = target.item.name,
                                quantity = qty
                            )
                        } else {
                            TaskType.BankRecycle(
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

    // Bulk withdraw confirmation dialog (multi-item)
    if (showBulkConfirm && selectedItems.isNotEmpty()) {
        val itemsToWithdraw = bankItems.filter { it.item.code in selectedItems }
        AlertDialog(
            onDismissRequest = { showBulkConfirm = false },
            title = { Text("Confirm Bulk Withdraw (${itemsToWithdraw.size} items)") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(itemsToWithdraw) { detail ->
                        val currentQtyStr = selectedQuantities[detail.item.code] ?: detail.bankItem.quantity.toString()
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = detail.item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "In bank: ${detail.bankItem.quantity}",
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
                        val items = itemsToWithdraw.mapNotNull { detail ->
                            val qtyStr = selectedQuantities[detail.item.code] ?: ""
                            val qty = qtyStr.toIntOrNull()?.coerceIn(1, detail.bankItem.quantity) ?: 0
                            if (qty > 0) SimpleItem(detail.item.code, qty) else null
                        }
                        if (items.isNotEmpty()) {
                            val task = TaskType.BulkBankWithdraw(items = items)
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
private fun BankItemRow(
    detail: TaskManager.BankItemDetail,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onToggleSelect: () -> Unit,
    onWithdraw: () -> Unit,
    onRecycle: () -> Unit
) {
    val craftSkill = detail.item.craft?.skill
    val canRecycle = craftSkill != null && craftSkill in RECYCLE_SKILLS

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
                    text = "${detail.item.code}  •  qty: ${detail.bankItem.quantity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isMultiSelectMode) {
                OutlinedButton(onClick = onWithdraw, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Withdraw", style = MaterialTheme.typography.labelSmall)
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
