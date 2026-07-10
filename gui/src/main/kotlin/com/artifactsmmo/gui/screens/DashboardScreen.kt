package com.artifactsmmo.gui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.artifactsmmo.core.task.TaskLogger
import com.artifactsmmo.gui.components.CharacterCard
import com.artifactsmmo.gui.components.CharacterDetailPanel
import com.artifactsmmo.gui.components.BankDialog
import com.artifactsmmo.gui.components.InventoryDialog
import com.artifactsmmo.gui.components.TaskWizardDialog
import com.artifactsmmo.gui.state.AppState
import com.artifactsmmo.gui.state.ImageCache
import kotlinx.coroutines.launch

/**
 * Main dashboard screen.
 *
 * Layout:
 *  - Top bar: title, Refresh, Stop All, multi-select controls, theme toggle
 *  - Body:
 *      - No selection:  [Card grid (full width)] / [Log panel]
 *      - Card selected: [Card grid (60%)] | [Detail panel (40%)] / [Log panel]
 *  - Log panel has per-character filter chips
 */
@Composable
fun DashboardScreen(
    appState: AppState,
    imageCache: ImageCache,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onDisconnect: () -> Unit = {},
    username: String? = null
) {
    val scope = rememberCoroutineScope()
    val initState by appState.initState.collectAsState()
    val statuses by appState.statuses.collectAsState()
    val characterDetails by appState.characterDetails.collectAsState()
    val logEntries by appState.logEntries.collectAsState()
    @OptIn(kotlin.time.ExperimentalTime::class)
    val wsLogEntries by appState.wsLogEntries.collectAsState()

    // Single-select: which character's detail panel is shown
    var selectedCharacter by remember { mutableStateOf<String?>(null) }

    // Multi-select mode
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedCharacters by remember { mutableStateOf(setOf<String>()) }

    // Task wizard: list of characters to assign to (null = closed)
    var wizardCharacters by remember { mutableStateOf<List<String>?>(null) }

    // Event config dialog
    var showEventConfig by remember { mutableStateOf(false) }

    // Bank dialog: character name (null = closed)
    var bankDialogCharacter by remember { mutableStateOf<String?>(null) }

    // Inventory dialog: character name (null = closed)
    var inventoryDialogCharacter by remember { mutableStateOf<String?>(null) }

    // Log filter: null = All
    var logFilter by remember { mutableStateOf<String?>(null) }

    // Disconnect confirmation dialog
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    // When multi-select mode is toggled off, clear selections
    LaunchedEffect(multiSelectMode) {
        if (!multiSelectMode) selectedCharacters = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ArtifactsMMO Task Runner",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        scope.launch { appState.refreshCharacterDetails() }
                    }) { Text("Refresh") }

                    OutlinedButton(onClick = { showEventConfig = true }) { Text("Events") }

                    // Multi-select toggle
                    val selectLabel = if (multiSelectMode) "Cancel Select" else "Select"
                    OutlinedButton(onClick = { multiSelectMode = !multiSelectMode }) {
                        Text(selectLabel)
                    }

                    // "Assign to N" button shown when characters are checked
                    if (multiSelectMode && selectedCharacters.isNotEmpty()) {
                        Button(onClick = {
                            wizardCharacters = selectedCharacters.toList()
                        }) {
                            Text("Assign to ${selectedCharacters.size}")
                        }
                    }

                    Button(
                        onClick = { appState.stopAll() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Stop All") }

                    if (username != null) {
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(onClick = { showDisconnectConfirm = true }) {
                        Text("Disconnect")
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (isDarkTheme) "Dark" else "Light",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(checked = isDarkTheme, onCheckedChange = { onToggleTheme() })
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect") },
            text = {
                Text("This will remove your stored API token. You will need to re-enter it to use the app.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        onDisconnect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Disconnect") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
        }

        // ── Body ──────────────────────────────────────────────────────────────
        when (val state = initState) {
            is AppState.InitState.Loading -> LoadingView(modifier = Modifier.weight(1f))
            is AppState.InitState.Error   -> ErrorView(state.message, modifier = Modifier.weight(1f))

            is AppState.InitState.Ready -> {
                val characterNames = state.characterNames

                // Middle section: cards (+ optional detail panel)
                Row(modifier = Modifier.weight(0.65f).fillMaxWidth()) {

                    // ── Card grid ─────────────────────────────────────────────
                    val gridWeight = if (selectedCharacter != null && !multiSelectMode) 0.6f else 1f
                    Column(
                        modifier = Modifier
                            .weight(gridWeight)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val rows = statuses.chunked(2)
                        for (row in rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                for (s in row) {
                                    val name = s.characterName
                                    CharacterCard(
                                        status = s,
                                        character = characterDetails[name],
                                        imageCache = imageCache,
                                        isSelected = name == selectedCharacter && !multiSelectMode,
                                        isChecked = if (multiSelectMode) selectedCharacters.contains(name) else null,
                                        onCheckedChange = if (multiSelectMode) { checked ->
                                            selectedCharacters = if (checked)
                                                selectedCharacters + name
                                            else
                                                selectedCharacters - name
                                        } else null,
                                        onClick = {
                                            if (multiSelectMode) {
                                                selectedCharacters = if (selectedCharacters.contains(name))
                                                    selectedCharacters - name
                                                else
                                                    selectedCharacters + name
                                            } else {
                                                selectedCharacter =
                                                    if (selectedCharacter == name) null else name
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // ── Detail panel (shown when a card is selected, not in multi-select) ──
                    val selName = selectedCharacter
                    val selChar = if (selName != null && !multiSelectMode) characterDetails[selName] else null
                    if (selName != null && selChar != null) {
                        VerticalDivider()
                        CharacterDetailPanel(
                            character = selChar,
                            appState = appState,
                            onClose = { selectedCharacter = null },
                            onAssignTask = { wizardCharacters = listOf(selName) },
                            onStopTask = { appState.taskManager.stopTask(selName) },
                            onOpenBank = { bankDialogCharacter = selName },
                            onOpenInventory = { inventoryDialogCharacter = selName },
                            modifier = Modifier.weight(0.4f)
                        )
                    }
                }

                // ── Task wizard dialog ─────────────────────────────────────────
                val wiz = wizardCharacters
                if (wiz != null) {
                    TaskWizardDialog(
                        characterNames = wiz,
                        appState = appState,
                        onDismiss = {
                            wizardCharacters = null
                            // Exit multi-select mode after assigning
                            if (multiSelectMode) {
                                multiSelectMode = false
                            }
                        }
                    )
                }

                // ── Event config dialog ────────────────────────────────────────
                if (showEventConfig) {
                    EventConfigScreen(appState = appState, onDismiss = { showEventConfig = false })
                }

                // ── Bank dialog ────────────────────────────────────────────────
                val bankChar = bankDialogCharacter
                if (bankChar != null) {
                    BankDialog(
                        characterName = bankChar,
                        appState = appState,
                        onDismiss = { bankDialogCharacter = null }
                    )
                }

                // ── Inventory dialog ───────────────────────────────────────────
                val invChar = inventoryDialogCharacter
                val invCharDetails = if (invChar != null) characterDetails[invChar] else null
                if (invChar != null && invCharDetails != null) {
                    InventoryDialog(
                        characterName = invChar,
                        character = invCharDetails,
                        appState = appState,
                        onDismiss = { inventoryDialogCharacter = null }
                    )
                }

                HorizontalDivider()

                // ── Log panel ─────────────────────────────────────────────────
                @OptIn(kotlin.time.ExperimentalTime::class)
                LogPanel(
                    entries = logEntries,
                    wsLogEntries = wsLogEntries,
                    characterNames = characterNames,
                    logFilter = logFilter,
                    onFilterChange = { logFilter = it },
                    modifier = Modifier.weight(0.35f).fillMaxWidth()
                )
            }
        }
    }
}

// ── Auxiliary views ────────────────────────────────────────────────────────────

@Composable
private fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Connecting to ArtifactsMMO...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Initialization failed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Log panel ──────────────────────────────────────────────────────────────────

private const val WS_LOG_FILTER = "__websocket__"

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
private fun LogPanel(
    entries: List<TaskLogger.LogEntry>,
    wsLogEntries: List<com.artifactsmmo.core.task.WebSocketManager.WebSocketLogEntry>,
    characterNames: List<String>,
    logFilter: String?,
    onFilterChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val isWsMode = logFilter == WS_LOG_FILTER

    // Task log: filter + format
    val filtered = remember(entries, logFilter) {
        if (logFilter == null || isWsMode) entries
        else entries.filter { it.characterName.equals(logFilter, ignoreCase = true) }
    }
    val lines = remember(filtered) { filtered.map { it.formatted() } }

    val listState = rememberLazyListState()

    // Auto-scroll: task log scrolls to newest at bottom; WS log is shown newest-first
    LaunchedEffect(lines.size) {
        if (!isWsMode && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LaunchedEffect(wsLogEntries.size) {
        if (isWsMode && wsLogEntries.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Filter chip row ────────────────────────────────────────────────
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = logFilter == null,
                        onClick = { onFilterChange(null) },
                        label = { Text("All") }
                    )
                }
                items(characterNames) { name ->
                    FilterChip(
                        selected = logFilter == name,
                        onClick = { onFilterChange(if (logFilter == name) null else name) },
                        label = { Text(name) }
                    )
                }
                item {
                    FilterChip(
                        selected = isWsMode,
                        onClick = { onFilterChange(if (isWsMode) null else WS_LOG_FILTER) },
                        label = { Text("WebSocket") }
                    )
                }
            }

            HorizontalDivider()

            // ── Log lines ──────────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (isWsMode) {
                    // WebSocket feed — newest first
                    val wsLines = wsLogEntries.takeLast(500).asReversed()
                    items(wsLines) { entry ->
                        val timeStr = formatWsTimestamp(entry.timestamp)
                        Text(
                            text = "[$timeStr] ${entry.summary}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.9
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.9
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@kotlin.time.ExperimentalTime
private fun formatWsTimestamp(instant: kotlin.time.Instant): String {
    val epochMs = instant.toEpochMilliseconds()
    val zdt = java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
    return "%02d:%02d:%02d".format(zdt.hour, zdt.minute, zdt.second)
}
