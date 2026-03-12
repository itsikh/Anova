package com.template.app.anova.cloud

import com.google.gson.annotations.SerializedName

// ── Firebase auth ─────────────────────────────────────────────────────────────

data class FirebaseSignInResponse(
    @SerializedName("idToken")       val idToken: String,
    @SerializedName("refreshToken")  val refreshToken: String,
    @SerializedName("expiresIn")     val expiresIn: String
)

data class FirebaseRefreshResponse(
    @SerializedName("id_token")      val idToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in")    val expiresIn: String
)

data class FirebaseIdpResponse(
    @SerializedName("idToken")       val idToken: String,
    @SerializedName("refreshToken")  val refreshToken: String,
    @SerializedName("expiresIn")     val expiresIn: String? = null,
    @SerializedName("email")         val email: String? = null
)

data class CreateAuthUriResponse(
    @SerializedName("authUri")   val authUri: String,
    @SerializedName("sessionId") val sessionId: String
)

// ── Anova JWT ─────────────────────────────────────────────────────────────────

data class AnovaAuthResponse(
    @SerializedName("jwt") val jwt: String
)

// ── WebSocket messages ────────────────────────────────────────────────────────

/** Generic wrapper to read the `command` field of any inbound WS event. */
data class WsCommandOnly(@SerializedName("command") val command: String?)

/** Outbound command envelope. Server expects `command` (not `type`). */
data class WsCommand(
    @SerializedName("command")   val command: String,
    @SerializedName("requestId") val requestId: String,
    @SerializedName("payload")   val payload: Map<String, Any?>
)

// EVENT_APC_WIFI_LIST
// payload is a list of connected devices
data class WsApcWifiListEvent(
    @SerializedName("command") val command: String?,
    @SerializedName("payload") val payload: List<ApcDeviceInfo>?
)

data class ApcDeviceInfo(
    @SerializedName("cookerId") val cookerId: String?,
    @SerializedName("name")     val name: String?,
    @SerializedName("type")     val type: String?
)

// EVENT_APC_STATE
// payload.cookerId, payload.state.nodes.waterTemperatureSensor.{current,setpoint}.{celsius,fahrenheit}
// payload.state.state.{mode, temperatureUnit}
// payload.state.nodes.timer.{initial, startedAtTimestamp}
data class WsApcStateEvent(
    @SerializedName("command") val command: String?,
    @SerializedName("payload") val payload: ApcStatePayload?
)

data class ApcStatePayload(
    @SerializedName("cookerId") val cookerId: String?,
    @SerializedName("state")    val state: ApcStateData?
)

data class ApcStateData(
    @SerializedName("nodes") val nodes: ApcStateNodes?,
    @SerializedName("state") val state: ApcStateMode?,
    @SerializedName("systemInfo") val systemInfo: ApcSystemInfo?
)

data class ApcStateNodes(
    @SerializedName("waterTemperatureSensor") val waterTemperatureSensor: ApcTempSensor?,
    @SerializedName("timer")                  val timer: ApcTimerNode?
)

data class ApcTempSensor(
    @SerializedName("current")  val current: ApcTempValue?,
    @SerializedName("setpoint") val setpoint: ApcTempValue?
)

data class ApcTempValue(
    @SerializedName("celsius")    val celsius: Float?,
    @SerializedName("fahrenheit") val fahrenheit: Float?
)

data class ApcStateMode(
    @SerializedName("mode")            val mode: String?,            // "cook" | "idle"
    @SerializedName("temperatureUnit") val temperatureUnit: String?  // "C" | "F"
)

data class ApcSystemInfo(
    @SerializedName("online") val online: Boolean?
)

data class ApcTimerNode(
    @SerializedName("initial")              val initial: Int?,
    @SerializedName("startedAtTimestamp")   val startedAtTimestamp: String?
)

// ── Old REST models (kept for reference / potential fallback) ─────────────────

data class AnovaDevicesResponse(
    @SerializedName("devices") val devices: List<AnovaDeviceInfo>? = null
)

data class AnovaDeviceInfo(
    @SerializedName("cooker_id") val cookerId: String,
    @SerializedName("alias")     val alias: String? = null
)

data class AnovaStateResponse(
    @SerializedName("status")              val status: String? = null,
    @SerializedName("current-temperature") val currentTempKebab: Float? = null,
    @SerializedName("current_temperature") val currentTempSnake: Float? = null,
    @SerializedName("target-temperature")  val targetTempKebab: Float? = null,
    @SerializedName("target_temperature")  val targetTempSnake: Float? = null,
    @SerializedName("temperature-unit")    val unitKebab: String? = null,
    @SerializedName("temperature_unit")    val unitSnake: String? = null,
    @SerializedName("timer")               val timer: AnovaTimerInfo? = null
) {
    val resolvedCurrentTemp: Float? get() = currentTempKebab ?: currentTempSnake
    val resolvedTargetTemp:  Float? get() = targetTempKebab  ?: targetTempSnake
    val resolvedUnit:        String? get() = unitKebab ?: unitSnake
}

data class AnovaTimerInfo(
    @SerializedName("current") val current: Int? = null,
    @SerializedName("initial") val initial: Int? = null
)
