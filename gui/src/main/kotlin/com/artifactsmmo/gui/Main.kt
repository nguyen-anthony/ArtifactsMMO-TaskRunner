package com.artifactsmmo.gui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.artifactsmmo.core.task.CredentialStore
import com.artifactsmmo.gui.screens.DashboardScreen
import com.artifactsmmo.gui.screens.LoginScreen
import com.artifactsmmo.gui.state.AppState
import com.artifactsmmo.gui.state.ImageCache
import com.artifactsmmo.gui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() = application {
    var isDarkTheme by remember { mutableStateOf(true) }
    val credentialStore = remember { CredentialStore() }
    var credentials by remember { mutableStateOf(credentialStore.load()) }

    Window(
        onCloseRequest = { exitApplication() },
        title = "ArtifactsMMO Task Runner",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
        resizable = true,
    ) {
        AppTheme(darkTheme = isDarkTheme) {
            Surface(color = MaterialTheme.colorScheme.background) {
                val creds = credentials
                if (creds == null) {
                    LoginScreen(onLoginSuccess = { token, username ->
                        credentialStore.save(token, username)
                        credentials = credentialStore.load()
                    })
                } else {
                    key(creds.token) {
                        val localScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
                        val localImageCache = remember { ImageCache() }
                        val localAppState = remember { AppState(localScope, creds.token) }
                        DisposableEffect(Unit) {
                            onDispose {
                                localAppState.stopAll()
                                localImageCache.close()
                                localScope.cancel()
                            }
                        }
                        DashboardScreen(
                            appState = localAppState,
                            imageCache = localImageCache,
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = { isDarkTheme = !isDarkTheme },
                            onDisconnect = {
                                credentialStore.clear()
                                credentials = null
                            },
                            username = creds.username
                        )
                    }
                }
            }
        }
    }
}
