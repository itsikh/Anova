package com.template.app.anova

import android.content.Context
import android.content.Intent
import com.template.app.anova.cloud.AnovaCloudTransport
import com.template.app.history.TemperatureReading
import com.template.app.history.TemperatureReadingDao
import com.template.app.logging.AppLogger
import com.template.app.notifications.AnovaAlertManager
import com.template.app.security.SecureKeyManager
import com.template.app.service.AnovaMonitorService
import com.template.app.widget.AnovaWidgetReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.glance.appwidget.updateAll
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaRepo"
private const val AUTO_WIFI_TIMEOUT_MS = 6_000L

enum class ActiveTransport { BLUETOOTH, LOCAL_WIFI, CLOUD, NONE }

@Singleton
class AnovaRepository @Inject constructor(
    val bleTransport: AnovaBluetoothManager,
    val wifiTransport: AnovaWifiTransport,
    val cloudTransport: AnovaCloudTransport,
    private val settings: AnovaSettings,
    private val readingDao: TemperatureReadingDao,
    private val secureKeyManager: SecureKeyManager,
    private val alertManager: AnovaAlertManager,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val KEY_CLOUD_PASSWORD = "anova_cloud_password"
    }

    private val _deviceState = MutableStateFlow(AnovaDeviceState())
    val deviceState: StateFlow<AnovaDeviceState> = _deviceState.asStateFlow()

    private val _activeTransport = MutableStateFlow(ActiveTransport.NONE)
    val activeTransport: StateFlow<ActiveTransport> = _activeTransport.asStateFlow()

    private var pollingJob: Job? = null
    private var currentPollIntervalMs = AnovaSettings.DEFAULT_REMOTE_POLL_MS
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Alert de-dup state
    private var minAlertFired = false
    private var maxAlertFired = false
    private var prevStatus = AnovaStatus.UNKNOWN
    private var lastHistorySampleMs = 0L

    init {
        observeTransport(bleTransport, ActiveTransport.BLUETOOTH)
        observeTransport(wifiTransport, ActiveTransport.LOCAL_WIFI)
        observeTransport(cloudTransport, ActiveTransport.CLOUD)
        observeCloudErrors()
        observeCloudPushes()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        scope.launch {
            _deviceState.update { it.copy(connectionError = null) }
            val mode     = settings.connectionMode.first()
            val ip       = settings.localWifiIp.first()
            val email    = settings.cloudEmail.first()
            val password = secureKeyManager.getKey(KEY_CLOUD_PASSWORD) ?: ""
            val localMs  = settings.localPollMs.first()
            val remoteMs = settings.remotePollMs.first()

            settings.setAutoReconnectOnBoot(true)
            when (mode) {
                ConnectionMode.BLUETOOTH  -> { currentPollIntervalMs = localMs; _activeTransport.value = ActiveTransport.BLUETOOTH; bleTransport.connect() }
                ConnectionMode.LOCAL_WIFI -> connectWifi(ip, localMs)
                ConnectionMode.CLOUD      -> connectCloud(email, password, remoteMs)
                ConnectionMode.AUTO       -> connectAuto(ip, email, password, localMs, remoteMs)
            }
            startService()
        }
    }

    fun disconnect() {
        scope.launch { settings.setAutoReconnectOnBoot(false) }
        stopPolling()
        bleTransport.disconnect()
        wifiTransport.disconnect()
        cloudTransport.disconnect()
        _activeTransport.value = ActiveTransport.NONE
        _deviceState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
        alertManager.cancelCookNotification()
        stopService()
    }

    fun setGoogleToken(token: String) = cloudTransport.setGoogleIdToken(token)
    fun useGoogleSsoSession()          = cloudTransport.useGoogleSsoSession()
    suspend fun discoverDevice(): String? = wifiTransport.discoverDevice()

    // ── Control ───────────────────────────────────────────────────────────────

    suspend fun startCook(): Boolean = cloudTransport.startCook()

    suspend fun stopCook(): Boolean {
        // Do NOT optimistically update status here — ws.send() only confirms the message
        // was enqueued in OkHttp's buffer, not that the device received or acted on it.
        // The real status change arrives via EVENT_APC_STATE push from the cloud.
        return cloudTransport.stopCook()
    }

    suspend fun updateCook(targetTemp: Float? = null, timerSeconds: Int? = null): Boolean =
        cloudTransport.updateCook(targetTemp, timerSeconds)

    /** Add 1 hour to the current timer. */
    suspend fun addHour(): Boolean {
        val currentMin = _deviceState.value.timerMinutes ?: return false
        val newSeconds = (currentMin + 60) * 60
        return cloudTransport.updateCook(timerSeconds = newSeconds)
    }

    // ── Connection helpers ────────────────────────────────────────────────────

    private suspend fun connectWifi(ip: String, localMs: Long) {
        currentPollIntervalMs = localMs
        val resolvedIp = if (ip.isBlank()) {
            _deviceState.update { it.copy(connectionState = ConnectionState.SCANNING) }
            val found = wifiTransport.discoverDevice()
            if (found == null) {
                _deviceState.update { it.copy(connectionState = ConnectionState.DISCONNECTED, connectionError = "No Anova device found on this network.") }
                return
            }
            settings.setLocalWifiIp(found); found
        } else ip
        _activeTransport.value = ActiveTransport.LOCAL_WIFI
        wifiTransport.connect(resolvedIp)
    }

    private fun connectCloud(email: String, password: String, remoteMs: Long) {
        currentPollIntervalMs = remoteMs
        _activeTransport.value = ActiveTransport.CLOUD
        cloudTransport.setCredentials(email, password)
        cloudTransport.connect()
    }

    private suspend fun connectAuto(ip: String, email: String, password: String, localMs: Long, remoteMs: Long) {
        val resolvedIp = if (ip.isBlank()) {
            _deviceState.update { it.copy(connectionState = ConnectionState.SCANNING) }
            val found = wifiTransport.discoverDevice()
            if (found != null) settings.setLocalWifiIp(found)
            found
        } else ip

        if (resolvedIp.isNullOrBlank()) {
            AppLogger.i(TAG, "AUTO: no local device, falling back to cloud")
            switchToCloud(email, password, remoteMs)
            return
        }

        currentPollIntervalMs = localMs
        _activeTransport.value = ActiveTransport.LOCAL_WIFI
        wifiTransport.connect(resolvedIp)

        val connected = withTimeoutOrNull(AUTO_WIFI_TIMEOUT_MS) {
            wifiTransport.connectionState.first { it == ConnectionState.CONNECTED }
        }
        if (connected == null) {
            wifiTransport.disconnect()
            _activeTransport.value = ActiveTransport.NONE
            switchToCloud(email, password, remoteMs)
        }
    }

    private fun switchToCloud(email: String, password: String, remoteMs: Long) {
        if (email.isBlank()) {
            // Try stored session (Anova JWT / Google SSO)
            currentPollIntervalMs = remoteMs
            _activeTransport.value = ActiveTransport.CLOUD
            cloudTransport.useGoogleSsoSession()
            cloudTransport.connect()
            return
        }
        currentPollIntervalMs = remoteMs
        _activeTransport.value = ActiveTransport.CLOUD
        cloudTransport.setCredentials(email, password)
        cloudTransport.connect()
    }

    // ── Transport observation ─────────────────────────────────────────────────

    private fun observeTransport(transport: AnovaTransport, type: ActiveTransport) {
        scope.launch {
            transport.connectionState.collect { state ->
                if (_activeTransport.value != type) return@collect
                AppLogger.d(TAG, "$type → $state")
                _deviceState.update { it.copy(connectionState = state) }
                when (state) {
                    ConnectionState.CONNECTED    -> { _deviceState.update { it.copy(connectionError = null) }; purgeOldHistory(); startPolling() }
                    ConnectionState.DISCONNECTED -> {
                        stopPolling()
                        val wasRunning = _deviceState.value.status == AnovaStatus.RUNNING
                        _deviceState.update { it.copy(currentTemp = null, targetTemp = null, timerMinutes = null, status = AnovaStatus.UNKNOWN) }
                        alertManager.cancelCookNotification()
                        if (wasRunning) checkAlert(settings.alertDeviceOffline) {
                            alertManager.postEventAlert("Anova offline", "Lost connection to your device.", AnovaAlertManager.NOTIFICATION_ID_OFFLINE)
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            transport.deviceName.collect { name ->
                if (_activeTransport.value == type) _deviceState.update { it.copy(deviceName = name) }
            }
        }
    }

    private fun observeCloudErrors() {
        scope.launch {
            cloudTransport.lastError.collect { error ->
                if (_activeTransport.value == ActiveTransport.CLOUD && error != null) {
                    _deviceState.update { it.copy(connectionError = error) }
                }
            }
        }
    }

    /** Observe real-time WebSocket pushes from the cloud transport. */
    private fun observeCloudPushes() {
        scope.launch {
            cloudTransport.rawStateFlow?.collect { raw ->
                if (_activeTransport.value == ActiveTransport.CLOUD) {
                    applyRawState(raw)
                }
            }
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                poll()
                delay(currentPollIntervalMs)
            }
        }
    }

    private fun stopPolling() { pollingJob?.cancel(); pollingJob = null }

    private suspend fun poll() {
        val transport: AnovaTransport = when (_activeTransport.value) {
            ActiveTransport.BLUETOOTH  -> bleTransport
            ActiveTransport.LOCAL_WIFI -> wifiTransport
            ActiveTransport.CLOUD      -> cloudTransport
            ActiveTransport.NONE       -> return
        }
        try {
            val raw = transport.poll() ?: return
            applyRawState(raw)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Poll error: ${e.message}")
        }
    }

    private fun applyRawState(raw: AnovaRawState) {
        val prev = _deviceState.value
        _deviceState.update {
            it.copy(
                currentTemp  = raw.currentTemp,
                targetTemp   = raw.targetTemp,
                unit         = raw.unit,
                timerMinutes = raw.timerMinutes,
                status       = raw.status,
                lastUpdated  = System.currentTimeMillis()
            )
        }
        checkAlerts(prev, raw)
        maybeLogHistory(raw)
        updateCookNotification(raw)
        scope.launch { AnovaWidgetReceiver().glanceAppWidget.updateAll(context) }
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    private fun checkAlerts(prev: AnovaDeviceState, raw: AnovaRawState) {
        val temp = raw.currentTemp ?: return

        // Threshold alerts — these use the caller's thresholds from AnovaViewModel;
        // handled in AnovaViewModel.checkThresholds(). Here we handle event alerts.

        // Cook finished: was running, now stopped, timer was counting
        if (prev.status == AnovaStatus.RUNNING && raw.status == AnovaStatus.STOPPED) {
            checkAlert(settings.alertCookFinished) {
                alertManager.postEventAlert("Cook finished", "Your Anova cook has completed.", AnovaAlertManager.NOTIFICATION_ID_COOK_DONE)
            }
            alertManager.cancelCookNotification()
        }

        // Cook started remotely: was not running, now running
        if (prev.status != AnovaStatus.RUNNING && raw.status == AnovaStatus.RUNNING && prevStatus != AnovaStatus.UNKNOWN) {
            checkAlert(settings.alertCookStarted) {
                alertManager.postEventAlert("Cook started", "Your Anova device started a cook.", AnovaAlertManager.NOTIFICATION_ID_COOK_START)
            }
        }

        // Temperature reached target
        val target = raw.targetTemp
        if (target != null && prev.currentTemp != null && raw.currentTemp != null) {
            val reachedNow = raw.currentTemp >= target - 0.3f
            val wasBelow = prev.currentTemp < target - 0.3f
            if (wasBelow && reachedNow) {
                checkAlert(settings.alertTempTarget) {
                    alertManager.postEventAlert(
                        "Target temp %.1f%s reached".format(target, raw.unit.symbol),
                        "Water is up to temperature — cook timer is running",
                        AnovaAlertManager.NOTIFICATION_ID_TEMP_TARGET
                    )
                }
            }
        }

        prevStatus = raw.status
    }

    private fun checkAlert(settingFlow: kotlinx.coroutines.flow.Flow<Boolean>, action: () -> Unit) {
        scope.launch {
            if (settingFlow.first()) action()
        }
    }

    private fun updateCookNotification(raw: AnovaRawState) {
        if (raw.status == AnovaStatus.RUNNING) {
            alertManager.updateCookNotification(raw.currentTemp, raw.targetTemp, raw.timerMinutes, raw.unit.symbol)
        }
    }

    // ── History ───────────────────────────────────────────────────────────────

    private fun maybeLogHistory(raw: AnovaRawState) {
        val temp = raw.currentTemp ?: return
        scope.launch {
            val sampleIntervalMs = settings.historySampleMs.first()
            val now = System.currentTimeMillis()
            if (now - lastHistorySampleMs < sampleIntervalMs) return@launch
            lastHistorySampleMs = now
            readingDao.insert(
                TemperatureReading(
                    timestamp    = now,
                    temperature  = temp,
                    unit         = raw.unit.name,
                    status       = raw.status.name.lowercase(),
                    timerMinutes = raw.timerMinutes
                )
            )
        }
    }

    private suspend fun purgeOldHistory() {
        try {
            val retentionDays = settings.historyRetentionDays.first()
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
            readingDao.deleteOlderThan(cutoff)
        } catch (e: Exception) {
            AppLogger.e(TAG, "History purge error: ${e.message}")
        }
    }

    // ── Foreground service ────────────────────────────────────────────────────

    private fun startService() {
        try {
            val intent = Intent(context, AnovaMonitorService::class.java)
            context.startForegroundService(intent)
        } catch (e: Exception) {
            // Expected on Android 12+ when app is in background; connection still works without it.
            AppLogger.d(TAG, "Could not start foreground service: ${e.message}")
        }
    }

    private fun stopService() {
        try {
            context.stopService(Intent(context, AnovaMonitorService::class.java))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not stop service: ${e.message}")
        }
    }
}
