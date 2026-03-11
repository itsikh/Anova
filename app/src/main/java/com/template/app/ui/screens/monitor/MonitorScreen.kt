package com.template.app.ui.screens.monitor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.template.app.anova.ActiveTransport
import com.template.app.anova.AnovaStatus
import com.template.app.anova.ConnectionMode
import com.template.app.anova.ConnectionState
import com.template.app.anova.ThresholdSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    vm: AnovaViewModel = hiltViewModel()
) {
    val state by vm.deviceState.collectAsState()
    val thresholds by vm.thresholds.collectAsState()
    val mode by vm.connectionMode.collectAsState()
    val active by vm.activeTransport.collectAsState()
    val localIp by vm.localWifiIp.collectAsState()
    val cloudEmail by vm.cloudEmail.collectAsState()
    val localPollMs by vm.localPollMs.collectAsState()
    val remotePollMs by vm.remotePollMs.collectAsState()

    var showThresholdDialog by remember { mutableStateOf(false) }
    var showConnectionSettings by remember { mutableStateOf(false) }

    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) vm.connect() }

    // Request POST_NOTIFICATIONS on Android 13+ (non-blocking — fire and forget)
    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — alerts are optional */ }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun onConnectClicked() {
        // AUTO mode tries WiFi → Cloud; it never uses BLE, so no BLE permissions needed
        if (mode == ConnectionMode.BLUETOOTH) {
            permissionLauncher.launch(blePermissions)
        } else {
            vm.connect()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anova Monitor") },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, "History") }
                    IconButton(onClick = { showThresholdDialog = true }) { Icon(Icons.Default.Tune, "Thresholds") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection mode chips + configure button
            ConnectionModeSelector(
                selected = mode,
                onSelect = { vm.setConnectionMode(it) },
                onConfigure = { showConnectionSettings = true }
            )

            // Badge showing which transport is actually active
            ActiveTransportBadge(active = active, connectionState = state.connectionState)

            HorizontalDivider()

            // Main temperature display
            TemperatureDisplay(
                temp = state.currentTemp,
                unit = state.unit.symbol,
                status = state.status,
                connectionState = state.connectionState
            )

            // Timer
            TimerDisplay(timerMinutes = state.timerMinutes)

            // Threshold violation chips
            ThresholdIndicator(
                temp = state.currentTemp,
                thresholds = thresholds,
                unitSymbol = state.unit.symbol
            )

            HorizontalDivider()

            // Connect / Disconnect
            ConnectionControls(
                connectionState = state.connectionState,
                deviceName = state.deviceName,
                onConnect = ::onConnectClicked,
                onDisconnect = { vm.disconnect() }
            )

            if (state.lastUpdated > 0L) {
                Text(
                    "Last updated: ${formatTime(state.lastUpdated)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showThresholdDialog) {
        ThresholdDialog(
            current = thresholds,
            unitSymbol = state.unit.symbol,
            onConfirm = { vm.updateThresholds(it) },
            onDismiss = { showThresholdDialog = false }
        )
    }

    if (showConnectionSettings) {
        ConnectionSettingsDialog(
            mode = mode,
            currentIp = localIp,
            currentEmail = cloudEmail,
            currentLocalPollMs = localPollMs,
            currentRemotePollMs = remotePollMs,
            onSave = { ip, email, password, localMs, remoteMs ->
                vm.saveLocalSettings(ip, localMs)
                if (email.isNotBlank()) vm.saveCloudSettings(email, password, remoteMs)
            },
            onDismiss = { showConnectionSettings = false }
        )
    }
}

// -----------------------------------------------------------------------------------------
// Connection mode selector
// -----------------------------------------------------------------------------------------

@Composable
private fun ConnectionModeSelector(
    selected: ConnectionMode,
    onSelect: (ConnectionMode) -> Unit,
    onConfigure: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(mode.icon, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
            }
        }
        TextButton(onClick = onConfigure) { Text("Configure connection…") }
    }
}

// -----------------------------------------------------------------------------------------
// Active transport badge
// -----------------------------------------------------------------------------------------

@Composable
private fun ActiveTransportBadge(active: ActiveTransport, connectionState: ConnectionState) {
    if (active == ActiveTransport.NONE || connectionState == ConnectionState.DISCONNECTED) return
    val isCloud = active == ActiveTransport.CLOUD
    AssistChip(
        onClick = {},
        label = {
            Text(
                if (connectionState == ConnectionState.CONNECTED) active.displayName
                else connectionState.displayName,
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingIcon = {
            if (isCloud && connectionState == ConnectionState.CONNECTED) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        },
        colors = if (isCloud && connectionState == ConnectionState.CONNECTED)
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        else AssistChipDefaults.assistChipColors()
    )
}

// -----------------------------------------------------------------------------------------
// Temperature display
// -----------------------------------------------------------------------------------------

@Composable
private fun TemperatureDisplay(
    temp: Float?,
    unit: String,
    status: AnovaStatus,
    connectionState: ConnectionState
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor(status, connectionState))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                statusLabel(status, connectionState),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        if (temp != null) {
            Text(
                "%.1f%s".format(temp, unit),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                "– –  $unit",
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Text("Current Temperature", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimerDisplay(timerMinutes: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (timerMinutes != null) {
                val h = timerMinutes / 60; val m = timerMinutes % 60
                if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
            } else "– – –",
            style = MaterialTheme.typography.titleLarge,
            color = if (timerMinutes != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text("remaining", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThresholdIndicator(temp: Float?, thresholds: ThresholdSettings, unitSymbol: String) {
    if (!thresholds.minTempEnabled && !thresholds.maxTempEnabled) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (thresholds.minTempEnabled) {
            FilledTonalButton(
                onClick = {},
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (temp != null && temp <= thresholds.minTemp)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            ) { Text("Min: %.1f%s".format(thresholds.minTemp, unitSymbol)) }
        }
        if (thresholds.maxTempEnabled) {
            FilledTonalButton(
                onClick = {},
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (temp != null && temp >= thresholds.maxTemp)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            ) { Text("Max: %.1f%s".format(thresholds.maxTemp, unitSymbol)) }
        }
    }
}

@Composable
private fun ConnectionControls(
    connectionState: ConnectionState,
    deviceName: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (deviceName != null) {
            Text(deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (connectionState) {
            ConnectionState.DISCONNECTED -> Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            ConnectionState.SCANNING     -> OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Scanning… (tap to cancel)") }
            ConnectionState.CONNECTING   -> OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Connecting… (tap to cancel)") }
            ConnectionState.CONNECTED    -> OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Disconnect") }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Helpers / extensions
// -----------------------------------------------------------------------------------------

private val ConnectionMode.label get() = when (this) {
    ConnectionMode.BLUETOOTH  -> "BLE"
    ConnectionMode.LOCAL_WIFI -> "Wi-Fi"
    ConnectionMode.CLOUD      -> "Cloud"
    ConnectionMode.AUTO       -> "Auto"
}

private val ConnectionMode.icon get() = when (this) {
    ConnectionMode.BLUETOOTH  -> Icons.Default.Bluetooth
    ConnectionMode.LOCAL_WIFI -> Icons.Default.Wifi
    ConnectionMode.CLOUD      -> Icons.Default.Cloud
    ConnectionMode.AUTO       -> Icons.Default.Home
}

private val ActiveTransport.displayName get() = when (this) {
    ActiveTransport.BLUETOOTH  -> "Via Bluetooth"
    ActiveTransport.LOCAL_WIFI -> "Via local Wi-Fi"
    ActiveTransport.CLOUD      -> "Via cloud (unofficial)"
    ActiveTransport.NONE       -> ""
}

private val ConnectionState.displayName get() = when (this) {
    ConnectionState.DISCONNECTED -> "Disconnected"
    ConnectionState.SCANNING     -> "Scanning…"
    ConnectionState.CONNECTING   -> "Connecting…"
    ConnectionState.CONNECTED    -> "Connected"
}

private fun statusColor(status: AnovaStatus, cs: ConnectionState) = when {
    cs != ConnectionState.CONNECTED  -> Color.Gray
    status == AnovaStatus.RUNNING    -> Color(0xFF4CAF50)
    status == AnovaStatus.STOPPED    -> Color(0xFFFF9800)
    else                             -> Color.Gray
}

private fun statusLabel(status: AnovaStatus, cs: ConnectionState) = when {
    cs == ConnectionState.DISCONNECTED -> "Disconnected"
    cs == ConnectionState.SCANNING     -> "Scanning…"
    cs == ConnectionState.CONNECTING   -> "Connecting…"
    status == AnovaStatus.RUNNING      -> "Running"
    status == AnovaStatus.STOPPED      -> "Stopped"
    else                               -> "Connected"
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTime(millis: Long) = timeFmt.format(Date(millis))
