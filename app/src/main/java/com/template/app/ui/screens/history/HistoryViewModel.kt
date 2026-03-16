package com.template.app.ui.screens.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.template.app.history.TemperatureReading
import com.template.app.history.TemperatureReadingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val readingDao: TemperatureReadingDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val readings: StateFlow<List<TemperatureReading>> = readingDao
        .getRecentReadings(500)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun exportCsv() {
        viewModelScope.launch {
            try {
                val all = readingDao.getAllReadings()
                if (all.isEmpty()) return@launch

                val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val sb = StringBuilder("timestamp,date,temperature,unit,target,status,timer_minutes\n")
                all.forEach { r ->
                    sb.append("${r.timestamp},${dateFmt.format(Date(r.timestamp))},${r.temperature},${r.unit},,${r.status},${r.timerMinutes ?: ""}\n")
                }

                val file = File(context.cacheDir, "anova_history_${System.currentTimeMillis()}.csv")
                file.writeText(sb.toString())

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export History").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                // silently fail — could add error state if needed
            }
        }
    }
}
