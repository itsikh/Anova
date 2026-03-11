package com.template.app.anova

import com.template.app.anova.cloud.AnovaCloudTransport
import com.template.app.history.TemperatureReading
import com.template.app.history.TemperatureReadingDao
import com.template.app.logging.AppLogger
import com.template.app.security.SecureKeyManager
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaRepo"
private const val AUTO_WIFI_TIMEOUT_MS = 6_000L
private val HISTORY_MAX_AGE_MS = TimeUnit.DAYS.toMillis(30)

/** Which physical transport is currently driving the connection. */
enum class ActiveTransport { BLUETOOTH, LOCAL_WIFI, CLOUD, NONE }

@Singleton
class AnovaRepository @Inject constructor(
    val bleTransport: AnovaBluetoothManager,
    val wifiTransport: AnovaWifiTransport,
    val cloudTransport: AnovaCloudTransport,
    private val settings: AnovaSettings,
    private val readingDao: TemperatureReadingDao,
    private val secureKeyManager: SecureKeyManager
) {
    companion object {
        const val KEY_CLOUD_PASSWORD = "anova_cloud_password"
    }

    private val _deviceState = MutableStateFlow(AnovaDeviceState())
    val deviceState: StateFlow<AnovaDeviceState> = _deviceState.asStateFlow()

    private val _activeTransport = MutableStateFlow(ActiveTransport.NONE)
    val activeTransport: StateFlow<ActiveTransport> = _activeTransport.asStateFlow()

    private var pollingJob: Job? = null
    private var currentPollIntervalMs = AnovaSettings.DEFAULT_LOCAL_POLL_MS
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        observeTransport(bleTransport, ActiveTransport.BLUETOOTH)
        observeTransport(wifiTransport, ActiveTransport.LOCAL_WIFI)
        observeTransport(cloudTransport, ActiveTransport.CLOUD)
        observeCloudErrors()
    }

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    fun connect() {
        scope.launch {
            _deviceState.update { it.copy(connectionError = null) }
            val mode = settings.connectionMode.first()
            val ip = settings.localWifiIp.first()
            val email = settings.cloudEmail.first()
            val password = secureKeyManager.getKey(KEY_CLOUD_PASSWORD) ?: ""
            val localMs = settings.localPollMs.first()
            val remoteMs = settings.remotePollMs.first()

            when (mode) {
                ConnectionMode.BLUETOOTH -> {
                    currentPollIntervalMs = localMs
                    _activeTransport.value = ActiveTransport.BLUETOOTH
                    bleTransport.connect()
                }
                ConnectionMode.LOCAL_WIFI -> {
                    currentPollIntervalMs = localMs
                    val resolvedIp = if (ip.isBlank()) {
                        _deviceState.update { it.copy(connectionState = ConnectionState.SCANNING, connectionError = null) }
                        AppLogger.i(TAG, "LOCAL_WIFI: no IP configured, scanning local network…")
                        val found = wifiTransport.discoverDevice()
                        if (found == null) {
                            _deviceState.update {
                                it.copy(
                                    connectionState = ConnectionState.DISCONNECTED,
                                    connectionError = "No Anova device found on this network. Make sure the cooker is powered on and connected to the same Wi-Fi network."
                                )
                            }
                            return@launch
                        }
                        settings.setLocalWifiIp(found)
                        found
                    } else ip
                    _activeTransport.value = ActiveTransport.LOCAL_WIFI
                    wifiTransport.connect(resolvedIp)
                }
                ConnectionMode.CLOUD -> {
                    currentPollIntervalMs = remoteMs
                    _activeTransport.value = ActiveTransport.CLOUD
                    cloudTransport.setCredentials(email, password)
                    cloudTransport.connect()
                }
                ConnectionMode.AUTO -> connectAuto(ip, email, password, localMs, remoteMs)
            }
        }
    }

    fun disconnect() {
        stopPolling()
        bleTransport.disconnect()
        wifiTransport.disconnect()
        cloudTransport.disconnect()
        _activeTransport.value = ActiveTransport.NONE
        _deviceState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
    }

    // -----------------------------------------------------------------------------------------
    // Auto-fallback logic
    // -----------------------------------------------------------------------------------------

    /** Scans the local network for an Anova device and returns its IP, or null if not found. */
    suspend fun discoverDevice(): String? = wifiTransport.discoverDevice()

    /** Use a Google ID token (from CredentialManager) for cloud auth instead of email/password. */
    fun setGoogleToken(googleIdToken: String) {
        cloudTransport.setGoogleIdToken(googleIdToken)
    }

    /**
     * Call after a browser-based Google sign-in has cached the Firebase token.
     * Switches the cloud transport to use the cached token (no re-sign-in needed).
     */
    fun useGoogleSsoSession() {
        cloudTransport.useGoogleSsoSession()
    }

    private suspend fun connectAuto(
        ip: String,
        email: String,
        password: String,
        localMs: Long,
        remoteMs: Long
    ) {
        val resolvedIp = if (ip.isBlank()) {
            AppLogger.i(TAG, "AUTO: no local IP configured, scanning…")
            _deviceState.update { it.copy(connectionState = ConnectionState.SCANNING, connectionError = null) }
            val found = wifiTransport.discoverDevice()
            if (found != null) {
                AppLogger.i(TAG, "AUTO: found Anova at $found")
                settings.setLocalWifiIp(found)
            }
            found
        } else ip

        if (resolvedIp.isNullOrBlank()) {
            AppLogger.i(TAG, "AUTO: no Anova on local network, falling back to cloud")
            switchToCloud(email, password, remoteMs)
            return
        }

        AppLogger.i(TAG, "AUTO: trying local WiFi ($resolvedIp) with ${AUTO_WIFI_TIMEOUT_MS}ms timeout…")
        currentPollIntervalMs = localMs
        _activeTransport.value = ActiveTransport.LOCAL_WIFI
        wifiTransport.connect(resolvedIp)

        val connected = withTimeoutOrNull(AUTO_WIFI_TIMEOUT_MS) {
            wifiTransport.connectionState.first { it == ConnectionState.CONNECTED }
        }

        if (connected == null) {
            AppLogger.i(TAG, "AUTO: local WiFi timed out → falling back to cloud")
            wifiTransport.disconnect()
            _activeTransport.value = ActiveTransport.NONE
            switchToCloud(email, password, remoteMs)
        }
        // If local connected: the observeTransport collector handles the rest
    }

    private fun switchToCloud(email: String, password: String, remoteMs: Long) {
        if (email.isBlank() || password.isBlank()) {
            AppLogger.e(TAG, "Cloud fallback failed: no cloud credentials configured")
            _deviceState.update {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    connectionError = "No cloud credentials configured. Tap 'Configure connection' to enter your Anova account details."
                )
            }
            return
        }
        currentPollIntervalMs = remoteMs
        _activeTransport.value = ActiveTransport.CLOUD
        cloudTransport.setCredentials(email, password)
        cloudTransport.connect()
    }

    // -----------------------------------------------------------------------------------------
    // Transport observation
    // -----------------------------------------------------------------------------------------

    private fun observeTransport(transport: AnovaTransport, type: ActiveTransport) {
        scope.launch {
            try {
                transport.connectionState.collect { state ->
                    if (_activeTransport.value != type) return@collect
                    AppLogger.d(TAG, "$type state → $state")
                    _deviceState.update { it.copy(connectionState = state) }
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            _deviceState.update { it.copy(connectionError = null) }
                            purgeOldHistory()
                            startPolling()
                        }
                        ConnectionState.DISCONNECTED -> {
                            stopPolling()
                            _deviceState.update {
                                it.copy(currentTemp = null, timerMinutes = null, status = AnovaStatus.UNKNOWN)
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "observeTransport($type) collector crashed: ${e.message}")
            }
        }
        scope.launch {
            try {
                transport.deviceName.collect { name ->
                    if (_activeTransport.value == type) _deviceState.update { it.copy(deviceName = name) }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "observeTransport($type) deviceName collector crashed: ${e.message}")
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

    // -----------------------------------------------------------------------------------------
    // Polling
    // -----------------------------------------------------------------------------------------

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
            ActiveTransport.BLUETOOTH -> bleTransport
            ActiveTransport.LOCAL_WIFI -> wifiTransport
            ActiveTransport.CLOUD -> cloudTransport
            ActiveTransport.NONE -> return
        }
        try {
            val raw = transport.poll() ?: return
            _deviceState.update {
                it.copy(
                    currentTemp = raw.currentTemp,
                    unit = raw.unit,
                    timerMinutes = raw.timerMinutes,
                    status = raw.status,
                    lastUpdated = System.currentTimeMillis()
                )
            }
            if (raw.currentTemp != null) {
                readingDao.insert(
                    TemperatureReading(
                        timestamp = System.currentTimeMillis(),
                        temperature = raw.currentTemp,
                        unit = raw.unit.name,
                        status = raw.status.name.lowercase(),
                        timerMinutes = raw.timerMinutes
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Poll error: ${e.message}")
        }
    }

    private suspend fun purgeOldHistory() {
        try {
            readingDao.deleteOlderThan(System.currentTimeMillis() - HISTORY_MAX_AGE_MS)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to purge old history: ${e.message}")
        }
    }
}
