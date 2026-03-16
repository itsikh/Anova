package com.template.app.anova

/** Parsed device state returned by every transport's poll(). */
data class AnovaRawState(
    val currentTemp: Float?,
    val targetTemp: Float? = null,
    val unit: TempUnit = TempUnit.CELSIUS,
    val timerMinutes: Int?,
    val status: AnovaStatus = AnovaStatus.UNKNOWN
)
