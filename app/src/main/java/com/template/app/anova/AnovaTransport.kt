package com.template.app.anova

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over a physical or logical connection to the Anova device.
 *
 * BLE, local WiFi TCP, and the cloud REST API all implement this interface so
 * [AnovaRepository] can switch between them without caring about the underlying transport.
 *
 * The primary contract is [poll]: it performs one complete read cycle and returns the
 * full device state, or null on failure. Each transport implements this differently:
 * - BLE/WiFi: sends 4 ASCII commands in sequence
 * - Cloud: makes one REST call to Anova's API
 */
interface AnovaTransport {
    val connectionState: StateFlow<ConnectionState>
    val deviceName: StateFlow<String?>

    /** Returns the current device state, or null if the poll failed or device is not connected. */
    suspend fun poll(): AnovaRawState?

    fun connect(address: String? = null)
    fun disconnect()
}
