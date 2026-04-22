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
import java.awt.Desktop
import java.net.URI

fun main() = application {
    var isDarkTheme by remember { mutableStateOf(true) }
    val credentialStore = remember { CredentialStore() }
    var credentials by remember { mutableStateOf(credentialStore.load()) }

    // Update check state — populated once after the user is logged in.
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

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
                    // Run the update check once when credentials are first available.
                    LaunchedEffect(Unit) {
                        updateInfo = UpdateChecker.checkForUpdate()
                    }

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

                    // Update available dialog — shown over the dashboard.
                    val info = updateInfo
                    if (info != null) {
                        AlertDialog(
                            onDismissRequest = { updateInfo = null },
                            title = { Text("Update Available") },
                            text = {
                                Text(
                                    "Version ${info.version} is available.\n" +
                                    "You are currently on ${UpdateChecker.currentVersion() ?: "unknown"}."
                                )
                            },
                            confirmButton = {
                                Button(onClick = {
                                    runCatching {
                                        Desktop.getDesktop().browse(URI(info.releaseUrl))
                                    }
                                    updateInfo = null
                                }) { Text("Download") }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { updateInfo = null }) { Text("Dismiss") }
                            }
                        )
                    }
                }
            }
        }
    }
}
