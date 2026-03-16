package com.template.app.anova

data class ThresholdSettings(
    val minTempEnabled: Boolean = true,
    val minTemp: Float = 0f,       // 0 = not yet calculated; updated from target on first state
    val isAutoMin: Boolean = true, // true = auto-track target * 0.9; false = user override
    val maxTempEnabled: Boolean = false,
    val maxTemp: Float = 90f
)
