package com.template.app.anova.cloud

import com.google.gson.annotations.SerializedName

// -----------------------------------------------------------------------------------------
// Firebase auth
// -----------------------------------------------------------------------------------------

data class FirebaseSignInResponse(
    @SerializedName("idToken") val idToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresIn") val expiresIn: String   // seconds, as a string
)

data class FirebaseRefreshResponse(
    @SerializedName("id_token") val idToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: String
)

// Response from signInWithIdp (Google SSO exchange)
data class FirebaseIdpResponse(
    @SerializedName("idToken") val idToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresIn") val expiresIn: String? = null,
    @SerializedName("email") val email: String? = null
)

// -----------------------------------------------------------------------------------------
// Anova device list
// -----------------------------------------------------------------------------------------

data class AnovaDevicesResponse(
    @SerializedName("devices") val devices: List<AnovaDeviceInfo>? = null
)

data class AnovaDeviceInfo(
    @SerializedName("cooker_id") val cookerId: String,
    @SerializedName("alias") val alias: String? = null
)

// -----------------------------------------------------------------------------------------
// Anova device state
// Anova's API has used both snake_case and kebab-case field names across versions,
// so we map both and resolve at runtime.
// -----------------------------------------------------------------------------------------

data class AnovaStateResponse(
    // Status: "running" | "stopped"
    @SerializedName("status") val status: String? = null,

    // Current temperature (device unit)
    @SerializedName("current-temperature") val currentTempKebab: Float? = null,
    @SerializedName("current_temperature") val currentTempSnake: Float? = null,

    // Target temperature
    @SerializedName("target-temperature") val targetTempKebab: Float? = null,
    @SerializedName("target_temperature") val targetTempSnake: Float? = null,

    // Temperature unit: "c" or "f"
    @SerializedName("temperature-unit") val unitKebab: String? = null,
    @SerializedName("temperature_unit") val unitSnake: String? = null,

    @SerializedName("timer") val timer: AnovaTimerInfo? = null
) {
    val resolvedCurrentTemp: Float? get() = currentTempKebab ?: currentTempSnake
    val resolvedUnit: String? get() = unitKebab ?: unitSnake
}

data class AnovaTimerInfo(
    @SerializedName("current") val current: Int? = null,
    @SerializedName("initial") val initial: Int? = null
)
