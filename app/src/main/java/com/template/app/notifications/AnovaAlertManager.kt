package com.template.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.template.app.AppConfig
import com.template.app.MainActivity
import com.template.app.R
import com.template.app.anova.AnovaSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnovaAlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val anovaSettings: AnovaSettings
) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cached sound URI from user settings. Updated via a background collector. */
    private var cachedSoundUri: String? = null
    private var activeRingtone: Ringtone? = null

    init {
        scope.launch {
            anovaSettings.alertSoundUri.collect { uri -> cachedSoundUri = uri }
        }
    }

    companion object {
        const val NOTIFICATION_ID_COOK_STATUS = 1000
        const val NOTIFICATION_ID_TEMP_MIN    = 1001
        const val NOTIFICATION_ID_TEMP_MAX    = 1002
        const val NOTIFICATION_ID_COOK_DONE   = 1003
        const val NOTIFICATION_ID_OFFLINE     = 1004
        const val NOTIFICATION_ID_TEMP_TARGET = 1005
        const val NOTIFICATION_ID_SERVICE     = 1006
        const val NOTIFICATION_ID_SCHED_FAIL  = 1007
        const val NOTIFICATION_ID_COOK_START  = 1008

        const val ACTION_STOP_COOK  = "com.template.app.ACTION_STOP_COOK"
        const val ACTION_ADD_HOUR   = "com.template.app.ACTION_ADD_HOUR"

        /**
         * AudioAttributes used when playing alert sounds directly via RingtoneManager.
         * USAGE_ALARM bypasses silent mode and DND on all OEMs. We play sound outside
         * the notification channel to avoid OEM overrides of channel sounds.
         */
        private val ALARM_AUDIO = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    /**
     * Plays the user's chosen alert sound via RingtoneManager on STREAM_ALARM,
     * which bypasses silent mode, DND, and mute on all Android OEMs.
     */
    private fun playAlertSound() {
        activeRingtone?.stop()
        val uri = cachedSoundUri?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
        ringtone.audioAttributes = ALARM_AUDIO
        activeRingtone = ringtone
        ringtone.play()
    }

    private fun tapIntent(id: Int): PendingIntent = PendingIntent.getActivity(
        context, id,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun broadcastIntent(action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id,
        Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ── Cook-active persistent notification ────────────────────────────────────

    fun buildCookNotification(currentTemp: Float?, targetTemp: Float?, timerMinutes: Int?, unitSymbol: String): android.app.Notification {
        val tempStr   = if (currentTemp != null) "%.1f%s".format(currentTemp, unitSymbol) else "– –"
        val targetStr = if (targetTemp != null) " → %.1f%s".format(targetTemp, unitSymbol) else ""
        val timerStr  = if (timerMinutes != null) {
            val h = timerMinutes / 60; val m = timerMinutes % 60
            if (h > 0) "%dh %02dm remaining".format(h, m) else "%dm remaining".format(m)
        } else ""
        return NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_COOK_STATUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Anova running  $tempStr$targetStr")
            .setContentText(timerStr.ifBlank { "Cook in progress" })
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent(NOTIFICATION_ID_COOK_STATUS))
            .addAction(0, "Stop", broadcastIntent(ACTION_STOP_COOK, NOTIFICATION_ID_COOK_STATUS))
            .addAction(0, "+1 hour", broadcastIntent(ACTION_ADD_HOUR, NOTIFICATION_ID_COOK_STATUS + 1))
            .build()
    }

    fun updateCookNotification(currentTemp: Float?, targetTemp: Float?, timerMinutes: Int?, unitSymbol: String) {
        nm.notify(NOTIFICATION_ID_COOK_STATUS, buildCookNotification(currentTemp, targetTemp, timerMinutes, unitSymbol))
    }

    fun cancelCookNotification() = nm.cancel(NOTIFICATION_ID_COOK_STATUS)

    // ── Threshold alerts (alarm channel — bypasses DND + hardware silent) ──────

    fun postTempAlert(message: String, notificationId: Int) {
        val n = NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_ALARM)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Anova Temperature Alert")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(tapIntent(notificationId))
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId, n)
        playAlertSound()
    }

    // ── Event alerts (cook finished, offline, started — also bypasses DND) ────

    fun postEventAlert(title: String, message: String, notificationId: Int) {
        val n = NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(tapIntent(notificationId))
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId, n)
        playAlertSound()
    }

    fun cancelTempAlerts() {
        nm.cancel(NOTIFICATION_ID_TEMP_MIN)
        nm.cancel(NOTIFICATION_ID_TEMP_MAX)
    }

    // ── Channel management ────────────────────────────────────────────────────

    /**
     * Deletes and recreates both alert channels with the given vibration setting.
     * Sound is no longer stored in the channel — it's played directly via RingtoneManager
     * (see [playAlertSound]) so OEM channel-sound overrides cannot interfere.
     */
    fun recreateAlertChannels(soundUri: String?, vibrate: Boolean) {
        nm.deleteNotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALARM)
        nm.deleteNotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALERTS)
        createAlarmChannel(vibrate)
        createAlertsChannel(vibrate)
    }

    fun createAlarmChannel(vibrate: Boolean) {
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALARM, "Temperature Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical temperature threshold alerts — sounds even in Silent, DND, and Mute modes"
                setBypassDnd(true)
                setSound(null, null)  // Sound played separately via RingtoneManager on STREAM_ALARM
                enableVibration(vibrate)
            }
        )
    }

    fun createAlertsChannel(vibrate: Boolean) {
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALERTS, "Anova Events", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cook finished, device offline, cook started — sounds even in Silent, DND, and Mute modes"
                setBypassDnd(true)
                setSound(null, null)  // Sound played separately via RingtoneManager on STREAM_ALARM
                enableVibration(vibrate)
            }
        )
    }

    // ── Foreground service notification ───────────────────────────────────────

    fun buildServiceNotification() = NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_SERVICE)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Anova Monitor")
        .setContentText("Monitoring your device in the background")
        .setSilent(true)
        .setOngoing(true)
        .setContentIntent(tapIntent(NOTIFICATION_ID_SERVICE))
        .build()
}
