package com.template.app.anova

import java.util.UUID

/**
 * BLE UUIDs and command constants for the Anova Precision Cooker.
 *
 * The Anova Precision Cooker 3.0 uses a single BLE characteristic (FFE1) under
 * service FFE0 for both writing commands and receiving notifications. Commands are
 * plain ASCII strings terminated with \r; responses are ASCII strings.
 *
 * If the device does not connect, check the actual GATT profile using a BLE scanner
 * app (e.g. nRF Connect) and update SERVICE_UUID / CHARACTERISTIC_UUID in AppConfig.
 */
object AnovaProtocol {
    // BLE UUIDs — see AppConfig for the actual strings so they are easy to change
    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    // Standard Bluetooth CCCD descriptor — enables notifications on the characteristic
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ASCII commands sent to the device
    const val CMD_READ_TEMP = "read temp"
    const val CMD_READ_UNIT = "read unit"
    const val CMD_READ_TIMER = "read timer"
    const val CMD_STATUS = "status"
    const val CMD_TERMINATOR = "\r"

    // BLE advertisement name prefix used to filter scan results
    const val DEVICE_NAME_PREFIX = "Anova"
}
