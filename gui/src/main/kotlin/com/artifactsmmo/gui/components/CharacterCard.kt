package com.artifactsmmo.gui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.core.task.RunnerStatus
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.gui.state.ImageCache

/**
 * Displays a summary card for one character on the dashboard.
 *
 * Shows:
 *  - Character sprite (loaded async)
 *  - Name + level
 *  - Current task description
 *  - Status message
 *  - Key counters (gathers / fights / crafts / bank trips)
 *  - Last error (if any), in error colour
 *  - Running indicator dot
 */
@Composable
fun CharacterCard(
    status: RunnerStatus,
    character: Character?,
    imageCache: ImageCache,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isChecked: Boolean? = null,          // non-null → show checkbox (multi-select mode)
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val skin = character?.skin ?: ""
    var sprite by remember(skin) { mutableStateOf<ImageBitmap?>(null) }

    // Load the sprite lazily
    if (skin.isNotEmpty()) {
        LaunchedEffect(skin) {
            sprite = imageCache.get(imageCache.characterUrl(skin))
        }
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 180.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected || isChecked == true) 8.dp else 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked == true)
                MaterialTheme.colorScheme.tertiaryContainer
            else if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Box {
            Column(modifier = Modifier.padding(12.dp)) {

                // ── Header row: sprite + name/level + running dot ──────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                // Sprite
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = sprite
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "${status.characterName} sprite",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = status.characterName.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Name + level
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.characterName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val lvl = character?.level ?: status.characterLevel
                    if (lvl > 0) {
                        Text(
                            text = "Lv $lvl",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Running indicator
                RunningDot(isRunning = status.isRunning)
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // ── Task label ────────────────────────────────────────────────────
            Text(
                text = describeTask(status.task),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))

            // ── Status message ────────────────────────────────────────────────
            Text(
                text = status.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // ── Error message ─────────────────────────────────────────────────
            val err = status.lastError
            if (err != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Counters row ──────────────────────────────────────────────────
            CounterRow(status)
        }

        // ── Multi-select checkbox overlay ──────────────────────────────────
        if (isChecked != null && onCheckedChange != null) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }
        }
    }
}

// ── Helper composables ─────────────────────────────────────────────────────────

@Composable
private fun RunningDot(isRunning: Boolean) {
    val color = if (isRunning) Color(0xFF66BB6A) else Color(0xFF757575)
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun CounterRow(status: RunnerStatus) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (status.gatherCount > 0)
            CounterChip(label = "Gather", value = status.gatherCount)
        if (status.fightCount > 0)
            CounterChip(label = "Fight", value = status.fightCount)
        if (status.craftCount > 0)
            CounterChip(label = "Craft", value = status.craftCount)
        if (status.bankTrips > 0)
            CounterChip(label = "Bank", value = status.bankTrips)
        if (status.tasksCompleted > 0)
            CounterChip(label = "Tasks", value = status.tasksCompleted)
    }
}

@Composable
private fun CounterChip(label: String, value: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.wrapContentSize()
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ── Task description ───────────────────────────────────────────────────────────

private fun describeTask(task: TaskType): String = when (task) {
    is TaskType.Idle             -> "Idle"
    is TaskType.Gather           -> "Gathering: ${task.resourceName} (${task.skill})"
    is TaskType.Fight            -> "Fighting: ${task.monsterName}"
    is TaskType.Craft            -> "Crafting: ${task.itemName} (${task.skill})"
    is TaskType.TaskMaster       -> "Task Master (${task.type})"
    is TaskType.BankWithdraw     -> "Withdrawing: ${task.quantity}x ${task.itemName}"
    is TaskType.BankRecycle      -> "Recycling (bank): ${task.quantity}x ${task.itemName}"
    is TaskType.InventoryDeposit -> "Depositing: ${task.quantity}x ${task.itemName}"
    is TaskType.InventoryRecycle -> "Recycling (inv): ${task.quantity}x ${task.itemName}"
}
