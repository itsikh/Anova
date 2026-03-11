package com.template.app.anova.cloud

import com.google.gson.Gson
import com.template.app.anova.AnovaRawState
import com.template.app.anova.AnovaStatus
import com.template.app.anova.AnovaTransport
import com.template.app.anova.ConnectionState
import com.template.app.anova.TempUnit
import com.template.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaCloud"

/**
 * Cloud transport that talks to the Anova device via Anova's unofficial REST API.
 *
 * ⚠️ This API is reverse-engineered from the official app's network traffic and is
 * NOT officially supported by Anova. It may change or stop working at any time.
 *
 * Flow:
 * 1. [setCredentials] — store Anova account email + password
 * 2. [connect] — authenticate with Firebase → fetch cooker ID → mark CONNECTED
 * 3. [poll] — called by [AnovaRepository] on a schedule; fetches device state via REST
 *
 * Token refresh is handled transparently by [AnovaFirebaseAuth].
 */
@Singleton
class AnovaCloudTransport @Inject constructor(
    private val auth: AnovaFirebaseAuth
) : AnovaTransport {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var email: String? = null
    private var password: String? = null
    private var cookerId: String? = null
    @Volatile private var pendingGoogleAccessToken: String? = null

    // -----------------------------------------------------------------------------------------
    // Credentials
    // -----------------------------------------------------------------------------------------

    /** Must be called before [connect]. Credentials are held in memory only. */
    fun setCredentials(email: String, password: String) {
        this.email = email
        this.password = password
        pendingGoogleAccessToken = null
        auth.clearToken()
    }

    /**
     * Use Google SSO instead of email/password. Call before [connect].
     * The Google ID token (from CredentialManager) is exchanged for a Firebase token on first connect;
     * subsequent reconnects use the cached Firebase refresh token automatically.
     */
    fun setGoogleIdToken(googleIdToken: String) {
        pendingGoogleAccessToken = googleIdToken
        auth.clearToken()
        AppLogger.i(TAG, "Google ID token set — will use SSO on next connect")
    }

    // -----------------------------------------------------------------------------------------
    // AnovaTransport
    // -----------------------------------------------------------------------------------------

    /** [address] is ignored — cloud transport uses stored credentials or Google SSO token. */
    override fun connect(address: String?) {
        val googleToken = pendingGoogleAccessToken
        // Require either Google SSO token or email+password
        if (googleToken == null) {
            val e = email?.takeIf { it.isNotBlank() } ?: run {
                _lastError.value = "No email configured. Enter your Anova account email in Connection Settings."
                AppLogger.e(TAG, "No email configured"); return
            }
            val p = password?.takeIf { it.isNotBlank() } ?: run {
                _lastError.value = "No password configured. Enter your Anova account password in Connection Settings."
                AppLogger.e(TAG, "No password configured"); return
            }
            if (_connectionState.value == ConnectionState.CONNECTING) return
            _lastError.value = null
            _connectionState.value = ConnectionState.CONNECTING
            scope.launch {
                try {
                    AppLogger.i(TAG, "Authenticating with Firebase (email/password)…")
                    val token = auth.getValidToken(e, p)
                    if (token == null) {
                        AppLogger.e(TAG, "Authentication failed — check credentials")
                        _lastError.value = auth.lastSignInError ?: "Authentication failed. Check your credentials."
                        _connectionState.value = ConnectionState.DISCONNECTED
                        return@launch
                    }
                    finishConnect(token)
                } catch (ex: Exception) {
                    AppLogger.e(TAG, "connect() threw unexpectedly: ${ex.message}")
                    _lastError.value = "Unexpected error: ${ex.message}"
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        } else {
            if (_connectionState.value == ConnectionState.CONNECTING) return
            _lastError.value = null
            _connectionState.value = ConnectionState.CONNECTING
            scope.launch {
                try {
                    AppLogger.i(TAG, "Authenticating with Firebase (Google ID token)…")
                    val token = auth.signInWithGoogleIdToken(googleToken)
                    if (token == null) {
                        _lastError.value = auth.lastSignInError ?: "Google sign-in failed."
                        _connectionState.value = ConnectionState.DISCONNECTED
                        return@launch
                    }
                    pendingGoogleAccessToken = null // token is now cached in auth, no longer needed
                    finishConnect(token)
                } catch (ex: Exception) {
                    AppLogger.e(TAG, "Google connect() threw: ${ex.message}")
                    _lastError.value = "Unexpected error: ${ex.message}"
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    private suspend fun finishConnect(token: String) {
        AppLogger.i(TAG, "Authenticated, fetching cooker ID…")
        val id = fetchCookerId(token)
        if (id == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        cookerId = id
        _deviceName.value = "Anova Cloud"
        _connectionState.value = ConnectionState.CONNECTED
        AppLogger.i(TAG, "Cloud connected — device: $id")
    }

    override fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = null
        cookerId = null
    }

    override suspend fun poll(): AnovaRawState? = withContext(Dispatchers.IO) {
        val id = cookerId ?: return@withContext null

        val token = if (email != null && password != null) {
            auth.getValidToken(email!!, password!!)
        } else {
            auth.getValidTokenOrRefresh()
        } ?: run {
            AppLogger.e(TAG, "Token unavailable — marking disconnected")
            _connectionState.value = ConnectionState.DISCONNECTED
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url("${AnovaCloudConfig.ANOVA_BASE_URL}/cooker/$id/state")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                AppLogger.w(TAG, "Poll HTTP ${response.code}")
                if (response.code == 401) {
                    auth.clearToken()
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                return@withContext null
            }

            val json = response.body?.string() ?: return@withContext null
            AppLogger.d(TAG, "Cloud state: $json")
            val state = gson.fromJson(json, AnovaStateResponse::class.java)

            val unit = if (state.resolvedUnit?.trim().equals("f", ignoreCase = true))
                TempUnit.FAHRENHEIT else TempUnit.CELSIUS
            val status = when {
                state.status?.contains("running", ignoreCase = true) == true -> AnovaStatus.RUNNING
                state.status?.contains("stopped", ignoreCase = true) == true -> AnovaStatus.STOPPED
                else -> AnovaStatus.UNKNOWN
            }

            AnovaRawState(
                currentTemp = state.resolvedCurrentTemp,
                unit = unit,
                timerMinutes = state.timer?.current,
                status = status
            )
        } catch (ex: Exception) {
            AppLogger.e(TAG, "Poll error: ${ex.message}")
            null
        }
    }

    // -----------------------------------------------------------------------------------------

    private suspend fun fetchCookerId(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${AnovaCloudConfig.ANOVA_BASE_URL}/devices")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "Device list fetch failed: ${response.code}")
                _lastError.value = "Cannot reach Anova servers (HTTP ${response.code})."
                return@withContext null
            }
            val json = response.body?.string() ?: run {
                _lastError.value = "Empty response from Anova servers."
                return@withContext null
            }
            AppLogger.d(TAG, "Devices: $json")
            val cookerId = gson.fromJson(json, AnovaDevicesResponse::class.java)
                ?.devices?.firstOrNull()?.cookerId
            if (cookerId == null) {
                AppLogger.e(TAG, "No Anova device found on this account")
                _lastError.value = "No Anova device found on this account."
            }
            cookerId
        } catch (e: java.io.IOException) {
            AppLogger.e(TAG, "Device list error: ${e.message}")
            _lastError.value = "Cannot reach Anova servers. Check your internet connection."
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Device list error: ${e.message}")
            _lastError.value = "Unexpected error fetching device list."
            null
        }
    }
}
