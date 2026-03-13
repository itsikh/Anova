package com.template.app.ui.screens.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.template.app.anova.ActiveTransport
import com.template.app.anova.AnovaDeviceState
import com.template.app.anova.AnovaRepository
import com.template.app.anova.AnovaSettings
import com.template.app.anova.AnovaStatus
import com.template.app.anova.ConnectionMode
import com.template.app.anova.ThresholdSettings
import com.template.app.anova.cloud.AnovaFirebaseAuth
import com.template.app.notifications.AnovaAlertManager
import com.template.app.security.SecureKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import com.template.app.anova.TempUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class AlertType { MIN, MAX }
data class ActiveAlert(val message: String, val type: AlertType)

@HiltViewModel
class AnovaViewModel @Inject constructor(
    private val repository: AnovaRepository,
    private val settings: AnovaSettings,
    private val secureKeyManager: SecureKeyManager,
    private val alertManager: AnovaAlertManager,
    private val firebaseAuth: AnovaFirebaseAuth
) : ViewModel() {

    val deviceState: StateFlow<AnovaDeviceState> = repository.deviceState
    val activeTransport: StateFlow<ActiveTransport> = repository.activeTransport

    /** Device state with temperatures converted to the user's preferred unit. */
    val displayDeviceState: StateFlow<AnovaDeviceState> =
        combine(repository.deviceState, settings.tempUnitCelsius) { state, wantCelsius ->
            val preferredUnit = if (wantCelsius) TempUnit.CELSIUS else TempUnit.FAHRENHEIT
            if (state.unit == preferredUnit) return@combine state
            // Convert temperatures
            fun convert(t: Float?) = t?.let {
                if (preferredUnit == TempUnit.FAHRENHEIT) it * 9f / 5f + 32f
                else (it - 32f) * 5f / 9f
            }
            state.copy(
                currentTemp = convert(state.currentTemp),
                targetTemp  = convert(state.targetTemp),
                unit        = preferredUnit
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.deviceState.value)

    val connectionMode: StateFlow<ConnectionMode> = settings.connectionMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionMode.AUTO)
    val localWifiIp:   StateFlow<String>  = settings.localWifiIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val cloudEmail:    StateFlow<String>  = settings.cloudEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val localPollMs:   StateFlow<Long>    = settings.localPollMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnovaSettings.DEFAULT_LOCAL_POLL_MS)
    val remotePollMs:  StateFlow<Long>    = settings.remotePollMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnovaSettings.DEFAULT_REMOTE_POLL_MS)

    /** The user's explicit unit preference — used for display; independent of device unit. */
    val useCelsius: StateFlow<Boolean> = settings.tempUnitCelsius
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val thresholdAutoPct: StateFlow<Float> = settings.thresholdAutoPct
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnovaSettings.DEFAULT_THRESHOLD_AUTO_PCT)

    private val _thresholds = MutableStateFlow(ThresholdSettings())
    val thresholds: StateFlow<ThresholdSettings> = _thresholds.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scannedIp = MutableStateFlow<String?>(null)
    val scannedIp: StateFlow<String?> = _scannedIp.asStateFlow()

    private val _controlError = MutableStateFlow<String?>(null)
    val controlError: StateFlow<String?> = _controlError.asStateFlow()

    /** True while a start/stop command has been sent but device hasn't confirmed it yet. */
    private val _cookCommandPending = MutableStateFlow(false)
    val cookCommandPending: StateFlow<Boolean> = _cookCommandPending.asStateFlow()

    /** Expiry of the stored Anova JWT, or 0 if none. */
    val anovaJwtExpiryMs: Long get() = firebaseAuth.anovaJwtExpiryMs
    val storedEmail: String?   get() = firebaseAuth.storedEmail

    // 0 = alert not active. >0 = System.currentTimeMillis() of last notification fire.
    private var lastMinAlertMs = 0L
    private var lastMaxAlertMs = 0L
    // 0 = not snoozed. >0 = System.currentTimeMillis() at which snooze expires.
    private var snoozeMinUntilMs = 0L
    private var snoozeMaxUntilMs = 0L
    // True once the device has reached its target temp during the current cook.
    // Min alert only fires after this point — prevents false alerts while heating up.
    private var hasReachedTarget = false

    /** Alerts currently active (condition still met, not yet acknowledged by user). */
    private val _activeAlerts = MutableStateFlow<List<ActiveAlert>>(emptyList())
    val activeAlerts: StateFlow<List<ActiveAlert>> = _activeAlerts.asStateFlow()

    init {
        // Load persisted thresholds from DataStore
        viewModelScope.launch {
            val minEnabled = settings.thresholdMinEnabled.first()
            val minTemp    = settings.thresholdMinTemp.first()
            val isAutoMin  = settings.thresholdMinAuto.first()
            val maxEnabled = settings.thresholdMaxEnabled.first()
            val maxTemp    = settings.thresholdMaxTemp.first()
            _thresholds.value = ThresholdSettings(
                minTempEnabled = minEnabled,
                minTemp        = minTemp,
                isAutoMin      = isAutoMin,
                maxTempEnabled = maxEnabled,
                maxTemp        = maxTemp
            )
        }

        viewModelScope.launch {
            var lastTarget: Float? = null
            var lastStatus: AnovaStatus? = null
            displayDeviceState.collect { state ->
                // Device confirmed a status change → command was delivered, clear pending flag
                if (state.status != lastStatus && lastStatus != null) {
                    _cookCommandPending.value = false
                }
                lastStatus = state.status

                val target = state.targetTemp
                // When target temp changes, recalculate auto min (and reset reached flag)
                if (target != null && target != lastTarget) {
                    lastTarget = target
                    if (_thresholds.value.isAutoMin) {
                        val pct = thresholdAutoPct.value
                        _thresholds.value = _thresholds.value.copy(
                            minTempEnabled = true,
                            minTemp = target * (1f - pct)
                        )
                    }
                    hasReachedTarget = false
                    lastMinAlertMs = 0L
                }
                checkThresholds(state)
            }
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect()    = repository.connect()
    fun disconnect() = repository.disconnect()

    fun setGoogleToken(token: String) = repository.setGoogleToken(token)
    fun createGoogleAuthSession(): Pair<String, String> = firebaseAuth.createGoogleAuthUri()

    suspend fun signInWithGoogleRedirect(redirectUrl: String, sessionId: String): String? =
        withContext(Dispatchers.IO) {
            val token = firebaseAuth.signInWithGoogleRedirectUrl(redirectUrl, sessionId)
                ?: return@withContext null
            repository.useGoogleSsoSession()
            token
        }

    /** Seed auth from a pasted Firebase refresh token (from the Mac HTML page). */
    suspend fun seedRefreshToken(refreshToken: String, email: String?): Boolean =
        withContext(Dispatchers.IO) {
            firebaseAuth.seedRefreshToken(refreshToken, email)
        }

    fun clearAuth() {
        firebaseAuth.clearAll()
    }

    // ── Cook control ──────────────────────────────────────────────────────────

    fun startCook() {
        viewModelScope.launch {
            _cookCommandPending.value = true
            val ok = repository.startCook()
            if (!ok) { _cookCommandPending.value = false; _controlError.value = "Failed to start cook — check connection." }
        }
    }

    fun stopCook() {
        viewModelScope.launch {
            _cookCommandPending.value = true
            val ok = repository.stopCook()
            if (!ok) { _cookCommandPending.value = false; _controlError.value = "Failed to stop cook — check connection." }
        }
    }

    fun updateTemp(targetTemp: Float) {
        viewModelScope.launch {
            val ok = repository.updateCook(targetTemp = targetTemp)
            if (!ok) _controlError.value = "Failed to update temperature."
        }
    }

    fun updateTimer(hours: Int, minutes: Int) {
        val totalSeconds = (hours * 3600) + (minutes * 60)
        viewModelScope.launch {
            val ok = repository.updateCook(timerSeconds = totalSeconds)
            if (!ok) _controlError.value = "Failed to update timer."
        }
    }

    fun dismissControlError() { _controlError.value = null }

    // ── Device scan ───────────────────────────────────────────────────────────

    fun scanForDevice() {
        viewModelScope.launch {
            _isScanning.value = true
            _scannedIp.value = null
            val ip = repository.discoverDevice()
            _isScanning.value = false
            if (ip != null) { settings.setLocalWifiIp(ip); _scannedIp.value = ip }
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        viewModelScope.launch { settings.setConnectionMode(mode) }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun saveLocalSettings(ip: String, pollMs: Long) {
        viewModelScope.launch {
            settings.setLocalWifiIp(ip.trim())
            settings.setLocalPollMs(pollMs.coerceAtLeast(1_000L))
        }
    }

    fun saveCloudSettings(email: String, password: String, pollMs: Long) {
        viewModelScope.launch {
            settings.setCloudEmail(email.trim())
            if (password.isNotBlank()) secureKeyManager.saveKey(AnovaRepository.KEY_CLOUD_PASSWORD, password)
            settings.setRemotePollMs(pollMs.coerceAtLeast(10_000L))
        }
    }

    // ── Thresholds ────────────────────────────────────────────────────────────

    fun acknowledgeAlerts() {
        _activeAlerts.value = emptyList()
        lastMinAlertMs = 0L
        lastMaxAlertMs = 0L
        snoozeMinUntilMs = 0L
        snoozeMaxUntilMs = 0L
        alertManager.cancelTempAlerts()
    }

    fun snoozeAlerts() {
        val until = System.currentTimeMillis() + SNOOZE_MS
        if (_activeAlerts.value.any { it.type == AlertType.MIN }) snoozeMinUntilMs = until
        if (_activeAlerts.value.any { it.type == AlertType.MAX }) snoozeMaxUntilMs = until
        _activeAlerts.value = emptyList()
        lastMinAlertMs = 0L
        lastMaxAlertMs = 0L
        alertManager.cancelTempAlerts()
    }

    fun updateThresholds(t: ThresholdSettings) {
        _thresholds.value = t
        lastMinAlertMs = 0L
        lastMaxAlertMs = 0L
        _activeAlerts.value = emptyList()
        viewModelScope.launch {
            settings.saveThresholds(
                minEnabled = t.minTempEnabled,
                minTemp    = t.minTemp,
                isAutoMin  = t.isAutoMin,
                maxEnabled = t.maxTempEnabled,
                maxTemp    = t.maxTemp
            )
        }
    }

    private fun checkThresholds(state: AnovaDeviceState) {
        val temp = state.currentTemp ?: return
        val target = state.targetTemp
        val t = _thresholds.value
        val now = System.currentTimeMillis()

        // Track when device reaches target temp during a cook.
        // Only reset when target temp changes (handled in init), NOT on status change —
        // otherwise min alerts are silently blocked after the cook finishes and temp drops.
        if (state.status == AnovaStatus.RUNNING && target != null && temp >= target - 0.5f) {
            hasReachedTarget = true
        }

        // Min alert: only fires after device has reached target temp (not during heat-up).
        // Re-fires every ALERT_REPEAT_MS while condition remains active (unless snoozed).
        val minActive = t.minTempEnabled && hasReachedTarget && temp <= t.minTemp
        if (minActive) {
            val snoozed = now < snoozeMinUntilMs
            if (!snoozed && (lastMinAlertMs == 0L || (now - lastMinAlertMs) >= ALERT_REPEAT_MS)) {
                lastMinAlertMs = now
                val msg = "Temp ${fmt(temp)}${state.unit.symbol} — below min ${fmt(t.minTemp)}${state.unit.symbol}"
                alertManager.postTempAlert(msg, AnovaAlertManager.NOTIFICATION_ID_TEMP_MIN)
                val others = _activeAlerts.value.filter { it.type != AlertType.MIN }
                _activeAlerts.value = others + ActiveAlert(msg, AlertType.MIN)
            }
        } else {
            snoozeMinUntilMs = 0L
            lastMinAlertMs = 0L
            if (_activeAlerts.value.any { it.type == AlertType.MIN }) {
                _activeAlerts.value = _activeAlerts.value.filter { it.type != AlertType.MIN }
            }
        }

        val maxActive = t.maxTempEnabled && temp >= t.maxTemp
        if (maxActive) {
            val snoozed = now < snoozeMaxUntilMs
            if (!snoozed && (lastMaxAlertMs == 0L || (now - lastMaxAlertMs) >= ALERT_REPEAT_MS)) {
                lastMaxAlertMs = now
                val msg = "Temp ${fmt(temp)}${state.unit.symbol} — above max ${fmt(t.maxTemp)}${state.unit.symbol}"
                alertManager.postTempAlert(msg, AnovaAlertManager.NOTIFICATION_ID_TEMP_MAX)
                val others = _activeAlerts.value.filter { it.type != AlertType.MAX }
                _activeAlerts.value = others + ActiveAlert(msg, AlertType.MAX)
            }
        } else {
            snoozeMaxUntilMs = 0L
            lastMaxAlertMs = 0L
            if (_activeAlerts.value.any { it.type == AlertType.MAX }) {
                _activeAlerts.value = _activeAlerts.value.filter { it.type != AlertType.MAX }
            }
        }
    }

    companion object {
        private const val ALERT_REPEAT_MS = 2 * 60 * 1_000L
        private const val SNOOZE_MS       = 5 * 60 * 1_000L
    }

    private fun fmt(t: Float) = "%.1f".format(t)
}
