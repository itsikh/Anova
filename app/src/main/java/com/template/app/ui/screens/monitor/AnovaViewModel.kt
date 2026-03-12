package com.template.app.ui.screens.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.template.app.anova.ActiveTransport
import com.template.app.anova.AnovaDeviceState
import com.template.app.anova.AnovaRepository
import com.template.app.anova.AnovaSettings
import com.template.app.anova.ConnectionMode
import com.template.app.anova.ThresholdSettings
import com.template.app.anova.cloud.AnovaFirebaseAuth
import com.template.app.notifications.AnovaAlertManager
import com.template.app.security.SecureKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

    val connectionMode: StateFlow<ConnectionMode> = settings.connectionMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionMode.AUTO)
    val localWifiIp: StateFlow<String> = settings.localWifiIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val cloudEmail: StateFlow<String> = settings.cloudEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val localPollMs: StateFlow<Long> = settings.localPollMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnovaSettings.DEFAULT_LOCAL_POLL_MS)
    val remotePollMs: StateFlow<Long> = settings.remotePollMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnovaSettings.DEFAULT_REMOTE_POLL_MS)

    private val _thresholds = MutableStateFlow(ThresholdSettings())
    val thresholds: StateFlow<ThresholdSettings> = _thresholds.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scannedIp = MutableStateFlow<String?>(null)
    val scannedIp: StateFlow<String?> = _scannedIp.asStateFlow()

    private var minAlertFired = false
    private var maxAlertFired = false

    init {
        viewModelScope.launch { deviceState.collect { checkThresholds(it) } }
    }

    // -----------------------------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------------------------

    fun connect() = repository.connect()
    fun disconnect() = repository.disconnect()

    /** Store a Google OAuth access token so the next [connect] uses Google SSO. */
    fun setGoogleToken(googleAccessToken: String) {
        repository.setGoogleToken(googleAccessToken)
    }

    /**
     * Builds the Google OAuth URL for a browser-based sign-in flow.
     * Returns (authUri, sessionId) to open in a WebView — never fails.
     */
    fun createGoogleAuthSession(): Pair<String, String> = firebaseAuth.createGoogleAuthUri()

    /**
     * Exchanges the intercepted Google OAuth redirect URL + sessionId for a Firebase token.
     * On success, tells the cloud transport to use the cached token and returns a non-null marker.
     */
    suspend fun signInWithGoogleRedirect(redirectUrl: String, sessionId: String): String? =
        withContext(Dispatchers.IO) {
            val token = firebaseAuth.signInWithGoogleRedirectUrl(redirectUrl, sessionId)
                ?: return@withContext null
            repository.useGoogleSsoSession()
            token // non-null means success
        }

    fun scanForDevice() {
        viewModelScope.launch {
            _isScanning.value = true
            _scannedIp.value = null
            val ip = repository.discoverDevice()
            _isScanning.value = false
            if (ip != null) {
                settings.setLocalWifiIp(ip)
                _scannedIp.value = ip
            }
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        viewModelScope.launch { settings.setConnectionMode(mode) }
    }

    // -----------------------------------------------------------------------------------------
    // Settings persistence
    // -----------------------------------------------------------------------------------------

    fun saveLocalSettings(ip: String, pollMs: Long) {
        viewModelScope.launch {
            settings.setLocalWifiIp(ip.trim())
            settings.setLocalPollMs(pollMs.coerceAtLeast(1_000L))
        }
    }

    fun saveCloudSettings(email: String, password: String, pollMs: Long) {
        viewModelScope.launch {
            settings.setCloudEmail(email.trim())
            if (password.isNotBlank()) {
                secureKeyManager.saveKey(AnovaRepository.KEY_CLOUD_PASSWORD, password)
            }
            settings.setRemotePollMs(pollMs.coerceAtLeast(10_000L))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Thresholds
    // -----------------------------------------------------------------------------------------

    fun updateThresholds(t: ThresholdSettings) {
        _thresholds.value = t
        minAlertFired = false
        maxAlertFired = false
    }

    // -----------------------------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------------------------

    private fun checkThresholds(state: AnovaDeviceState) {
        val temp = state.currentTemp ?: return
        val t = _thresholds.value

        if (t.minTempEnabled && temp <= t.minTemp) {
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
