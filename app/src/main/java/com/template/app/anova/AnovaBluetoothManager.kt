package com.template.app.anova

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.template.app.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaBLE"
private const val RESPONSE_TIMEOUT_MS = 3000L
private const val COMMAND_DELAY_MS = 300L

@Singleton
@SuppressLint("MissingPermission") // Caller (MonitorScreen) ensures permissions are granted first
class AnovaBluetoothManager @Inject constructor(
    @ApplicationContext private val context: Context
) : AnovaTransport {

    private val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var anovaCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val responseChannel = Channel<String>(Channel.UNLIMITED)

    // -----------------------------------------------------------------------------------------
    // AnovaTransport
    // -----------------------------------------------------------------------------------------

    /** [address] is ignored for BLE — device is found by scanning for name "Anova". */
    override fun connect(address: String?) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            AppLogger.e(TAG, "BLE not available")
            return
        }
        AppLogger.i(TAG, "Scanning for Anova…")
        _connectionState.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder().setDeviceName(AnovaProtocol.DEVICE_NAME_PREFIX).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    override fun disconnect() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Exception during GATT disconnect/close: ${e.message}")
        }
        gatt = null
        anovaCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = null
        AppLogger.i(TAG, "Disconnected")
    }

    override suspend fun poll(): AnovaRawState? {
        if (_connectionState.value != ConnectionState.CONNECTED) return null
        return try {
            val tempStr = sendCommand(AnovaProtocol.CMD_READ_TEMP)
            if (tempStr == null) AppLogger.w(TAG, "No response to ${AnovaProtocol.CMD_READ_TEMP} (timeout)")
            val temp = tempStr?.trim()?.toFloatOrNull()
            delay(COMMAND_DELAY_MS)

            val unitStr = sendCommand(AnovaProtocol.CMD_READ_UNIT)
            if (unitStr == null) AppLogger.w(TAG, "No response to ${AnovaProtocol.CMD_READ_UNIT} (timeout)")
            val unit = if (unitStr?.trim().equals("f", ignoreCase = true)) TempUnit.FAHRENHEIT else TempUnit.CELSIUS
            delay(COMMAND_DELAY_MS)

            val timerStr = sendCommand(AnovaProtocol.CMD_READ_TIMER)
            if (timerStr == null) AppLogger.w(TAG, "No response to ${AnovaProtocol.CMD_READ_TIMER} (timeout)")
            val timer = timerStr?.trim()?.toIntOrNull()
            delay(COMMAND_DELAY_MS)

            val statusStr = sendCommand(AnovaProtocol.CMD_STATUS)
            if (statusStr == null) AppLogger.w(TAG, "No response to ${AnovaProtocol.CMD_STATUS} (timeout)")
            val status = when {
                statusStr?.contains("running", ignoreCase = true) == true -> AnovaStatus.RUNNING
                statusStr?.contains("stopped", ignoreCase = true) == true -> AnovaStatus.STOPPED
                else -> AnovaStatus.UNKNOWN
            }

            AnovaRawState(currentTemp = temp, unit = unit, timerMinutes = timer, status = status)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Poll failed: ${e.message} — disconnecting")
            handleDisconnect()
            null
        }
    }

    // -----------------------------------------------------------------------------------------
    // GATT callbacks
    // -----------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            AppLogger.d(TAG, "onConnectionStateChange: state=$newState status=$status device=${gatt.device.name ?: gatt.device.address}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.e(TAG, "GATT error on state change: status=$status (0x${status.toString(16)}) newState=$newState — disconnecting")
                handleDisconnect()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    AppLogger.i(TAG, "Connected to ${gatt.device.name ?: gatt.device.address}, discovering services…")
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    AppLogger.i(TAG, "Disconnected from ${gatt.device.name ?: gatt.device.address}")
                    handleDisconnect()
                }
                else -> AppLogger.d(TAG, "Unhandled GATT state: $newState")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.e(TAG, "Service discovery failed: $status")
                handleDisconnect(); return
            }
            val service = gatt.getService(AnovaProtocol.SERVICE_UUID)
            if (service == null) {
                AppLogger.e(TAG, "Anova service (FFE0) not found — verify UUID with nRF Connect")
                handleDisconnect(); return
            }
            val characteristic = service.getCharacteristic(AnovaProtocol.CHARACTERISTIC_UUID)
            if (characteristic == null) {
                AppLogger.e(TAG, "Anova characteristic (FFE1) not found")
                handleDisconnect(); return
            }
            anovaCharacteristic = characteristic
            enableNotifications(gatt, characteristic)
            AppLogger.i(TAG, "Ready — notifications enabled on FFE1")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val response = String(value).trim()
            AppLogger.d(TAG, "← $response")
            responseChannel.trySend(response)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Required for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                responseChannel.trySend(String(characteristic.value ?: return).trim())
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            AppLogger.i(TAG, "Found: ${device.name ?: device.address}")
            stopScan()
            _connectionState.value = ConnectionState.CONNECTING
            _deviceName.value = device.name ?: device.address
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            AppLogger.e(TAG, "Scan failed: $errorCode")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // -----------------------------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------------------------

    private suspend fun sendCommand(command: String): String? {
        val char = anovaCharacteristic ?: run {
            AppLogger.w(TAG, "sendCommand($command) — no characteristic, dropping")
            return null
        }
        val currentGatt = gatt ?: run {
            AppLogger.w(TAG, "sendCommand($command) — no GATT, dropping")
            return null
        }
        while (responseChannel.tryReceive().isSuccess) {} // drain stale responses
        val data = (command + AnovaProtocol.CMD_TERMINATOR).toByteArray()
        AppLogger.d(TAG, "→ $command")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION") char.value = data
                @Suppress("DEPRECATION") char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION") currentGatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "writeCharacteristic($command) threw: ${e.message}")
            return null
        }
        return withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { responseChannel.receive() }
    }

    private fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(AnovaProtocol.CCCD_UUID) ?: run {
            AppLogger.e(TAG, "CCCD descriptor (0x2902) not found on FFE1 — notifications won't work")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
            }
            AppLogger.d(TAG, "CCCD notification descriptor written")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write CCCD descriptor: ${e.message}")
        }
    }

    private fun handleDisconnect() {
        gatt?.close(); gatt = null
        anovaCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = null
    }
}
