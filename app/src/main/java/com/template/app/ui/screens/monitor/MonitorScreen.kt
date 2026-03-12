package com.template.app.ui.screens.monitor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.template.app.anova.ActiveTransport
import com.template.app.anova.AnovaStatus
import com.template.app.anova.ConnectionMode
import com.template.app.anova.ConnectionState
import com.template.app.anova.ThresholdSettings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import com.template.app.presets.PresetsSheet
import com.template.app.security.BiometricHelper
import com.template.app.ui.theme.AnovaOrange
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ── Deep Charcoal design palette ─────────────────────────────────────────────
private val DC_Bg          = Color(0xFF0F0F0F)
private val DC_Surface     = Color(0xFF191919)
private val DC_Track       = Color(0xFF1D1D1D)
private val DC_ArcAmber    = Color(0xFFFF9500)
private val DC_ArcRed      = Color(0xFFFF3300)
private val DC_TipDot      = Color(0xFFFF5000)
private val DC_TextPrimary = Color(0xFFEAEAEA)
private val DC_TextDim     = Color(0xFF909090)
private val DC_TextMuted   = Color(0xFF606060)
private val DC_Orange      = Color(0xFFFF6600)
private val DC_AlertBg     = Color(0x1AFF6600)
private val DC_AlertBorder = Color(0x44FF6600)
private val DC_IconBg      = Color(0x12FFFFFF)
private val DC_IconBorder  = Color(0x18FFFFFF)

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
    var showDisconnectConfirm  by remember { mutableStateOf(false) }
    var showPresetsSheet       by remember { mutableStateOf(false) }
    var showSetDialog          by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
        val stored = vm.storedEmail
        if (stored != null && googleSignedInAs == null) googleSignedInAs = stored
    }

    fun onConnectClicked() {
        if (mode == ConnectionMode.BLUETOOTH) permissionLauncher.launch(blePermissions)
        else vm.connect()
    }

    val isConnected = state.connectionState == ConnectionState.CONNECTED
    val alertActive = thresholds.minTempEnabled || thresholds.maxTempEnabled
    val timerFinishEpochMs = state.timerMinutes?.let { System.currentTimeMillis() + it * 60_000L }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "anova",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = DC_Orange,
                        letterSpacing = 0.5.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DC_Bg),
                actions = {
                    IconButton(onClick = { showThresholdDialog = true }) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(
                                    if (alertActive) DC_AlertBg else DC_IconBg,
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    if (alertActive) DC_AlertBorder else DC_IconBorder,
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (alertActive) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                                "Alerts",
                                tint = if (alertActive) DC_Orange else DC_TextDim,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(onClick = onOpenSchedule) {
                        Icon(Icons.Default.DateRange, "Schedule", tint = DC_TextDim)
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, "History", tint = DC_TextDim)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = DC_TextDim)
                    }
                }
            )
        },
        containerColor = DC_Bg
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val ringSize = (maxHeight * 0.33f).coerceIn(160.dp, 220.dp)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Status + device name
            DarkStatusRow(state.connectionState, state.status, active, state.deviceName)

            Spacer(Modifier.height(2.dp))

            // Thermostat ring — sized to fit available screen height
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                ThermostatRing(
                    currentTemp = state.currentTemp,
                    targetTemp = state.targetTemp,
                    unit = state.unit.symbol,
                    ringDp = ringSize
                )
            }

            Spacer(Modifier.height(6.dp))

            // Info row: Target | Remaining | Updated
            InfoRow(
                targetTemp = state.targetTemp,
                unit = state.unit.symbol,
                timerMinutes = state.timerMinutes,
                lastUpdated = state.lastUpdated,
                isConnected = isConnected,
                onTempClick = { if (isConnected) showTempEditDialog = true },
                onTimerClick = { if (isConnected) showTimerEditDialog = true }
            )

            // Finishes row
            if (timerFinishEpochMs != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Finishes ${formatFinishDate(timerFinishEpochMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DC_TextDim,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(6.dp))

            // Alert strip
            if (thresholds.minTempEnabled && thresholds.minTemp > 0f) {
                AlertStrip(
                    minTemp = thresholds.minTemp,
                    unit = state.unit.symbol,
                    isAuto = thresholds.isAutoMin
                )
                Spacer(Modifier.height(8.dp))
            }

            // Threshold breach warning
            val minV = thresholds.minTempEnabled && state.currentTemp != null && state.currentTemp!! <= thresholds.minTemp
            val maxV = thresholds.maxTempEnabled && state.currentTemp != null && state.currentTemp!! >= thresholds.maxTemp
            if (minV || maxV) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Text(
                        buildString {
                            if (minV) append("Below min %.1f°".format(thresholds.minTemp))
                            if (minV && maxV) append("  ·  ")
                            if (maxV) append("Above max %.1f°".format(thresholds.maxTemp))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Cook control + presets — only when connected
            if (isConnected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CookControls(
                        status = state.status,
                        onStart = { vm.startCook() },
                        onStop = { vm.stopCook() },
                        onUpdate = { showSetDialog = true }
                    )
                    PresetsButton(onClick = { showPresetsSheet = true })
                }
                Spacer(Modifier.height(10.dp))
            }

            // Connect / Disconnect
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ActionButton(
                    connectionState = state.connectionState,
                    onConnect = ::onConnectClicked,
                    onDisconnect = { showDisconnectConfirm = true }
                )
            }

            Spacer(Modifier.height(4.dp))

            TextButton(onClick = { showConnectionSettings = true }) {
                Text(
                    "Configure connection",
                    style = MaterialTheme.typography.labelMedium,
                    color = DC_TextDim
                )
            }

            Text(
                if (googleSignedInAs != null) "Signed in as: $googleSignedInAs" else "Not signed in",
                style = MaterialTheme.typography.labelSmall,
                color = if (googleSignedInAs != null) DC_TextMuted else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            if (state.connectionError != null) {
                Text(
                    state.connectionError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
        }
        } // BoxWithConstraints
    }

    // ── Bottom sheets & dialogs ───────────────────────────────────────────────

    if (showPresetsSheet) {
        PresetsSheet(
            useCelsius = state.unit.symbol == "C",
            onDismiss = { showPresetsSheet = false }
        )
    }

    if (showThresholdDialog) {
        ThresholdDialog(
            current = thresholds, unitSymbol = state.unit.symbol,
            onConfirm = { vm.updateThresholds(it) }, onDismiss = { showThresholdDialog = false }
        )
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

    if (showSetDialog) {
        SetCookDialog(
            currentTarget = state.targetTemp,
            unitSymbol = state.unit.symbol,
            currentMinutes = state.timerMinutes,
            onConfirmTemp = { vm.updateTemp(it) },
            onConfirmTimer = { h, m -> vm.updateTimer(h, m) },
            onDismiss = { showSetDialog = false }
        )
    }

    if (showTempEditDialog) {
        TempEditDialog(
            currentTarget = state.targetTemp, unitSymbol = state.unit.symbol,
            onConfirm = { vm.updateTemp(it) }, onDismiss = { showTempEditDialog = false }
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
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Signing in…") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AnovaOrange) } },
            confirmButton = {}
        )
    }

    googleAuthError?.let { err ->
        AlertDialog(
            onDismissRequest = { googleAuthError = null },
            title = { Text("Sign-in failed") }, text = { Text(err) },
            confirmButton = { TextButton(onClick = { googleAuthError = null }) { Text("OK") } }
        )
    }

    controlError?.let { err ->
        AlertDialog(
            onDismissRequest = { vm.dismissControlError() },
            title = { Text("Error") }, text = { Text(err) },
            confirmButton = { TextButton(onClick = { vm.dismissControlError() }) { Text("OK") } }
        )
    }

    googleAuthSession?.let { (authUri, sessionId) ->
        GoogleSignInWebViewDialog(
            authUri = authUri, sessionId = sessionId,
            onAuthRedirectIntercepted = ::onGoogleAuthRedirect,
            onDismiss = { googleAuthSession = null }
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect") },
            text = { Text("Disconnect from the device?") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    BiometricHelper.authenticate(
                        activity = context as FragmentActivity,
                        title = "Confirm disconnect",
                        subtitle = "Authenticate to disconnect from the device",
                        onSuccess = { vm.disconnect() },
                        onError = {}
                    )
                }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") } }
        )
    }
}

// ── Thermostat ring ───────────────────────────────────────────────────────────

@Composable
private fun ThermostatRing(currentTemp: Float?, targetTemp: Float?, unit: String, ringDp: Dp = 220.dp) {
    val fillFraction = remember(currentTemp, targetTemp) {
        if (currentTemp != null && targetTemp != null && targetTemp > 0f)
            (currentTemp / targetTemp).coerceIn(0f, 1f)
        else 0f
    }
    val animatedFill by animateFloatAsState(
        targetValue = fillFraction,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "arcFill"
    )
    val arcColor = lerp(DC_ArcAmber, DC_ArcRed, animatedFill)

    Box(
        modifier = Modifier.size(ringDp),
        contentAlignment = Alignment.Center
    ) {
        val strokeDp = 14.dp
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeDp.toPx()
            val radius   = (size.minDimension - strokePx) / 2f
            val topLeft  = Offset((size.width - radius * 2) / 2f, (size.height - radius * 2) / 2f)
            val arcSize  = Size(radius * 2, radius * 2)
            val center   = Offset(size.width / 2f, size.height / 2f)

            // Track
            drawArc(
                color      = DC_Track,
                startAngle = 140f,
                sweepAngle = 280f,
                useCenter  = false,
                topLeft    = topLeft,
                size       = arcSize,
                style      = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // Animated fill
            val fillSweep = animatedFill * 280f
            if (fillSweep > 1f) {
                drawArc(
                    color      = arcColor,
                    startAngle = 140f,
                    sweepAngle = fillSweep,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSize,
                    style      = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                // Glowing tip dot
                val tipAngleDeg = 140f + fillSweep
                val tipRad      = Math.toRadians(tipAngleDeg.toDouble())
                val tipX        = center.x + radius * cos(tipRad).toFloat()
                val tipY        = center.y + radius * sin(tipRad).toFloat()
                drawCircle(
                    color  = DC_TipDot,
                    radius = strokePx / 2f + 2.dp.toPx(),
                    center = Offset(tipX, tipY)
                )
            }
        }

        // Center text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = currentTemp,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "ringTemp"
            ) { t ->
                Text(
                    text       = if (t != null) "%.1f".format(t) else "–",
                    fontSize   = 62.sp,
                    fontWeight = FontWeight.Bold,
                    color      = DC_TextPrimary,
                    letterSpacing = (-2).sp
                )
            }
            Text(unit, fontSize = 15.sp, fontWeight = FontWeight.Light, color = DC_TextDim)
            Spacer(Modifier.height(2.dp))
            Text(
                "CURRENT",
                fontSize      = 9.sp,
                letterSpacing = 2.sp,
                color         = DC_TextMuted,
                fontWeight    = FontWeight.Medium
            )
        }
    }
}

// ── Status row ────────────────────────────────────────────────────────────────

@Composable
private fun DarkStatusRow(
    connectionState: ConnectionState,
    status: AnovaStatus,
    activeTransport: ActiveTransport,
    deviceName: String?
) {
    val dotColor = when {
        connectionState != ConnectionState.CONNECTED -> Color(0xFF333333)
        status == AnovaStatus.RUNNING               -> Color(0xFF4CAF50)
        status == AnovaStatus.STOPPED               -> Color(0xFFFF9500)
        else                                        -> Color(0xFF444444)
    }
    val dotGlow = when {
        connectionState == ConnectionState.CONNECTED && status == AnovaStatus.RUNNING -> Color(0xFF4CAF50)
        connectionState == ConnectionState.CONNECTED && status == AnovaStatus.STOPPED -> Color(0xFFFF9500)
        else -> Color.Transparent
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                buildString {
                    append(dcStatusLabel(status, connectionState))
                    if (connectionState == ConnectionState.CONNECTED && activeTransport != ActiveTransport.NONE) {
                        append(" · ")
                        append(activeTransport.displayName)
                    }
                },
                style     = MaterialTheme.typography.labelSmall,
                color     = DC_TextDim,
                fontWeight = FontWeight.Medium
            )
        }
        if (deviceName != null && connectionState == ConnectionState.CONNECTED) {
            Text(
                deviceName,
                style  = MaterialTheme.typography.labelSmall,
                color  = DC_TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Info row (Target | Remaining | Updated) ───────────────────────────────────

@Composable
private fun InfoRow(
    targetTemp: Float?, unit: String, timerMinutes: Int?, lastUpdated: Long,
    isConnected: Boolean, onTempClick: () -> Unit, onTimerClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Target
        InfoCell(
            label    = "TARGET",
            value    = if (targetTemp != null) "%.1f%s".format(targetTemp, unit) else "–",
            isAccent = targetTemp != null,
            onClick  = if (isConnected && targetTemp != null) onTempClick else null
        )

        Box(Modifier.width(1.dp).height(30.dp).background(Color(0xFF2A2A2A)))

        // Remaining
        InfoCell(
            label    = "REMAINING",
            value    = formatTimer(timerMinutes),
            isAccent = false,
            onClick  = if (isConnected) onTimerClick else null
        )

        Box(Modifier.width(1.dp).height(30.dp).background(Color(0xFF2A2A2A)))

        // Updated
        InfoCell(
            label    = "UPDATED",
            value    = if (lastUpdated > 0L) formatHHmm(lastUpdated) else "–",
            isAccent = false,
            onClick  = null
        )
    }
}

@Composable
private fun InfoCell(label: String, value: String, isAccent: Boolean, onClick: (() -> Unit)?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            fontSize      = 9.sp,
            letterSpacing = 1.2.sp,
            fontWeight    = FontWeight.SemiBold,
            color         = DC_TextMuted
        )
        // Fixed height keeps all three cells vertically aligned regardless of whether
        // the value is wrapped in a TextButton (48dp min-touch target) or plain Text.
        Box(
            modifier = Modifier.height(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (onClick != null) {
                TextButton(
                    onClick = onClick,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        value,
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = if (isAccent) DC_Orange else DC_TextDim
                    )
                }
            } else {
                Text(
                    value,
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (isAccent) DC_Orange else DC_TextDim
                )
            }
        }
    }
}

