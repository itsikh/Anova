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

/** Generic wrapper to read the `type` field of any inbound WS event. */
data class WsTypeOnly(@SerializedName("type") val type: String?)

/** Outbound command envelope. */
data class WsCommand(
    @SerializedName("type")    val type: String,
    @SerializedName("id")      val id: String,
    @SerializedName("payload") val payload: Map<String, Any?>
)

// EVENT_APC_WIFI_LIST
data class WsApcWifiListEvent(
    @SerializedName("type") val type: String?,
    @SerializedName("body") val body: List<ApcDeviceInfo>?
)

data class ApcDeviceInfo(
    @SerializedName("cookerId") val cookerId: String?,
    @SerializedName("name")     val name: String?,
    @SerializedName("type")     val type: String?
)

// EVENT_APC_STATE
data class WsApcStateEvent(
    @SerializedName("type") val type: String?,
    @SerializedName("body") val body: ApcStateBody?
)

data class ApcStateBody(
    @SerializedName("cookerId")     val cookerId: String?,
    @SerializedName("status")       val status: String?,       // "cook" | "idle"
    @SerializedName("targetTemp")   val targetTemp: Float?,
    @SerializedName("currentTemp")  val currentTemp: Float?,
    @SerializedName("unit")         val unit: String?,          // "c" | "f"
    @SerializedName("timer")        val timer: ApcTimer?
)

data class ApcTimer(
    @SerializedName("running") val running: Boolean?,
    @SerializedName("initial") val initial: Int?,
    @SerializedName("elapsed") val elapsed: Int?
) {
    /** Remaining seconds, or null if timer data is incomplete. */
    val remainingSeconds: Int?
        get() {
            val i = initial ?: return null
            val e = elapsed ?: return null
            return (i - e).coerceAtLeast(0)
        }
}

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
