package com.template.app.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

private val DC_Bg      = Color(0xFF191919)
private val DC_Orange  = Color(0xFFFF6600)
private val DC_TextPrimary = Color(0xFFEAEAEA)
private val DC_TextDim = Color(0xFF909090)
private val DC_TextMuted = Color(0xFF606060)
private val DC_Track   = Color(0xFF1D1D1D)
private val DC_Surface = Color(0xFF242424)
private val DC_AlertBorder = Color(0x44FF6600)
private val DC_AlertBg = Color(0x1AFF6600)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsSheet(
    useCelsius: Boolean,
    onDismiss: () -> Unit,
    vm: PresetsViewModel = hiltViewModel()
) {
    val presets by vm.presets.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showAddEdit by remember { mutableStateOf(false) }
    var editTarget  by remember { mutableStateOf<Preset?>(null) }
    var errorMsg    by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DC_Bg,
        contentColor = DC_TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Presets",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = DC_TextPrimary
                )
                Button(
                    onClick = { editTarget = null; showAddEdit = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DC_Orange),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No presets yet. Tap New to add one.",
                        color = DC_TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        PresetCard(
                            preset = preset,
                            useCelsius = useCelsius,
                            onStart = {
                                vm.startCookWithPreset(
                                    preset,
                                    onError = { errorMsg = it },
                                    onDone  = { onDismiss() }
                                )
                            },
                            onEdit = { editTarget = preset; showAddEdit = true },
                            onDelete = { vm.delete(preset) }
                        )
                    }
                }
            }
        }
    }

    if (showAddEdit) {
        PresetEditDialog(
            initial   = editTarget,
            useCelsius = useCelsius,
            onSave    = { vm.save(it); showAddEdit = false },
            onDismiss = { showAddEdit = false }
        )
    }

    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            title = { Text("Error") },
            text  = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorMsg = null }) { Text("OK") } }
        )
    }
}

// ── Preset card ───────────────────────────────────────────────────────────────

@Composable
private fun PresetCard(
    preset: Preset,
    useCelsius: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val displayTemp = if (useCelsius) preset.targetTemp
                      else preset.targetTemp * 9f / 5f + 32f
    val unit = if (useCelsius) "C" else "F"
    val timerStr = if (preset.timerMinutes > 0) {
        val h = preset.timerMinutes / 60; val m = preset.timerMinutes % 60
        if (h > 0 && m > 0) "${h}h ${m}m" else if (h > 0) "${h}h" else "${m}m"
    } else "No timer"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DC_Surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.name, fontWeight = FontWeight.SemiBold, color = DC_TextPrimary, fontSize = 15.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                "%.1f°%s  ·  %s".format(displayTemp, unit, timerStr),
                color = DC_Orange,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (preset.notes.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(preset.notes, color = DC_TextMuted, fontSize = 12.sp)
            }
        }

        // Actions
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, "Edit", tint = DC_TextDim, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete", tint = DC_TextDim, modifier = Modifier.size(18.dp))
        }
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Start", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

// ── Add / Edit dialog ─────────────────────────────────────────────────────────

@Composable
private fun PresetEditDialog(
    initial: Preset?,
    useCelsius: Boolean,
    onSave: (Preset) -> Unit,
    onDismiss: () -> Unit
) {
    val unitLabel = if (useCelsius) "°C" else "°F"

    // Display temp in user's preferred unit; always save as Celsius
    val initDisplayTemp = initial?.targetTemp?.let { c ->
        if (useCelsius) c else c * 9f / 5f + 32f
    }

    var name    by remember { mutableStateOf(initial?.name ?: "") }
    var temp    by remember { mutableStateOf(initDisplayTemp?.let { "%.1f".format(it) } ?: "") }
    var hours   by remember { mutableStateOf(((initial?.timerMinutes ?: 0) / 60).toString()) }
    var minutes by remember { mutableStateOf(((initial?.timerMinutes ?: 0) % 60).toString()) }
    var notes   by remember { mutableStateOf(initial?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DC_Bg,
        title = { Text(if (initial == null) "New Preset" else "Edit Preset", color = DC_TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = temp, onValueChange = { temp = it },
                    label = { Text("Target temperature ($unitLabel)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hours, onValueChange = { hours = it },
                        label = { Text("Hours") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it },
                        label = { Text("Min") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val displayTemp = temp.toFloatOrNull() ?: return@TextButton
                val tempC = if (useCelsius) displayTemp else (displayTemp - 32f) * 5f / 9f
                val h = hours.toIntOrNull() ?: 0
                val m = minutes.toIntOrNull() ?: 0
                onSave(
                    (initial ?: Preset(name = "", targetTemp = 0f, timerMinutes = 0)).copy(
                        name         = name.trim().ifBlank { return@TextButton },
                        targetTemp   = tempC,
                        timerMinutes = h * 60 + m,
                        notes        = notes.trim()
                    )
                )
            }) { Text("Save", color = DC_Orange, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
