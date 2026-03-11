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
    onSave: (ip: String, email: String, password: String, localMs: Long, remoteMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val needsWifi  = mode == ConnectionMode.LOCAL_WIFI || mode == ConnectionMode.AUTO
    val needsCloud = mode == ConnectionMode.CLOUD      || mode == ConnectionMode.AUTO

    var ip by remember { mutableStateOf(currentIp) }
    var email by remember { mutableStateOf(currentEmail) }
    var password by remember { mutableStateOf("") }  // never pre-fill password
    var showPassword by remember { mutableStateOf(false) }
    var localPollSec by remember { mutableStateOf((currentLocalPollMs / 1000).toString()) }
    var remotePollSec by remember { mutableStateOf((currentRemotePollMs / 1000).toString()) }

    // Auto-populate IP field when a scan completes
    LaunchedEffect(scannedIp) {
        if (scannedIp != null) ip = scannedIp
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---- Local WiFi section ----
                if (needsWifi) {
                    SectionHeader("Local Wi-Fi (same network)")
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("Device IP address") },
                        placeholder = { Text("e.g. 192.168.1.42") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (onScanClick != null) {
                                if (isScanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = onScanClick) {
                                        Icon(Icons.Default.Search, contentDescription = "Scan for device")
                                    }
                                }
                            }
                        }
                    )
                    if (isScanning) {
                        Text(
                            "Scanning local network for Anova device…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = localPollSec,
                        onValueChange = { localPollSec = it },
                        label = { Text("Poll interval (seconds)") },
                        placeholder = { Text("Default: ${AnovaSettings.DEFAULT_LOCAL_POLL_MS / 1000}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (needsWifi && needsCloud) HorizontalDivider()

                // ---- Cloud section ----
                if (needsCloud) {
                    SectionHeader("Cloud (remote access)")

                    // Stability warning
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                "This uses an unofficial API reverse-engineered from the Anova app. " +
                                "It may stop working if Anova changes their servers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Anova account email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Anova account password") },
                        placeholder = { Text("Leave blank to keep existing") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide" else "Show"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remotePollSec,
                        onValueChange = { remotePollSec = it },
                        label = { Text("Poll interval (seconds)") },
                        placeholder = { Text("Default: ${AnovaSettings.DEFAULT_REMOTE_POLL_MS / 1000}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Bluetooth — no extra settings needed
                if (!needsWifi && !needsCloud) {
                    Text(
                        "Bluetooth mode requires no configuration. Tap Connect on the main screen to scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val localMs  = (localPollSec.toLongOrNull()  ?: (AnovaSettings.DEFAULT_LOCAL_POLL_MS  / 1000)) * 1000
                val remoteMs = (remotePollSec.toLongOrNull() ?: (AnovaSettings.DEFAULT_REMOTE_POLL_MS / 1000)) * 1000
                onSave(ip.trim(), email.trim(), password, localMs, remoteMs)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
