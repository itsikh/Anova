package com.template.app.anova

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AnovaTransport {
    val connectionState: StateFlow<ConnectionState>
    val deviceName: StateFlow<String?>

    /**
     * Push-based transports (WebSocket) expose a Flow that emits on every state update.
     * Poll-based transports (BLE, WiFi) return null here.
     */
    val rawStateFlow: Flow<AnovaRawState>? get() = null

    suspend fun poll(): AnovaRawState?
    fun connect(address: String? = null)
    fun disconnect()

    /** Send a start-cook command. Returns true if accepted. Default: not supported. */
    suspend fun startCook(): Boolean = false

    /** Send a stop-cook command. Returns true if accepted. Default: not supported. */
    suspend fun stopCook(): Boolean = false

    /**
     * Update cook parameters. Pass null to leave a parameter unchanged.
     * Returns true if accepted. Default: not supported.
     */
    suspend fun updateCook(targetTemp: Float? = null, timerSeconds: Int? = null): Boolean = false
}
