package com.template.app.ui.screens.monitor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.template.app.anova.ActiveTransport
import com.template.app.anova.AnovaStatus
import com.template.app.anova.ConnectionMode
import com.template.app.anova.ConnectionState
import com.template.app.anova.ThresholdSettings
import com.template.app.logging.AppLogger
import com.template.app.ui.theme.AnovaOrange
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    vm: AnovaViewModel = hiltViewModel()
) {
    val state          by vm.displayDeviceState.collectAsState()
    val thresholds     by vm.thresholds.collectAsState()
    val mode           by vm.connectionMode.collectAsState()
    val active         by vm.activeTransport.collectAsState()
    val localIp        by vm.localWifiIp.collectAsState()
    val cloudEmail     by vm.cloudEmail.collectAsState()
    val localPollMs    by vm.localPollMs.collectAsState()
    val remotePollMs   by vm.remotePollMs.collectAsState()
    val isScanning     by vm.isScanning.collectAsState()
    val scannedIp      by vm.scannedIp.collectAsState()
    val controlError   by vm.controlError.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    var showThresholdDialog    by remember { mutableStateOf(false) }
    var showConnectionSettings by remember { mutableStateOf(false) }
    var showTempEditDialog     by remember { mutableStateOf(false) }
    var showTimerEditDialog    by remember { mutableStateOf(false) }
    var googleSignedInAs       by remember { mutableStateOf<String?>(null) }
    var googleAuthSession      by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isLoadingGoogleAuth    by remember { mutableStateOf(false) }
    var googleAuthError        by remember { mutableStateOf<String?>(null) }

    fun launchGoogleSignIn() {
        showConnectionSettings = false
        googleAuthError = null
        googleAuthSession = vm.createGoogleAuthSession()
    }

    fun onGoogleAuthRedirect(redirectUrl: String, sessionId: String) {
        googleAuthSession = null
        isLoadingGoogleAuth = true
        coroutineScope.launch {
            val result = vm.signInWithGoogleRedirect(redirectUrl, sessionId)
            isLoadingGoogleAuth = false
            if (result != null) googleSignedInAs = "Google account"
            else googleAuthError = "Google Sign-In failed — could not exchange credentials."
        }
    }

    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) vm.connect() }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Seed auth status from persisted session on first composition
        val stored = vm.storedEmail
        if (stored != null && googleSignedInAs == null) googleSignedInAs = stored
    }

    fun onConnectClicked() {
        if (mode == ConnectionMode.BLUETOOTH) permissionLauncher.launch(blePermissions)
        else vm.connect()
    }

    val isConnected = state.connectionState == ConnectionState.CONNECTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("anova", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = AnovaOrange, letterSpacing = 1.sp)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { showThresholdDialog = true }) {
                        Icon(Icons.Default.Tune, "Alerts", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSchedule) {
                        Icon(Icons.Default.DateRange, "Schedule", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, "History", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status row
            DeviceStatusRow(state.connectionState, state.status, active, state.deviceName)

            // Main readout card
            ReadoutCard(
                temp = state.currentTemp,
                targetTemp = state.targetTemp,
                unit = state.unit.symbol,
                timerMinutes = state.timerMinutes,
                status = state.status,
                connectionState = state.connectionState,
                thresholds = thresholds,
                onTempClick = { if (isConnected) showTempEditDialog = true },
                onTimerClick = { if (isConnected) showTimerEditDialog = true }
            )

            // Cook control (Start/Stop) — only when connected
            if (isConnected) {
                CookControlButton(
                    status = state.status,
                    onStart = { vm.startCook() },
                    onStop = { vm.stopCook() }
                )
            }

            // Connect / Disconnect
            ActionButton(
                connectionState = state.connectionState,
                onConnect = ::onConnectClicked,
                onDisconnect = { vm.disconnect() }
            )

            TextButton(onClick = { showConnectionSettings = true }) {
                Text("Configure connection", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Auth status — show signed-in email or "Not signed in"
            Text(
                if (googleSignedInAs != null) "Signed in as: $googleSignedInAs"
                else "Not signed in",
                style = MaterialTheme.typography.labelSmall,
                color = if (googleSignedInAs != null)
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            if (state.connectionError != null) {
                Text(state.connectionError!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }

            if (state.lastUpdated > 0L) {
                Text("Updated ${formatTime(state.lastUpdated)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showThresholdDialog) {
        ThresholdDialog(current = thresholds, unitSymbol = state.unit.symbol,
            onConfirm = { vm.updateThresholds(it) }, onDismiss = { showThresholdDialog = false })
    }

    if (showConnectionSettings) {
        ConnectionSettingsDialog(
            mode = mode, currentIp = localIp, currentEmail = cloudEmail,
            currentLocalPollMs = localPollMs, currentRemotePollMs = remotePollMs,
            isScanning = isScanning, scannedIp = scannedIp,
            onScanClick = { vm.scanForDevice() },
            onGoogleSignInClick = ::launchGoogleSignIn,
            googleSignedInAs = googleSignedInAs,
            onSeedRefreshToken = { token, email ->
                coroutineScope.launch {
                    val ok = vm.seedRefreshToken(token, email)
                    if (ok) googleSignedInAs = email ?: "Saved session"
                    else googleAuthError = "Invalid refresh token — re-authenticate via the HTML page."
                }
            },
            onSave = { ip, email, password, localMs, remoteMs ->
                vm.saveLocalSettings(ip, localMs)
                if (email.isNotBlank()) vm.saveCloudSettings(email, password, remoteMs)
            },
            onDismiss = { showConnectionSettings = false }
        )
    }

    if (showTempEditDialog) {
        TempEditDialog(
            currentTarget = state.targetTemp,
            unitSymbol = state.unit.symbol,
            onConfirm = { vm.updateTemp(it) },
            onDismiss = { showTempEditDialog = false }
        )
    }

    if (showTimerEditDialog) {
        TimerEditDialog(
            currentMinutes = state.timerMinutes,
            onConfirm = { h, m -> vm.updateTimer(h, m) },
            onDismiss = { showTimerEditDialog = false }
        )
    }

    if (isLoadingGoogleAuth) {
        AlertDialog(onDismissRequest = {},
            title = { Text("Signing in…") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AnovaOrange) } },
            confirmButton = {})
    }

    googleAuthError?.let { err ->
        AlertDialog(onDismissRequest = { googleAuthError = null },
            title = { Text("Sign-in failed") }, text = { Text(err) },
            confirmButton = { TextButton(onClick = { googleAuthError = null }) { Text("OK") } })
    }

    controlError?.let { err ->
        AlertDialog(onDismissRequest = { vm.dismissControlError() },
            title = { Text("Error") }, text = { Text(err) },
            confirmButton = { TextButton(onClick = { vm.dismissControlError() }) { Text("OK") } })
    }

    googleAuthSession?.let { (authUri, sessionId) ->
        GoogleSignInWebViewDialog(authUri = authUri, sessionId = sessionId,
            onAuthRedirectIntercepted = ::onGoogleAuthRedirect,
            onDismiss = { googleAuthSession = null })
    }
}

// ── Cook control button ────────────────────────────────────────────────────────

@Composable
private fun CookControlButton(status: AnovaStatus, onStart: () -> Unit, onStop: () -> Unit) {
    when (status) {
        AnovaStatus.RUNNING -> Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("Stop Cook", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }

        AnovaStatus.STOPPED, AnovaStatus.UNKNOWN -> Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White)
        ) { Text("Start Cook", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
    }
}

// ── Device status row ─────────────────────────────────────────────────────────

@Composable
private fun DeviceStatusRow(
    connectionState: ConnectionState, status: AnovaStatus,
    activeTransport: ActiveTransport, deviceName: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(statusDotColor(status, connectionState)))
        Text(statusLabel(status, connectionState), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (connectionState == ConnectionState.CONNECTED && activeTransport != ActiveTransport.NONE) {
            Text("·", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            Text(activeTransport.displayName, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
    if (deviceName != null && connectionState == ConnectionState.CONNECTED) {
        Text(deviceName, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = TextAlign.Center)
    }
}

// ── Readout card ──────────────────────────────────────────────────────────────

@Composable
private fun ReadoutCard(
    temp: Float?, targetTemp: Float?, unit: String, timerMinutes: Int?,
    status: AnovaStatus, connectionState: ConnectionState, thresholds: ThresholdSettings,
    onTempClick: () -> Unit, onTimerClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AnimatedContent(targetState = temp,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "temp") { t ->
                if (t != null) {
                    Text("%.1f%s".format(t, unit), fontSize = 80.sp, fontWeight = FontWeight.Bold,
                        color = AnovaOrange, textAlign = TextAlign.Center)
                } else {
                    Text("– –  $unit", fontSize = 72.sp, fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        textAlign = TextAlign.Center)
                }
            }

            // Target temp — tappable when connected
            if (targetTemp != null && connectionState == ConnectionState.CONNECTED) {
                TextButton(onClick = onTempClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Target  %.1f%s".format(targetTemp, unit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Current Temperature", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }

            Spacer(Modifier.height(20.dp))

            // Timer — tappable when connected
            if (connectionState == ConnectionState.CONNECTED) {
                TextButton(onClick = onTimerClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.Timer, null,
                        tint = if (timerMinutes != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(formatTimer(timerMinutes),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                        color = if (timerMinutes != null) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    if (timerMinutes != null) {
                        Spacer(Modifier.width(4.dp))
                        Text("remaining", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Timer, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(formatTimer(timerMinutes), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                }
            }

            // Threshold alerts
            val minV = thresholds.minTempEnabled && temp != null && temp <= thresholds.minTemp
            val maxV = thresholds.maxTempEnabled && temp != null && temp >= thresholds.maxTemp
            if (minV || maxV) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Text(buildString {
                        if (minV) append("Below min %.1f°".format(thresholds.minTemp))
                        if (minV && maxV) append("  ·  ")
                        if (maxV) append("Above max %.1f°".format(thresholds.maxTemp))
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ── Connect/Disconnect button ─────────────────────────────────────────────────

@Composable
private fun ActionButton(connectionState: ConnectionState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    when (connectionState) {
        ConnectionState.DISCONNECTED -> Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AnovaOrange, contentColor = Color.White)
        ) { Text("Connect", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }

        ConnectionState.SCANNING, ConnectionState.CONNECTING -> OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AnovaOrange)
            Spacer(Modifier.width(10.dp))
            Text(if (connectionState == ConnectionState.SCANNING) "Scanning…" else "Connecting…",
                style = MaterialTheme.typography.labelLarge)
        }

        ConnectionState.CONNECTED -> OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        ) { Text("Disconnect", style = MaterialTheme.typography.labelLarge) }
    }
}

// ── Temperature edit dialog ───────────────────────────────────────────────────

@Composable
private fun TempEditDialog(currentTarget: Float?, unitSymbol: String, onConfirm: (Float) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(currentTarget?.let { "%.1f".format(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Target Temperature") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Temperature ($unitSymbol)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                text.toFloatOrNull()?.let { onConfirm(it); onDismiss() }
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Timer edit dialog ─────────────────────────────────────────────────────────

@Composable
private fun TimerEditDialog(currentMinutes: Int?, onConfirm: (hours: Int, minutes: Int) -> Unit, onDismiss: () -> Unit) {
    var hours   by remember { mutableStateOf(currentMinutes?.div(60)?.toString() ?: "0") }
    var minutes by remember { mutableStateOf(currentMinutes?.rem(60)?.toString() ?: "0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Timer") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hours, onValueChange = { hours = it },
                    label = { Text("Hours") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minutes, onValueChange = { minutes = it },
                    label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hours.toIntOrNull() ?: 0
                val m = minutes.toIntOrNull() ?: 0
                onConfirm(h, m); onDismiss()
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatTimer(timerMinutes: Int?): String {
    if (timerMinutes == null) return "– –"
    val h = timerMinutes / 60; val m = timerMinutes % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
}

private val ActiveTransport.displayName get() = when (this) {
    ActiveTransport.BLUETOOTH  -> "Bluetooth"
    ActiveTransport.LOCAL_WIFI -> "Local Wi-Fi"
    ActiveTransport.CLOUD      -> "Cloud"
    ActiveTransport.NONE       -> ""
}

private fun statusDotColor(status: AnovaStatus, cs: ConnectionState) = when {
    cs != ConnectionState.CONNECTED -> Color(0xFFAAAAAA)
    status == AnovaStatus.RUNNING   -> Color(0xFF4CAF50)
    status == AnovaStatus.STOPPED   -> Color(0xFFFF9800)
    else                            -> Color(0xFFAAAAAA)
}

private fun statusLabel(status: AnovaStatus, cs: ConnectionState) = when {
    cs == ConnectionState.DISCONNECTED -> "Not connected"
    cs == ConnectionState.SCANNING     -> "Scanning…"
    cs == ConnectionState.CONNECTING   -> "Connecting…"
    status == AnovaStatus.RUNNING      -> "Running"
    status == AnovaStatus.STOPPED      -> "Stopped"
    else                               -> "Connected"
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTime(millis: Long) = timeFmt.format(Date(millis))
