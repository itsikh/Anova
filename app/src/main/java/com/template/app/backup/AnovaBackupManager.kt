package com.template.app.backup

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.template.app.AppConfig
import com.template.app.history.TemperatureReading
import com.template.app.history.TemperatureReadingDao
import com.template.app.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaBackup"

/**
 * Backs up and restores the Anova temperature history database.
 *
 * ## Backup contents
 * A single JSON array of all [TemperatureReading] rows is written to the ZIP.
 * The backup integrates with Android's Storage Access Framework so it can be saved
 * to local storage, Google Drive, Dropbox, etc. — no extra SDK needed.
 *
 * ## Usage
 * - Export: Settings → Backup → "Export" → choose destination in the SAF picker
 * - Restore: Settings → Backup → "Restore" → choose the previously exported ZIP
 */
@Singleton
class AnovaBackupManager @Inject constructor(
    @ApplicationContext context: Context,
    private val readingDao: TemperatureReadingDao
) : BaseBackupManager(context, AppConfig.APP_NAME) {

    override suspend fun collectBackupData(): BackupData = withContext(Dispatchers.IO) {
        // Fetch all readings for the last 30 days (what the DB already contains)
        val readings = readingDao.getReadingsInRange(0L, Long.MAX_VALUE)
        AppLogger.i(TAG, "Backing up ${readings.size} temperature readings")

        val array = JsonArray()
        readings.forEach { r ->
            JsonObject().apply {
                addProperty("id", r.id)
                addProperty("timestamp", r.timestamp)
                addProperty("temperature", r.temperature)
                addProperty("unit", r.unit)
                addProperty("status", r.status)
                r.timerMinutes?.let { addProperty("timerMinutes", it) }
            }.also { array.add(it) }
        }

        BackupData(data = array)
    }

    override suspend fun restoreBackupData(data: BackupContent, extractDir: File) =
        withContext(Dispatchers.IO) {
            val array = data.data.asJsonArray
            var count = 0
            array.forEach { element ->
                runCatching {
                    val obj = element.asJsonObject
                    readingDao.insert(
                        TemperatureReading(
                            timestamp   = obj.get("timestamp").asLong,
                            temperature = obj.get("temperature").asFloat,
                            unit        = obj.get("unit").asString,
                            status      = obj.get("status").asString,
                            timerMinutes = obj.get("timerMinutes")?.asInt
                        )
                    )
                    count++
                }.onFailure { AppLogger.w(TAG, "Skipped malformed row: ${it.message}") }
            }
            AppLogger.i(TAG, "Restored $count temperature readings")
        }
}
