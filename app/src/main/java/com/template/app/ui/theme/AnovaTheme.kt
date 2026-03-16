package com.template.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Anova brand palette ──────────────────────────────────────────────────────
val AnovaOrange      = Color(0xFFFF8B01)   // Anova signature orange
val AnovaOrangeDim   = Color(0xFFED7200)   // accessible / pressed
val AnovaOrangeLight = Color(0xFFFFE0B2)   // container / tonal surface
val AnovaBlue        = Color(0xFF256BC1)
val AnovaNavy        = Color(0xFF07131C)   // dark background
val AnovaNavySurface = Color(0xFF0D1F2E)
val AnovaGray        = Color(0xFFECEEF0)
val AnovaText        = Color(0xFF2B2B2B)

// ── Color schemes ─────────────────────────────────────────────────────────────
private val LightScheme = lightColorScheme(
    primary               = AnovaOrange,
    onPrimary             = Color.White,
    primaryContainer      = AnovaOrangeLight,
    onPrimaryContainer    = Color(0xFF3E1000),
    secondary             = AnovaBlue,
    onSecondary           = Color.White,
    secondaryContainer    = Color(0xFFD6E4FF),
    onSecondaryContainer  = Color(0xFF001944),
    tertiary              = Color(0xFF4CAF50),  // running green
    tertiaryContainer     = Color(0xFFD4EDDA),
    background            = Color.White,
    onBackground          = AnovaText,
    surface               = Color(0xFFF7F8FA),
    onSurface             = AnovaText,
    surfaceVariant        = AnovaGray,
    onSurfaceVariant      = Color(0xFF5A5A5A),
    error                 = Color(0xFFD32F2F),
    onError               = Color.White,
    outline               = Color(0xFFDDDDDD),
)

private val DarkScheme = darkColorScheme(
    primary               = AnovaOrange,
    onPrimary             = Color(0xFF3E1000),
    primaryContainer      = Color(0xFF5E2000),
    onPrimaryContainer    = AnovaOrangeLight,
    secondary             = Color(0xFF90C4FF),
    onSecondary           = Color(0xFF00264E),
    secondaryContainer    = Color(0xFF004880),
    onSecondaryContainer  = Color(0xFFD1E4FF),
    tertiary              = Color(0xFF66BB6A),
    tertiaryContainer     = Color(0xFF1B3D1C),
    background            = Color(0xFF0F0F0F),   // deep charcoal
    onBackground          = Color(0xFFEAEAEA),
    surface               = Color(0xFF191919),   // slightly elevated charcoal
    onSurface             = Color(0xFFEAEAEA),
    surfaceVariant        = Color(0xFF222222),
    onSurfaceVariant      = Color(0xFF909090),
    error                 = Color(0xFFEF5350),
    onError               = Color(0xFF690005),
    outline               = Color(0xFF333333),
)

@Composable
fun AnovaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