// ── Alert strip ───────────────────────────────────────────────────────────────

@Composable
private fun AlertStrip(minTemp: Float, unit: String, isAuto: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(DC_AlertBg, RoundedCornerShape(14.dp))
            .border(1.dp, DC_AlertBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.NotificationsActive, null, tint = DC_Orange, modifier = Modifier.size(16.dp))
        Text(
            "Alert below %.1f%s%s".format(minTemp, unit, if (isAuto) " (auto)" else ""),
            style      = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color      = DC_Orange
        )
    }
}

// ── Cook controls (Start · Stop · Update temp) ────────────────────────────────

@Composable
private fun CookControls(
    status: AnovaStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUpdate: () -> Unit
) {
    val isRunning = status == AnovaStatus.RUNNING
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Start
        Button(
            onClick = onStart,
            modifier = Modifier.weight(1f).height(52.dp),
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E7D32),
                contentColor   = Color.White,
                disabledContainerColor = Color(0xFF1A3D1A),
                disabledContentColor   = Color(0xFF4A7A4A)
            ),
            enabled = !isRunning
        ) {
            Text("Start", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        // Stop
        Button(
            onClick = onStop,
            modifier = Modifier.weight(1f).height(52.dp),
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD32F2F),
                contentColor   = Color.White,
                disabledContainerColor = Color(0xFF3D1A1A),
                disabledContentColor   = Color(0xFF7A4A4A)
            ),
            enabled = isRunning
        ) {
            Text("Stop", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        // Update (set target temp + timer)
        OutlinedButton(
            onClick = onUpdate,
            modifier = Modifier.weight(1f).height(52.dp),
            shape  = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DC_Track),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DC_TextDim)
        ) {
            Icon(Icons.Default.Tune, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("Set", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

// ── Presets button ────────────────────────────────────────────────────────────

@Composable
private fun PresetsButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape  = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, DC_AlertBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DC_Orange)
    ) {
        Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text("Presets", fontWeight = FontWeight.SemiBold)
    }
}

