package com.artifactsmmo.gui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artifactsmmo.client.models.Raid
import com.artifactsmmo.core.task.RaidConfig
import com.artifactsmmo.gui.state.AppState

/** Configure scheduled raid teams. The scheduler owns dispatch and late-join behavior. */
@Composable
fun RaidConfigScreen(appState: AppState, onDismiss: () -> Unit) {
    var raids by remember { mutableStateOf<List<Raid>?>(null) }
    var configs by remember { mutableStateOf(appState.taskManager.getRaidConfigs()) }
    var error by remember { mutableStateOf<String?>(null) }
    val characterNames = appState.taskManager.getCharacterNames()

    LaunchedEffect(Unit) {
        try {
            raids = appState.taskManager.getRaids()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load raids"
        }
    }

    fun configFor(code: String): RaidConfig = configs.firstOrNull { it.raidCode == code } ?: RaidConfig(code)
    fun update(config: RaidConfig) {
        configs = configs.filterNot { it.raidCode == config.raidCode } + config
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raid Configuration") },
        text = {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                raids == null -> Text("Loading raids...")
                else -> LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                    items(raids!!) { raid ->
                        RaidConfigCard(raid, configFor(raid.code), characterNames, ::update)
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                appState.taskManager.saveRaidConfigs(configs)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RaidConfigCard(
    raid: Raid,
    config: RaidConfig,
    characterNames: List<String>,
    onUpdate: (RaidConfig) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(raid.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${raid.schedule.weekdays.joinToString()} at %02d:%02d UTC".format(
                        raid.schedule.startHourUtc, raid.schedule.startMinuteUtc
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = config.enabled, onCheckedChange = { onUpdate(config.copy(enabled = it)) })
        }

        if (!config.enabled) return@Column

        Text("Initiator", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            characterNames.forEach { name ->
                FilterChip(
                    selected = config.initiatorName == name,
                    onClick = {
                        val participants = config.participantNames.filter { it != name }
                        onUpdate(config.copy(initiatorName = name, participantNames = participants))
                    },
                    label = { Text(name) }
                )
            }
        }

        Text("Participants (up to 2)", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            characterNames.filter { it != config.initiatorName }.forEach { name ->
                val selected = name in config.participantNames
                FilterChip(
                    selected = selected,
                    onClick = {
                        val updated = if (selected) config.participantNames - name
                        else if (config.participantNames.size < 2) config.participantNames + name
                        else config.participantNames
                        onUpdate(config.copy(participantNames = updated))
                    },
                    label = { Text(name) }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.tankOverride != null,
                onCheckedChange = { enabled ->
                    onUpdate(config.copy(tankOverride = if (enabled) config.initiatorName else null))
                }
            )
            Text("Override auto-detected tank")
        }
        if (config.tankOverride != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOfNotNull(config.initiatorName) + config.participantNames).forEach { name ->
                    FilterChip(
                        selected = config.tankOverride == name,
                        onClick = { onUpdate(config.copy(tankOverride = name)) },
                        label = { Text(name) }
                    )
                }
            }
        }

        Text("Dispatch ${config.leadTimeMinutes} minutes early", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = config.leadTimeMinutes.toFloat(),
            onValueChange = { onUpdate(config.copy(leadTimeMinutes = it.toInt().coerceIn(1, 30))) },
            valueRange = 1f..30f,
            steps = 28
        )
    }
}
