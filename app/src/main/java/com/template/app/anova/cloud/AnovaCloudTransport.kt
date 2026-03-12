package com.template.app.anova.cloud

import com.google.gson.Gson
import com.template.app.anova.AnovaRawState
import com.template.app.anova.AnovaStatus
import com.template.app.anova.AnovaTransport
import com.template.app.anova.ConnectionState
import com.template.app.anova.TempUnit
import com.template.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
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
    // State is pushed automatically by the server after connection.
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

    /** Pending RESPONSE waiters keyed by requestId. */
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private var webSocket: WebSocket? = null
    private var cookerId: String? = null
    private var deviceType: String? = null
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

                // The WebSocket requires the Firebase ID token (1-hour lifetime),
                // NOT the Anova JWT. The Anova JWT causes a 1005 close.
                val firebaseToken = auth.getValidTokenOrRefresh()
                if (firebaseToken == null) {
                    val reason = auth.lastSignInError ?: "Not signed in. Configure your Anova account."
                    AppLogger.e(TAG, "No Firebase token — $reason")
                    _lastError.value = reason
                    _connectionState.value = ConnectionState.DISCONNECTED
                    return@launch
                }

                openWebSocket(firebaseToken)
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
        deviceType = null
        cachedRawState = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = null
    }

    override suspend fun poll(): AnovaRawState? {
        // Cloud transport is push-based; the server sends EVENT_APC_STATE automatically.
        // poll() just returns the last cached state without sending any request.
        return cachedRawState
    }

    override suspend fun startCook(): Boolean {
        val id = cookerId ?: return false
        val type = deviceType ?: return false
        // Use the last known target temp; fall back to 60°C if no state yet.
        val target = cachedRawState?.targetTemp?.toDouble() ?: 60.0
        val unit = if (cachedRawState?.unit == TempUnit.FAHRENHEIT) "F" else "C"
        return sendCommand("CMD_APC_START", mapOf(
            "cookerId" to id, "type" to type,
            "targetTemperature" to target, "unit" to unit
        ))
    }

    override suspend fun stopCook(): Boolean {
        val id = cookerId ?: return false
        val type = deviceType ?: return false
        return sendCommand("CMD_APC_STOP", mapOf("cookerId" to id, "type" to type))
    }

    override suspend fun updateCook(targetTemp: Float?, timerSeconds: Int?): Boolean {
        val id = cookerId ?: return false
        val type = deviceType ?: return false
        var ok = true
        if (targetTemp != null) {
            val unit = if (cachedRawState?.unit == TempUnit.FAHRENHEIT) "F" else "C"
            ok = sendCommand("CMD_APC_SET_TARGET_TEMP", mapOf(
                "cookerId" to id, "type" to type,
                "targetTemperature" to targetTemp.toDouble(), "unit" to unit
            )) && ok
        }
        if (timerSeconds != null) {
            ok = sendCommand("CMD_APC_SET_TIMER", mapOf(
                "cookerId" to id, "type" to type, "timer" to timerSeconds
            )) && ok
        }
        return ok
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun openWebSocket(firebaseToken: String) {
        // Cancel any timeout launched by a previous (failed) connection attempt.
        connectionTimeoutJob?.cancel()

        // IMPORTANT: Use Firebase ID token (not Anova JWT) — Anova JWT causes 1005 close.
        val url = "${AnovaCloudConfig.ANOVA_WS_BASE}?token=$firebaseToken&supportedAccessories=APC,APO&platform=android"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $firebaseToken")
            .build()
        AppLogger.i(TAG, "Opening WebSocket…")
        webSocket = client.newWebSocket(request, listener)
        // If EVENT_APC_WIFI_LIST never arrives, stop waiting after 30s.
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
            // Server pushes EVENT_APC_WIFI_LIST automatically — no explicit request needed.
            AppLogger.i(TAG, "WebSocket opened — waiting for device list…")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            AppLogger.i(TAG, "WebSocket closing (server): $code $reason")
            ws.close(1000, "")
            if (_connectionState.value == ConnectionState.CONNECTING) {
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                _lastError.value = "No Anova device found. Make sure the cooker is powered on and connected to Wi-Fi."
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            AppLogger.i(TAG, "WebSocket closed: $code $reason")
            if (_connectionState.value != ConnectionState.DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
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
            // Server sends "command" field, NOT "type"
            val cmdOnly = gson.fromJson(text, WsCommandOnly::class.java)
            AppLogger.d(TAG, "WS msg [${cmdOnly.command}]: ${text.take(200)}")
            when (cmdOnly.command) {
                "EVENT_APC_WIFI_LIST"  -> handleWifiList(text)
                "EVENT_APC_STATE"      -> handleState(text)
                "RESPONSE"             -> handleResponse(text)
                else -> AppLogger.i(TAG, "Unhandled WS command: ${cmdOnly.command} — ${text.take(120)}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Message parse error: ${e.message} — ${text.take(120)}")
        }
    }

    private fun handleWifiList(text: String) {
        val event = gson.fromJson(text, WsApcWifiListEvent::class.java)
        val device = event.payload?.firstOrNull()
        if (device?.cookerId != null) {
            cookerId = device.cookerId
            deviceType = device.type
            _deviceName.value = device.name ?: "Anova Precision Cooker"
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
            _connectionState.value = ConnectionState.CONNECTED
            AppLogger.i(TAG, "Connected — cookerId=${device.cookerId} name=${device.name}")
        } else {
            AppLogger.w(TAG, "EVENT_APC_WIFI_LIST had no devices")
            _lastError.value = "No Anova device found on this account."
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun handleState(text: String) {
        val event = gson.fromJson(text, WsApcStateEvent::class.java)
        val payload = event.payload ?: return
        val stateData = payload.state ?: return
        val nodes = stateData.nodes
        val modeState = stateData.state

        val unitStr = modeState?.temperatureUnit?.trim()
        val unit = if (unitStr.equals("F", ignoreCase = true)) TempUnit.FAHRENHEIT else TempUnit.CELSIUS

        val tempSensor = nodes?.waterTemperatureSensor
        val currentTemp = if (unit == TempUnit.FAHRENHEIT)
            tempSensor?.current?.fahrenheit
        else
            tempSensor?.current?.celsius

        val targetTemp = if (unit == TempUnit.FAHRENHEIT)
            tempSensor?.setpoint?.fahrenheit
        else
            tempSensor?.setpoint?.celsius

        val status = when (modeState?.mode?.lowercase()) {
            "cook" -> AnovaStatus.RUNNING
            "idle" -> AnovaStatus.STOPPED
            else   -> AnovaStatus.UNKNOWN
        }

        // Timer remaining: calculate from startedAtTimestamp + initial duration
        val timerNode = nodes?.timer
        val timerRemainingMin: Int? = run {
            val initial = timerNode?.initial ?: return@run null
            val startedAt = timerNode.startedAtTimestamp ?: return@run null
            try {
                val startMs = Instant.parse(startedAt).toEpochMilli()
                val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                val remainingSec = (initial - elapsedSec).coerceAtLeast(0)
                remainingSec / 60
            } catch (e: Exception) {
                AppLogger.w(TAG, "Timer parse error: ${e.message}")
                null
            }
        }

        val raw = AnovaRawState(
            currentTemp  = currentTemp,
            targetTemp   = targetTemp,
            unit         = unit,
            timerMinutes = timerRemainingMin,
            status       = status
        )
        cachedRawState = raw
        scope.launch { _rawStateFlow.emit(raw) }
        AppLogger.d(TAG, "State: ${currentTemp}° → ${targetTemp}° status=${modeState?.mode} timer=${timerRemainingMin}m")
    }

    private suspend fun sendCommand(command: String, payload: Map<String, Any?>): Boolean {
        val ws = webSocket ?: return false
        val requestId = UUID.randomUUID().toString()
        val cmd = WsCommand(command = command, requestId = requestId, payload = payload)
        val json = gson.toJson(cmd)
        AppLogger.d(TAG, "Sending: ${json.take(120)}")

        val deferred = CompletableDeferred<Boolean>()
        pendingCommands[requestId] = deferred

        if (!ws.send(json)) {
            pendingCommands.remove(requestId)
            AppLogger.w(TAG, "ws.send() failed (WebSocket buffer full or closed)")
            return false
        }

        // Wait up to 5 s for the server to acknowledge with a RESPONSE message.
        val ok = withTimeoutOrNull(5_000) { deferred.await() }
        pendingCommands.remove(requestId)
        if (ok == null) AppLogger.w(TAG, "Timeout waiting for RESPONSE to $command ($requestId)")
        return ok == true
    }

    private fun handleResponse(text: String) {
        val response = gson.fromJson(text, WsResponse::class.java)
        val requestId = response.requestId ?: return
        val ok = response.payload?.status?.equals("ok", ignoreCase = true) == true
        AppLogger.d(TAG, "RESPONSE [$requestId] status=${response.payload?.status}")
        pendingCommands[requestId]?.complete(ok)
    }
}