// ── Connect/Disconnect button ─────────────────────────────────────────────────

@Composable
private fun ActionButton(connectionState: ConnectionState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    when (connectionState) {
        ConnectionState.DISCONNECTED -> Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DC_Orange, contentColor = Color.White)
        ) { Text("Connect", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }

        ConnectionState.SCANNING, ConnectionState.CONNECTING -> OutlinedButton(
            onClick  = onDisconnect,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape    = RoundedCornerShape(16.dp),
            border   = BorderStroke(1.dp, DC_Track),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = DC_TextDim)
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = DC_Orange)
            Spacer(Modifier.width(10.dp))
            Text(
                if (connectionState == ConnectionState.SCANNING) "Scanning…" else "Connecting…",
                style = MaterialTheme.typography.labelLarge
            )
        }

        ConnectionState.CONNECTED -> OutlinedButton(
            onClick  = onDisconnect,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape    = RoundedCornerShape(16.dp),
            border   = BorderStroke(1.dp, DC_Track),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = DC_TextDim)
        ) { Text("Disconnect", style = MaterialTheme.typography.labelLarge) }
    }
}

// ── Combined Set dialog (temp + timer) ────────────────────────────────────────

@Composable
private fun SetCookDialog(
    currentTarget: Float?,
    unitSymbol: String,
    currentMinutes: Int?,
    onConfirmTemp: (Float) -> Unit,
    onConfirmTimer: (hours: Int, minutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var tempText by remember { mutableStateOf(currentTarget?.let { "%.1f".format(it) } ?: "") }
    var hours    by remember { mutableStateOf(currentMinutes?.div(60)?.toString() ?: "0") }
    var minutes  by remember { mutableStateOf(currentMinutes?.rem(60)?.toString() ?: "0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Temperature & Timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Temperature section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Temperature", style = MaterialTheme.typography.labelMedium, color = DC_TextDim)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tempText,
                            onValueChange = { tempText = it },
                            label = { Text("Target (°$unitSymbol)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                tempText.toFloatOrNull()?.let { onConfirmTemp(it); onDismiss() }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DC_Orange),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Set") }
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))

                // Timer section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Timer", style = MaterialTheme.typography.labelMedium, color = DC_TextDim)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hours, onValueChange = { hours = it },
                            label = { Text("h") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minutes, onValueChange = { minutes = it },
                            label = { Text("min") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                onConfirmTimer(hours.toIntOrNull() ?: 0, minutes.toIntOrNull() ?: 0)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DC_Orange),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Set") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
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
            TextButton(onClick = { text.toFloatOrNull()?.let { onConfirm(it); onDismiss() } }) { Text("Set") }
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

private fun dcStatusLabel(status: AnovaStatus, cs: ConnectionState) = when {
    cs == ConnectionState.DISCONNECTED -> "Not connected"
    cs == ConnectionState.SCANNING     -> "Scanning…"
    cs == ConnectionState.CONNECTING   -> "Connecting…"
    status == AnovaStatus.RUNNING      -> "Running"
    status == AnovaStatus.STOPPED      -> "Stopped"
    else                               -> "Connected"
}

private val ActiveTransport.displayName get() = when (this) {
    ActiveTransport.BLUETOOTH  -> "Bluetooth"
    ActiveTransport.LOCAL_WIFI -> "Wi-Fi"
    ActiveTransport.CLOUD      -> "Cloud"
    ActiveTransport.NONE       -> ""
}

private val hhmm       = SimpleDateFormat("HH:mm", Locale.getDefault())
private val finishFmt  = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
private fun formatHHmm(millis: Long): String = hhmm.format(Date(millis))
private fun formatFinishDate(millis: Long): String = finishFmt.format(Date(millis))
