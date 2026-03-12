package com.template.app.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.template.app.anova.ThresholdSettings

@Composable
fun ThresholdDialog(
    current: ThresholdSettings,
    unitSymbol: String,
    onConfirm: (ThresholdSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var minEnabled by remember { mutableStateOf(current.minTempEnabled) }
    var minTemp    by remember { mutableStateOf("%.1f".format(current.minTemp)) }
    var minManuallyEdited by remember { mutableStateOf(false) }
    var maxEnabled by remember { mutableStateOf(current.maxTempEnabled) }
    var maxTemp    by remember { mutableStateOf(current.maxTemp.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Temperature Alerts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Get notified when the temperature crosses these thresholds.")

                Spacer(Modifier.height(4.dp))

                // Min threshold
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Minimum alert")
                    Switch(checked = minEnabled, onCheckedChange = { minEnabled = it })
                }
                if (minEnabled) {
                    OutlinedTextField(
                        value = minTemp,
                        onValueChange = { minTemp = it; minManuallyEdited = true },
                        label = {
                            Text(if (current.isAutoMin && !minManuallyEdited)
                                "Min temp ($unitSymbol) — auto (10% below target)"
                            else "Min temp ($unitSymbol)")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Max threshold
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Maximum alert")
                    Switch(checked = maxEnabled, onCheckedChange = { maxEnabled = it })
                }
                if (maxEnabled) {
                    OutlinedTextField(
                        value = maxTemp,
                        onValueChange = { maxTemp = it },
                        label = { Text("Max temp ($unitSymbol)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    ThresholdSettings(
                        minTempEnabled = minEnabled,
                        minTemp = minTemp.toFloatOrNull() ?: current.minTemp,
                        isAutoMin = current.isAutoMin && !minManuallyEdited,
                        maxTempEnabled = maxEnabled,
                        maxTemp = maxTemp.toFloatOrNull() ?: current.maxTemp
                    )
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
