package com.template.app.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.template.app.anova.AnovaSettings
import com.template.app.anova.ConnectionMode

@Composable
fun ConnectionSettingsDialog(
    mode: ConnectionMode,
    currentIp: String,
    currentEmail: String,
    currentLocalPollMs: Long,
    currentRemotePollMs: Long,
    isScanning: Boolean = false,
    scannedIp: String? = null,
    onScanClick: (() -> Unit)? = null,
    onGoogleSignInClick: (() -> Unit)? = null,
    googleSignedInAs: String? = null,
    onSeedRefreshToken: ((token: String, email: String?) -> Unit)? = null,
    onSave: (ip: String, email: String, password: String, localMs: Long, remoteMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val needsCloud = mode == ConnectionMode.CLOUD || mode == ConnectionMode.AUTO

    var ip            by remember { mutableStateOf(currentIp) }
    var email         by remember { mutableStateOf(currentEmail) }
    var password      by remember { mutableStateOf("") }
    var showPassword  by remember { mutableStateOf(false) }
    var remotePollSec by remember { mutableStateOf((currentRemotePollMs / 1000).toString()) }

    // Refresh token paste section
    var showTokenSection  by remember { mutableStateOf(false) }
    var refreshTokenInput by remember { mutableStateOf("") }
    var tokenEmail        by remember { mutableStateOf("") }

    LaunchedEffect(scannedIp) { if (scannedIp != null) ip = scannedIp }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {

                if (needsCloud) {
                    SectionHeader("Cloud (remote access)")

                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(top = 2.dp))
                            Text("Unofficial API — may change without notice.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }

                    // Google Sign-In button
                    if (onGoogleSignInClick != null) {
                        if (googleSignedInAs != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Signed in: $googleSignedInAs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(10.dp))
                            }
                        } else {
                            OutlinedButton(onClick = onGoogleSignInClick, modifier = Modifier.fillMaxWidth()) {
                                Text("Sign in with Google")
                            }
                        }
                    }

                    HorizontalDivider()

                    // Email / password
                    OutlinedTextField(value = email, onValueChange = { email = it },
                        label = { Text("Anova account email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("Password") },
                        placeholder = { Text("Leave blank to keep existing") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null)
                            }
                        }, modifier = Modifier.fillMaxWidth())

                    OutlinedTextField(value = remotePollSec, onValueChange = { remotePollSec = it },
                        label = { Text("Poll interval (seconds)") },
                        placeholder = { Text("Default: ${AnovaSettings.DEFAULT_REMOTE_POLL_MS / 1000}") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())

                    HorizontalDivider()

                    // Paste refresh token section
                    TextButton(onClick = { showTokenSection = !showTokenSection },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (showTokenSection) "▲ Hide manual token setup" else "▼ Manual token setup (advanced)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (showTokenSection && onSeedRefreshToken != null) {
                        Text(
                            "If Google sign-in doesn't work, authenticate via the Mac HTML page " +
                            "(see docs/anova-token-management.md) and paste the Firebase refresh token here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = refreshTokenInput, onValueChange = { refreshTokenInput = it },
                            label = { Text("Firebase Refresh Token") }, singleLine = false, maxLines = 3,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = tokenEmail, onValueChange = { tokenEmail = it },
                            label = { Text("Account email (optional)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = {
                                if (refreshTokenInput.isNotBlank()) {
                                    onSeedRefreshToken(refreshTokenInput.trim(), tokenEmail.trim().ifBlank { null })
                                    refreshTokenInput = ""
                                    tokenEmail = ""
                                    showTokenSection = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = refreshTokenInput.isNotBlank()
                        ) { Text("Save Token") }
                    }
                }

                if (!needsCloud) {
                    Text("Cloud mode is recommended for the Anova Precision Cooker 3. Switch to Auto or Cloud mode above.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val remoteMs = (remotePollSec.toLongOrNull() ?: (AnovaSettings.DEFAULT_REMOTE_POLL_MS / 1000)) * 1000
                onSave(ip.trim(), email.trim(), password, AnovaSettings.DEFAULT_LOCAL_POLL_MS, remoteMs)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
