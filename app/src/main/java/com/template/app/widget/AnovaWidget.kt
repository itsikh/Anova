package com.template.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.template.app.MainActivity
import com.template.app.anova.AnovaDeviceState
import com.template.app.anova.AnovaStatus
import com.template.app.anova.ConnectionState
import dagger.hilt.android.EntryPointAccessors

private val ColorOrange = Color(0xFFFF8B01)
private val ColorNavy   = Color(0xFF07131C)
private val ColorGreen  = Color(0xFF4CAF50)
private val ColorRed    = Color(0xFFE53935)
private val ColorWhite  = Color(0xFFFFFFFF)
private val ColorGrey   = Color(0xFF8A9BA8)

class AnovaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors
            .fromApplication(context.applicationContext, AnovaWidgetEntryPoint::class.java)
            .anovaRepository()

        val state = repo.deviceState.value

        provideContent {
            GlanceTheme {
                WidgetContent(state = state, context = context)
            }
        }
    }
}

@Composable
private fun WidgetContent(state: AnovaDeviceState, context: Context) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(ColorNavy))
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Header row: wordmark + status badge
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ANOVA",
                    style = TextStyle(
                        color = ColorProvider(ColorOrange),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(GlanceModifier.width(8.dp))
                StatusBadge(state)
            }

            Spacer(GlanceModifier.height(6.dp))

            when (state.connectionState) {
                ConnectionState.CONNECTED     -> ConnectedContent(state)
                ConnectionState.CONNECTING,
                ConnectionState.SCANNING,
                ConnectionState.RECONNECTING  -> CenteredLabel("Connecting…", ColorGrey)
                ConnectionState.DISCONNECTED  -> CenteredLabel("Disconnected", ColorGrey)
            }
        }
    }
}

@Composable
private fun ConnectedContent(state: AnovaDeviceState) {
    val tempLabel = state.currentTemp?.let { "%.1f%s".format(it, state.unit.symbol) } ?: "—"
    val targetLabel = state.targetTemp?.let { "→ %.1f%s".format(it, state.unit.symbol) } ?: ""

    // Large temperature
    Text(
        text = tempLabel,
        style = TextStyle(
            color = ColorProvider(ColorWhite),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold
        )
    )

    if (targetLabel.isNotEmpty()) {
        Text(
            text = targetLabel,
            style = TextStyle(
                color = ColorProvider(ColorGrey),
                fontSize = 13.sp
            )
        )
    }

    // Timer if running
    val timerMin = state.timerMinutes
    if (timerMin != null && state.status == AnovaStatus.RUNNING) {
        val h = timerMin / 60
        val m = timerMin % 60
        val timerStr = if (h > 0) "${h}h ${m}m left" else "${m}m left"
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = timerStr,
            style = TextStyle(
                color = ColorProvider(ColorOrange),
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun StatusBadge(state: AnovaDeviceState) {
    val (label, color) = when {
        state.connectionState != ConnectionState.CONNECTED -> "•" to ColorGrey
        state.status == AnovaStatus.RUNNING -> "● RUNNING" to ColorGreen
        state.status == AnovaStatus.STOPPED -> "● STOPPED" to ColorRed
        else -> "●" to ColorGrey
    }
    Text(
        text = label,
        style = TextStyle(
            color = ColorProvider(color),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    )
}

@Composable
private fun CenteredLabel(text: String, color: Color) {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = ColorProvider(color),
                fontSize = 14.sp
            )
        )
    }
}
