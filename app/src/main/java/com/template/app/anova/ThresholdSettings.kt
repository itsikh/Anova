package com.template.app.anova

data class ThresholdSettings(
    val minTempEnabled: Boolean = false,
    val minTemp: Float = 40f,
    val maxTempEnabled: Boolean = false,
    val maxTemp: Float = 90f
)
