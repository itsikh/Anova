package com.template.app.ui.screens.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.template.app.history.TemperatureReading
import com.template.app.ui.theme.AnovaOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    vm: HistoryViewModel = hiltViewModel()
) {
    val readings by vm.readings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (readings.isNotEmpty()) {
                        IconButton(onClick = { vm.exportCsv() }) {
                            Icon(Icons.Default.Share, "Export CSV")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)
        ) {
            if (readings.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data yet — connect to the device to start recording.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                return@Scaffold
            }

            val chartData = readings.reversed()

            // Stats row
            val minTemp = chartData.minOf { it.temperature }
            val maxTemp = chartData.maxOf { it.temperature }
            val avgTemp = chartData.map { it.temperature }.average().toFloat()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip("Min", "%.1f°".format(minTemp))
                StatChip("Avg", "%.1f°".format(avgTemp))
                StatChip("Max", "%.1f°".format(maxTemp))
                StatChip("Readings", readings.size.toString())
            }

            Spacer(Modifier.height(12.dp))

            TemperatureChart(readings = chartData)

            Spacer(Modifier.height(16.dp))
            Text("Recent readings", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(readings, key = { it.id }) { reading ->
                    ReadingRow(reading)
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = AnovaOrange)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TemperatureChart(readings: List<TemperatureReading>) {
    if (readings.size < 2) return

    val primary     = AnovaOrange
    val gridColor   = MaterialTheme.colorScheme.outlineVariant
    val labelColor  = android.graphics.Color.parseColor("#88888888")

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val minTemp = readings.minOf { it.temperature }
            val maxTemp = readings.maxOf { it.temperature }
            val range   = (maxTemp - minTemp).coerceAtLeast(1f)
            val w = size.width
            val h = size.height

            // Grid lines (3)
            for (i in 0..2) {
                val y = h * i / 2f
                drawLine(Color(0x22888888), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            // Temperature line with gradient fill area
            val path = Path()
            val fillPath = Path()
            readings.forEachIndexed { index, reading ->
                val x = w * index / (readings.size - 1)
                val y = h * (1f - (reading.temperature - minTemp) / range)
                if (index == 0) { path.moveTo(x, y); fillPath.moveTo(x, h) ; fillPath.lineTo(x, y) }
                else { path.lineTo(x, y); fillPath.lineTo(x, y) }
            }
            fillPath.lineTo(w, h); fillPath.close()
            drawPath(fillPath, color = AnovaOrange.copy(alpha = 0.08f))
            drawPath(path, color = primary, style = Stroke(width = 2.5f))

            // Min/max labels
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = labelColor; textSize = 26f; isAntiAlias = true
                }
                canvas.nativeCanvas.drawText("%.1f".format(maxTemp), 4f, 24f, paint)
                canvas.nativeCanvas.drawText("%.1f".format(minTemp), 4f, h - 4f, paint)
            }
        }
    }
}

@Composable
private fun ReadingRow(reading: TemperatureReading) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(formatTimestamp(reading.timestamp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("%.1f°%s".format(reading.temperature, reading.unit), style = MaterialTheme.typography.bodyMedium)
        Text(reading.status, style = MaterialTheme.typography.labelSmall,
            color = if (reading.status == "running") Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp))
    }
}

private val timestampFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
private fun formatTimestamp(millis: Long): String = timestampFmt.format(Date(millis))
