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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
                title = { Text("Temperature History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (readings.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No data yet — connect to the device to start recording.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@Scaffold
            }

            // Mini chart — last 200 readings newest-first, so reverse for the chart
            val chartData = readings.reversed()
            TemperatureChart(readings = chartData)

            Spacer(Modifier.height(16.dp))
            Text(
                "Recent readings (${readings.size})",
                style = MaterialTheme.typography.titleSmall
            )
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
private fun TemperatureChart(readings: List<TemperatureReading>) {
    if (readings.size < 2) return

    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        colors = CardDefaults.cardColors(containerColor = surface)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val minTemp = readings.minOf { it.temperature }
            val maxTemp = readings.maxOf { it.temperature }
            val range = (maxTemp - minTemp).coerceAtLeast(1f)

            val path = Path()
            readings.forEachIndexed { index, reading ->
                val x = size.width * index / (readings.size - 1)
                val y = size.height * (1f - (reading.temperature - minTemp) / range)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = primary, style = Stroke(width = 3f))

            // Axis labels (min/max)
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 28f
                }
                canvas.nativeCanvas.drawText("%.1f".format(maxTemp), 4f, 28f, paint)
                canvas.nativeCanvas.drawText("%.1f".format(minTemp), 4f, size.height, paint)
            }
        }
    }
}

@Composable
private fun ReadingRow(reading: TemperatureReading) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTimestamp(reading.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "%.1f°%s".format(reading.temperature, reading.unit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = reading.status,
            style = MaterialTheme.typography.labelSmall,
            color = if (reading.status == "running") Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

private val timestampFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(millis: Long): String = timestampFmt.format(Date(millis))
