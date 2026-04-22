package com.artifactsmmo.gui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.artifactsmmo.client.ArtifactsMMOClient
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: (token: String, username: String) -> Unit) {
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(420.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ArtifactsMMO Task Runner",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Enter your API token to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        errorMessage = null
                    },
                    label = { Text("API Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(if (showToken) "Hide" else "Show")
                        }
                    },
                    isError = errorMessage != null,
                    enabled = !isLoading
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = {
                        val trimmed = token.trim()
                        if (trimmed.isBlank()) {
                            errorMessage = "Token cannot be empty."
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val tempClient = ArtifactsMMOClient(token = trimmed)
                            try {
                                val details = tempClient.account.getMyDetails()
                                onLoginSuccess(trimmed, details.username)
                            } catch (e: Exception) {
                                errorMessage = "Invalid token or connection error: ${e.message}"
                            } finally {
                                tempClient.close()
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Sign In")
                }
            }
        }
    }
}
