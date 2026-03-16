package com.template.app.ui.screens.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.template.app.schedule.ScheduleEntry
import com.template.app.ui.theme.AnovaOrange
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    vm: ScheduleViewModel = hiltViewModel()
) {
    val schedules by vm.schedules.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = AnovaOrange) {
                Icon(Icons.Default.Add, "Add schedule")
            }
        }
    ) { innerPadding ->
        if (schedules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No scheduled commands", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to schedule a start or stop", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(schedules, key = { it.id }) { entry ->
                    ScheduleCard(entry = entry, onDelete = { vm.deleteSchedule(entry) })
                }
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            onConfirm = { vm.addSchedule(it) },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun ScheduleCard(entry: ScheduleEntry, onDelete: () -> Unit) {
    val dateFmt = SimpleDateFormat("EEE, MMM d  HH:mm", Locale.getDefault())
    val isPast = entry.scheduledAtMs < System.currentTimeMillis()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!entry.isPending || isPast)
                MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (entry.type == "START") Icons.Default.PlayArrow else Icons.Default.Stop,
                    contentDescription = null,
                    tint = if (entry.type == "START") AnovaOrange else MaterialTheme.colorScheme.error
                )
                Column {
                    Text(entry.type.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge)
                    Text(dateFmt.format(Date(entry.scheduledAtMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!entry.isPending) Text("Fired", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entry.isPending && !isPast) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddScheduleDialog(onConfirm: (ScheduleEntry) -> Unit, onDismiss: () -> Unit) {
    var type        by remember { mutableStateOf("START") }
    var dateStr     by remember { mutableStateOf("") }
    var timeStr     by remember { mutableStateOf("") }
    var tempStr     by remember { mutableStateOf("") }
    var timerHrsStr by remember { mutableStateOf("") }
    var timerMinStr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("START", "STOP").forEachIndexed { index, t ->
                        SegmentedButton(
                            selected = type == t, onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) { Text(t) }
                    }
                }

                OutlinedTextField(value = dateStr, onValueChange = { dateStr = it },
                    label = { Text("Date (YYYY-MM-DD)") }, singleLine = true,
                    placeholder = { Text(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = timeStr, onValueChange = { timeStr = it },
                    label = { Text("Time (HH:MM)") }, singleLine = true,
                    placeholder = { Text("08:00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())

                if (type == "START") {
                    OutlinedTextField(value = tempStr, onValueChange = { tempStr = it },
                        label = { Text("Target temperature (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = timerHrsStr, onValueChange = { timerHrsStr = it },
                            label = { Text("Hours") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f))
                        OutlinedTextField(value = timerMinStr, onValueChange = { timerMinStr = it },
                            label = { Text("Minutes") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cal = Calendar.getInstance()
                try {
                    val dateParts = dateStr.ifBlank {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    }.split("-")
                    val timeParts = timeStr.ifBlank { "08:00" }.split(":")
                    cal.set(dateParts[0].toInt(), dateParts[1].toInt() - 1, dateParts[2].toInt(),
                        timeParts[0].toInt(), timeParts[1].toInt(), 0)
                    cal.set(Calendar.MILLISECOND, 0)
                } catch (e: Exception) { return@TextButton }

                val targetTemp = tempStr.toFloatOrNull()
                val timerSecs = run {
                    val h = timerHrsStr.toIntOrNull() ?: 0
                    val m = timerMinStr.toIntOrNull() ?: 0
                    if (h == 0 && m == 0) null else h * 3600 + m * 60
                }
                onConfirm(ScheduleEntry(
                    type = type, scheduledAtMs = cal.timeInMillis,
                    targetTemp = targetTemp, timerSeconds = timerSecs
                ))
                onDismiss()
            }) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
