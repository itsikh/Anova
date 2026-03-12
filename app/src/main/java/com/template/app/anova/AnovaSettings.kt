package com.template.app.anova

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.anovaDataStore by preferencesDataStore(name = "anova_settings")

@Singleton
class AnovaSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.anovaDataStore

    companion object {
        val KEY_CONNECTION_MODE     = stringPreferencesKey("connection_mode")
        val KEY_LOCAL_WIFI_IP       = stringPreferencesKey("local_wifi_ip")
        val KEY_CLOUD_EMAIL         = stringPreferencesKey("cloud_email")
        val KEY_LOCAL_POLL_MS       = longPreferencesKey("local_poll_ms")
        val KEY_REMOTE_POLL_MS      = longPreferencesKey("remote_poll_ms")

        // History
        val KEY_HISTORY_SAMPLE_MS   = longPreferencesKey("history_sample_ms")
        val KEY_HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")

        // Alerts
        val KEY_ALERT_COOK_FINISHED     = booleanPreferencesKey("alert_cook_finished")
        val KEY_ALERT_TEMP_TARGET       = booleanPreferencesKey("alert_temp_target")
        val KEY_ALERT_DEVICE_OFFLINE    = booleanPreferencesKey("alert_device_offline")
        val KEY_ALERT_SCHEDULE_FAILED   = booleanPreferencesKey("alert_schedule_failed")
        val KEY_ALERT_COOK_STARTED      = booleanPreferencesKey("alert_cook_started")

        // Display
        val KEY_TEMP_UNIT_CELSIUS   = booleanPreferencesKey("temp_unit_celsius")
        val KEY_APP_THEME            = stringPreferencesKey("app_theme") // DARK / LIGHT / SYSTEM

        // Boot reconnect
        val KEY_AUTO_RECONNECT_ON_BOOT  = booleanPreferencesKey("auto_reconnect_on_boot")

        // Scheduler
        val KEY_SCHEDULER_RETRY_MS      = longPreferencesKey("scheduler_retry_ms")
        val KEY_SCHEDULER_MAX_RETRIES   = intPreferencesKey("scheduler_max_retries")

        // Alert sound & vibration
        val KEY_ALERT_SOUND_URI = stringPreferencesKey("alert_sound_uri")
        val KEY_ALERT_VIBRATE   = booleanPreferencesKey("alert_vibrate")

        // Defaults
        const val DEFAULT_LOCAL_POLL_MS   = 2_000L
        const val DEFAULT_REMOTE_POLL_MS  = 30_000L
        const val DEFAULT_HISTORY_SAMPLE_MS = 3_600_000L  // 1 hour
        const val DEFAULT_HISTORY_RETENTION_DAYS = 30
        const val DEFAULT_SCHEDULER_RETRY_MS = 60_000L
        const val DEFAULT_SCHEDULER_MAX_RETRIES = 5
    }

    val connectionMode: Flow<ConnectionMode> = store.data.map { prefs ->
        prefs[KEY_CONNECTION_MODE]
            ?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
            ?: ConnectionMode.AUTO
    }
    val localWifiIp:   Flow<String>  = store.data.map { it[KEY_LOCAL_WIFI_IP]  ?: "" }
    val cloudEmail:    Flow<String>  = store.data.map { it[KEY_CLOUD_EMAIL]    ?: "" }
    val localPollMs:   Flow<Long>    = store.data.map { it[KEY_LOCAL_POLL_MS]  ?: DEFAULT_LOCAL_POLL_MS }
    val remotePollMs:  Flow<Long>    = store.data.map { it[KEY_REMOTE_POLL_MS] ?: DEFAULT_REMOTE_POLL_MS }

    val historySampleMs: Flow<Long>  = store.data.map { it[KEY_HISTORY_SAMPLE_MS] ?: DEFAULT_HISTORY_SAMPLE_MS }
    val historyRetentionDays: Flow<Int> = store.data.map { it[KEY_HISTORY_RETENTION_DAYS] ?: DEFAULT_HISTORY_RETENTION_DAYS }

    val alertCookFinished:   Flow<Boolean> = store.data.map { it[KEY_ALERT_COOK_FINISHED]  ?: true }
    val alertTempTarget:     Flow<Boolean> = store.data.map { it[KEY_ALERT_TEMP_TARGET]    ?: true }
    val alertDeviceOffline:  Flow<Boolean> = store.data.map { it[KEY_ALERT_DEVICE_OFFLINE] ?: true }
    val alertScheduleFailed: Flow<Boolean> = store.data.map { it[KEY_ALERT_SCHEDULE_FAILED]?: true }
    val alertCookStarted:    Flow<Boolean> = store.data.map { it[KEY_ALERT_COOK_STARTED]   ?: false }

    val tempUnitCelsius: Flow<Boolean> = store.data.map { it[KEY_TEMP_UNIT_CELSIUS] ?: true }
    val appTheme:        Flow<String>  = store.data.map { it[KEY_APP_THEME]          ?: "DARK" }

    val autoReconnectOnBoot: Flow<Boolean> = store.data.map { it[KEY_AUTO_RECONNECT_ON_BOOT] ?: false }

    val schedulerRetryMs:    Flow<Long>  = store.data.map { it[KEY_SCHEDULER_RETRY_MS]    ?: DEFAULT_SCHEDULER_RETRY_MS }
    val schedulerMaxRetries: Flow<Int>   = store.data.map { it[KEY_SCHEDULER_MAX_RETRIES] ?: DEFAULT_SCHEDULER_MAX_RETRIES }

    /** `null` = use the default alarm sound */
    val alertSoundUri: Flow<String?> = store.data.map { it[KEY_ALERT_SOUND_URI] }
    val alertVibrate:  Flow<Boolean> = store.data.map { it[KEY_ALERT_VIBRATE] ?: true }

    suspend fun setConnectionMode(mode: ConnectionMode)    = store.edit { it[KEY_CONNECTION_MODE] = mode.name }
    suspend fun setLocalWifiIp(ip: String)                 = store.edit { it[KEY_LOCAL_WIFI_IP]   = ip }
    suspend fun setCloudEmail(email: String)               = store.edit { it[KEY_CLOUD_EMAIL]     = email }
    suspend fun setLocalPollMs(ms: Long)                   = store.edit { it[KEY_LOCAL_POLL_MS]   = ms }
    suspend fun setRemotePollMs(ms: Long)                  = store.edit { it[KEY_REMOTE_POLL_MS]  = ms }

    suspend fun setHistorySampleMs(ms: Long)               = store.edit { it[KEY_HISTORY_SAMPLE_MS] = ms }
    suspend fun setHistoryRetentionDays(days: Int)         = store.edit { it[KEY_HISTORY_RETENTION_DAYS] = days }

    suspend fun setAlertCookFinished(on: Boolean)          = store.edit { it[KEY_ALERT_COOK_FINISHED]   = on }
    suspend fun setAlertTempTarget(on: Boolean)            = store.edit { it[KEY_ALERT_TEMP_TARGET]     = on }
    suspend fun setAlertDeviceOffline(on: Boolean)         = store.edit { it[KEY_ALERT_DEVICE_OFFLINE]  = on }
    suspend fun setAlertScheduleFailed(on: Boolean)        = store.edit { it[KEY_ALERT_SCHEDULE_FAILED] = on }
    suspend fun setAlertCookStarted(on: Boolean)           = store.edit { it[KEY_ALERT_COOK_STARTED]    = on }

    suspend fun setTempUnitCelsius(celsius: Boolean)       = store.edit { it[KEY_TEMP_UNIT_CELSIUS] = celsius }
    suspend fun setAppTheme(theme: String)                 = store.edit { it[KEY_APP_THEME]          = theme }

    suspend fun setAutoReconnectOnBoot(on: Boolean)        = store.edit { it[KEY_AUTO_RECONNECT_ON_BOOT] = on }

    suspend fun setSchedulerRetryMs(ms: Long)              = store.edit { it[KEY_SCHEDULER_RETRY_MS]    = ms }
    suspend fun setSchedulerMaxRetries(n: Int)             = store.edit { it[KEY_SCHEDULER_MAX_RETRIES] = n }

    suspend fun setAlertSoundUri(uri: String?) = store.edit {
        if (uri == null) it.remove(KEY_ALERT_SOUND_URI) else it[KEY_ALERT_SOUND_URI] = uri
    }
    suspend fun setAlertVibrate(on: Boolean) = store.edit { it[KEY_ALERT_VIBRATE] = on }
}
