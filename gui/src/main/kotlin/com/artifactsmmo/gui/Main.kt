package com.artifactsmmo.gui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.artifactsmmo.gui.screens.DashboardScreen
import com.artifactsmmo.gui.state.AppState
import com.artifactsmmo.gui.state.ImageCache
import com.artifactsmmo.gui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() = application {
    var isDarkTheme by remember { mutableStateOf(true) }

    // Single app-wide coroutine scope backed by the default dispatcher
    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val appState = remember { AppState(appScope) }
    val imageCache = remember { ImageCache() }

    Window(
        onCloseRequest = {
            appState.stopAll()
            imageCache.close()
            appScope.cancel()
            exitApplication()
        },
        title = "ArtifactsMMO Task Runner",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
        resizable = true,
    ) {
        AppTheme(darkTheme = isDarkTheme) {
            Surface(
                color = MaterialTheme.colorScheme.background
            ) {
                DashboardScreen(
                    appState = appState,
                    imageCache = imageCache,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme }
                )
            }
        }
    }
}
