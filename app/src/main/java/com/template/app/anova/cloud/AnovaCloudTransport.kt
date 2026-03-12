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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaCloud"

@Singleton
class AnovaCloudTransport @Inject constructor(
    private val auth: AnovaFirebaseAuth
) : AnovaTransport {

    // No pingInterval — the Anova server does not respond to WebSocket pings,
    // causing OkHttp to close the connection after every ping timeout.
    // Application-level keepalive is handled by CMD_APC_REQUEST_DEVICE_STATUS polls.
    private val client = OkHttpClient.Builder()
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _rawStateFlow = MutableSharedFlow<AnovaRawState>(replay = 1)
    override val rawStateFlow: Flow<AnovaRawState> = _rawStateFlow.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var webSocket: WebSocket? = null
    private var cookerId: String? = null
    private var cachedRawState: AnovaRawState? = null
    private var connectionTimeoutJob: Job? = null

    // Credentials (email/password path)
    private var email: String? = null
    private var password: String? = null
    @Volatile private var pendingGoogleIdToken: String? = null

    // ── Credentials ───────────────────────────────────────────────────────────

    fun setCredentials(email: String, password: String) {
        this.email = email
        this.password = password
        pendingGoogleIdToken = null
        auth.clearToken()
    }

    fun setGoogleIdToken(token: String) {
        pendingGoogleIdToken = token
        auth.clearToken()
    }

    fun useGoogleSsoSession() {
        email = null
        password = null
        pendingGoogleIdToken = null
    }

    // ── AnovaTransport ────────────────────────────────────────────────────────

    override fun connect(address: String?) {
        if (_connectionState.value == ConnectionState.CONNECTING) return
        _lastError.value = null
        _connectionState.value = ConnectionState.CONNECTING

        scope.launch {
            try {
                // Authenticate if needed
                val googleToken = pendingGoogleIdToken
                val e = email?.takeIf { it.isNotBlank() }
                val p = password?.takeIf { it.isNotBlank() }

                when {
                    googleToken != null -> {
                        auth.signInWithGoogleIdToken(googleToken)
                        pendingGoogleIdToken = null
                    }
                    e != null && p != null -> auth.getValidToken(e, p)
                    else -> { /* use stored session */ }
                }

                val jwt = auth.getAnovaJwt()
                if (jwt == null) {
                    val reason = auth.lastSignInError ?: "Not signed in. Configure your Anova account."
                    AppLogger.e(TAG, "No Anova JWT — $reason")
                    _lastError.value = reason
                    _connectionState.value = ConnectionState.DISCONNECTED
                    return@launch
                }

                openWebSocket(jwt)
            } catch (ex: Exception) {
                AppLogger.e(TAG, "connect() error: ${ex.message}")
                _lastError.value = "Connection error: ${ex.message}"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    override fun disconnect() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        cookerId = null
        cachedRawState = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = null
    }

    override suspend fun poll(): AnovaRawState? {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            sendStatusRequest()
        }
        return cachedRawState
    }

    override suspend fun startCook(): Boolean {
        val id = cookerId ?: return false
        return sendCommand("CMD_APC_START", mapOf("cookerId" to id))
    }

    override suspend fun stopCook(): Boolean {
        val id = cookerId ?: return false
        return sendCommand("CMD_APC_STOP", mapOf("cookerId" to id))
    }

    override suspend fun updateCook(targetTemp: Float?, timerSeconds: Int?): Boolean {
        val id = cookerId ?: return false
        val payload = mutableMapOf<String, Any?>("cookerId" to id)
        if (targetTemp != null) payload["targetTemp"] = targetTemp
        if (timerSeconds != null) payload["timerSeconds"] = timerSeconds
        return sendCommand("CMD_APC_UPDATE_COOK", payload)
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun openWebSocket(jwt: String) {
        // Cancel any timeout launched by a previous (failed) connection attempt.
        // Without this, stale timeout coroutines accumulate during DNS-failure retries
        // and fire after a later attempt succeeds, killing a good connection.
        connectionTimeoutJob?.cancel()

        val url = "${AnovaCloudConfig.ANOVA_WS_BASE}?token=$jwt&supportedAccessories=APC,APO&platform=android"
        val request = Request.Builder().url(url).build()
        AppLogger.i(TAG, "Opening WebSocket…")
        webSocket = client.newWebSocket(request, listener)
        // If EVENT_APC_WIFI_LIST never arrives, stop waiting after 30s so the
        // UI can show an error instead of being permanently stuck at CONNECTING.
        connectionTimeoutJob = scope.launch {
            delay(30_000)
            if (_connectionState.value == ConnectionState.CONNECTING) {
                AppLogger.e(TAG, "Timeout waiting for device list — no device found on account?")
                _lastError.value = "No Anova device found on this account (timed out)."
                webSocket?.close(1000, "Timeout")
                webSocket = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            AppLogger.i(TAG, "WebSocket opened — waiting for device list…")
            // Wait for EVENT_APC_WIFI_LIST before marking connected
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            AppLogger.i(TAG, "WebSocket closed: $code $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            AppLogger.e(TAG, "WebSocket failure: ${t.message}")
            _lastError.value = "Connection lost: ${t.message}"
            _connectionState.value = ConnectionState.DISCONNECTED
            // Schedule reconnect
            scope.launch {
                delay(5_000)
                if (_connectionState.value == ConnectionState.DISCONNECTED) {
                    AppLogger.i(TAG, "Auto-reconnecting…")
                    connect()
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val typeOnly = gson.fromJson(text, WsTypeOnly::class.java)
            AppLogger.d(TAG, "WS msg [${typeOnly.type}]: ${text.take(200)}")
            when (typeOnly.type) {
                "EVENT_APC_WIFI_LIST"  -> handleWifiList(text)
                "EVENT_APC_STATE"      -> handleState(text)
                else -> AppLogger.i(TAG, "Unhandled WS type: ${typeOnly.type} — ${text.take(120)}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Message parse error: ${e.message} — ${text.take(120)}")
        }
    }

    private fun handleWifiList(text: String) {
        val event = gson.fromJson(text, WsApcWifiListEvent::class.java)
        val device = event.body?.firstOrNull()
        if (device?.cookerId != null) {
            cookerId = device.cookerId
            _deviceName.value = device.name ?: "Anova Precision Cooker"
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
            _connectionState.value = ConnectionState.CONNECTED
            AppLogger.i(TAG, "Connected — cookerId=${device.cookerId} name=${device.name}")
            sendStatusRequest()
        } else {
            AppLogger.w(TAG, "EVENT_APC_WIFI_LIST had no devices")
            _lastError.value = "No Anova device found on this account."
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun handleState(text: String) {
        val event = gson.fromJson(text, WsApcStateEvent::class.java)
        val body = event.body ?: return

        val unit = if (body.unit?.trim().equals("f", ignoreCase = true))
            TempUnit.FAHRENHEIT else TempUnit.CELSIUS
        val status = when (body.status?.lowercase()) {
            "cook" -> AnovaStatus.RUNNING
            "idle" -> AnovaStatus.STOPPED
            else   -> AnovaStatus.UNKNOWN
        }
        val timerRemainingSec = body.timer?.remainingSeconds
        val timerRemainingMin = timerRemainingSec?.let { it / 60 }

        val raw = AnovaRawState(
            currentTemp  = body.currentTemp,
            targetTemp   = body.targetTemp,
            unit         = unit,
            timerMinutes = timerRemainingMin,
            status       = status
        )
        cachedRawState = raw
        scope.launch { _rawStateFlow.emit(raw) }
        AppLogger.d(TAG, "State: ${body.currentTemp}° → ${body.targetTemp}° status=${body.status} timer=${timerRemainingMin}m")
    }

    private fun sendStatusRequest() {
        val id = cookerId ?: return
        sendCommand("CMD_APC_REQUEST_DEVICE_STATUS", mapOf("cookerId" to id))
    }

    private fun sendCommand(type: String, payload: Map<String, Any?>): Boolean {
        val ws = webSocket ?: return false
        val cmd = WsCommand(type = type, id = UUID.randomUUID().toString(), payload = payload)
        val json = gson.toJson(cmd)
        AppLogger.d(TAG, "Sending: ${json.take(120)}")
        return ws.send(json)
    }
}
