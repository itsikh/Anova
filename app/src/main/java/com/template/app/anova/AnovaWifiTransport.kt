package com.template.app.anova

import android.content.Context
import android.net.ConnectivityManager
import com.template.app.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaWiFi"
private const val ANOVA_TCP_PORT = 8080
private const val CONNECT_TIMEOUT_MS = 5000
private const val RESPONSE_TIMEOUT_MS = 3000L
private const val SCAN_CONNECT_TIMEOUT_MS = 500
private const val SCAN_RESPONSE_TIMEOUT_MS = 500L

/**
 * Connects to the Anova Precision Cooker over the local WiFi network via TCP.
 *
 * The device listens on [ANOVA_TCP_PORT] and uses the same ASCII command protocol as BLE.
 * If the device IP is unknown, call [discoverDevice] to scan the local /24 subnet.
 *
 * This transport only works on the same local network as the device. For remote access,
 * use [cloud.AnovaCloudTransport].
 */
@Singleton
class AnovaWifiTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : AnovaTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun connect(address: String?) {
        if (address.isNullOrBlank()) { AppLogger.e(TAG, "No IP address provided"); return }
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING
        AppLogger.i(TAG, "Connecting to $address:$ANOVA_TCP_PORT…")
        scope.launch {
            try {
                val s = Socket()
                s.connect(java.net.InetSocketAddress(address, ANOVA_TCP_PORT), CONNECT_TIMEOUT_MS)
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(s.getInputStream()))
                _deviceName.value = "Anova @ $address"
                _connectionState.value = ConnectionState.CONNECTED
                AppLogger.i(TAG, "Connected via WiFi to $address")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Connection failed: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    override fun disconnect() {
        runCatching { writer?.close(); reader?.close(); socket?.close() }
        socket = null; writer = null; reader = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = null
        AppLogger.i(TAG, "Disconnected")
    }

    override suspend fun poll(): AnovaRawState? {
        if (_connectionState.value != ConnectionState.CONNECTED) return null
        return try {
            val temp = sendCommand(AnovaProtocol.CMD_READ_TEMP)?.trim()?.toFloatOrNull()
            val unitStr = sendCommand(AnovaProtocol.CMD_READ_UNIT)
            val unit = if (unitStr?.trim().equals("f", ignoreCase = true)) TempUnit.FAHRENHEIT else TempUnit.CELSIUS
            val timer = sendCommand(AnovaProtocol.CMD_READ_TIMER)?.trim()?.toIntOrNull()
            val statusStr = sendCommand(AnovaProtocol.CMD_STATUS)
            val status = when {
                statusStr?.contains("running", ignoreCase = true) == true -> AnovaStatus.RUNNING
                statusStr?.contains("stopped", ignoreCase = true) == true -> AnovaStatus.STOPPED
                else -> AnovaStatus.UNKNOWN
            }
            AnovaRawState(currentTemp = temp, unit = unit, timerMinutes = timer, status = status)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Poll failed: ${e.message} — disconnecting")
            disconnect()
            null
        }
    }

    /**
     * Scans the local /24 subnet in parallel on [ANOVA_TCP_PORT] and returns the IP of the first
     * host that accepts the connection and responds to a status command (indicating an Anova device).
     * Returns null if not connected to WiFi or no device is found.
     */
    suspend fun discoverDevice(): String? = withContext(Dispatchers.IO) {
        val localIp = getLocalIp() ?: run {
            AppLogger.e(TAG, "Cannot discover: not connected to WiFi")
            return@withContext null
        }
        val subnet = localIp.substringBeforeLast(".")
        AppLogger.i(TAG, "Scanning $subnet.0/24 for Anova on port $ANOVA_TCP_PORT…")
        coroutineScope {
            (1..254).map { host ->
                async {
                    val candidate = "$subnet.$host"
                    if (candidate == localIp) return@async null
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(candidate, ANOVA_TCP_PORT), SCAN_CONNECT_TIMEOUT_MS)
                            val w = PrintWriter(s.getOutputStream(), true)
                            val r = BufferedReader(InputStreamReader(s.getInputStream()))
                            w.print(AnovaProtocol.CMD_STATUS + AnovaProtocol.CMD_TERMINATOR)
                            w.flush()
                            withTimeoutOrNull(SCAN_RESPONSE_TIMEOUT_MS) { r.readLine() }
                                ?.let { candidate }
                        }
                    } catch (_: Exception) { null }
                }
            }.awaitAll().firstOrNull()
        }.also { found ->
            if (found != null) AppLogger.i(TAG, "Discovered Anova at $found")
            else AppLogger.i(TAG, "No Anova device found on $subnet.0/24")
        }
    }

    private fun getLocalIp(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private suspend fun sendCommand(command: String): String? = withContext(Dispatchers.IO) {
        val w = writer ?: return@withContext null
        val r = reader ?: return@withContext null
        AppLogger.d(TAG, "→ $command")
        w.print(command + AnovaProtocol.CMD_TERMINATOR)
        w.flush()
        withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            r.readLine()?.trim().also { AppLogger.d(TAG, "← $it") }
        }
    }
}
