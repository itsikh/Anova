package com.template.app.anova

data class AnovaDeviceState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val cookerId: String? = null,
    val currentTemp: Float? = null,
    val targetTemp: Float? = null,
    val unit: TempUnit = TempUnit.CELSIUS,
    val timerMinutes: Int? = null,
    val status: AnovaStatus = AnovaStatus.UNKNOWN,
    val deviceName: String? = null,
    val lastUpdated: Long = 0L,
    val connectionError: String? = null
)

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED,
    /** Lost connection — auto-retry in progress. Offline notification held until retries exhausted. */
    RECONNECTING
}

enum class TempUnit {
    CELSIUS, FAHRENHEIT;
    val symbol: String get() = if (this == CELSIUS) "°C" else "°F"
}

enum class AnovaStatus {
    RUNNING, STOPPED, UNKNOWN
}
