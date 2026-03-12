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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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

    private val _thresholds = MutableStateFlow(ThresholdSettings())
    val thresholds: StateFlow<ThresholdSettings> = _thresholds.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scannedIp = MutableStateFlow<String?>(null)
    val scannedIp: StateFlow<String?> = _scannedIp.asStateFlow()

    private val _controlError = MutableStateFlow<String?>(null)
    val controlError: StateFlow<String?> = _controlError.asStateFlow()

    /** Expiry of the stored Anova JWT, or 0 if none. */
    val anovaJwtExpiryMs: Long get() = firebaseAuth.anovaJwtExpiryMs
    val storedEmail: String?   get() = firebaseAuth.storedEmail

    private var minAlertFired = false
    private var maxAlertFired = false
    // True once the device has reached its target temp during the current cook.
    // Min alert only fires after this point — prevents false alerts while heating up.
    private var hasReachedTarget = false

    init {
        viewModelScope.launch {
            var lastTarget: Float? = null
            displayDeviceState.collect { state ->
                val target = state.targetTemp
                // When target temp changes, recalculate auto min (and reset reached flag)
                if (target != null && target != lastTarget) {
                    lastTarget = target
                    if (_thresholds.value.isAutoMin) {
                        _thresholds.value = _thresholds.value.copy(
                            minTempEnabled = true,
                            minTemp = target * 0.9f
                        )
                    }
                    hasReachedTarget = false
                    minAlertFired = false
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
            val ok = repository.startCook()
            if (!ok) _controlError.value = "Failed to start cook — check connection."
        }
    }

    fun stopCook() {
        viewModelScope.launch {
            val ok = repository.stopCook()
            if (!ok) _controlError.value = "Failed to stop cook — check connection."
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

    fun updateThresholds(t: ThresholdSettings) {
        _thresholds.value = t
        minAlertFired = false
        maxAlertFired = false
        // If the user manually overrides auto min, keep isAutoMin = false going forward
        // (already stored in t.isAutoMin from the dialog)
    }

    private fun checkThresholds(state: AnovaDeviceState) {
        val temp = state.currentTemp ?: return
        val target = state.targetTemp
        val t = _thresholds.value

        // Track when device reaches target temp during a cook.
        // Only reset when target temp changes (handled in init), NOT on status change —
        // otherwise min alerts are silently blocked after the cook finishes and temp drops.
        if (state.status == AnovaStatus.RUNNING && target != null && temp >= target - 0.5f) {
            hasReachedTarget = true
        }

        // Min alert: only fires after device has reached target temp (not during heat-up)
        if (t.minTempEnabled && hasReachedTarget && temp <= t.minTemp) {
            if (!minAlertFired) {
                minAlertFired = true
                alertManager.postTempAlert(
                    "Temp ${fmt(temp)}${state.unit.symbol} — below min ${fmt(t.minTemp)}${state.unit.symbol}",
                    AnovaAlertManager.NOTIFICATION_ID_TEMP_MIN
                )
            }
        } else minAlertFired = false

        if (t.maxTempEnabled && temp >= t.maxTemp) {
            if (!maxAlertFired) {
                maxAlertFired = true
                alertManager.postTempAlert(
                    "Temp ${fmt(temp)}${state.unit.symbol} — above max ${fmt(t.maxTemp)}${state.unit.symbol}",
                    AnovaAlertManager.NOTIFICATION_ID_TEMP_MAX
                )
            }
        } else maxAlertFired = false
    }

    private fun fmt(t: Float) = "%.1f".format(t)
}
