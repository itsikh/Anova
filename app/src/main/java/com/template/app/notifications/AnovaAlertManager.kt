package com.template.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.template.app.AppConfig
import com.template.app.MainActivity
import com.template.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnovaAlertManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
         * AudioAttributes for alert channels. USAGE_NOTIFICATION_RINGTONE lets the channel
         * use any custom URI the user picks (USAGE_ALARM causes Samsung and some other OEMs
         * to override the channel sound with the system alarm regardless of what is set).
         * DND bypass is handled separately via NotificationChannel.setBypassDnd(true).
         */
        private val ALARM_AUDIO = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
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
    }

    fun cancelTempAlerts() {
        nm.cancel(NOTIFICATION_ID_TEMP_MIN)
        nm.cancel(NOTIFICATION_ID_TEMP_MAX)
    }

    // ── Channel management ────────────────────────────────────────────────────

    /**
     * Deletes and recreates both alert channels with the given sound URI and vibration setting.
     * Called when the user changes alert sound or vibration preferences.
     *
     * [soundUri] — custom ringtone URI string, or `null` to use the default alarm sound.
     * [vibrate]  — whether vibration is enabled for alert notifications.
     *
     * Note: On Android 8+ notification channels cache their sound. Deleting and recreating
     * the channel is the only way to programmatically change the sound.
     */
    fun recreateAlertChannels(soundUri: String?, vibrate: Boolean) {
        nm.deleteNotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALARM)
        nm.deleteNotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALERTS)
        createAlarmChannel(soundUri, vibrate)
        createAlertsChannel(soundUri, vibrate)
    }

    fun createAlarmChannel(soundUri: String?, vibrate: Boolean) {
        val sound = soundUri?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALARM, "Temperature Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical temperature threshold alerts — sounds even in Silent, DND, and Mute modes"
                setBypassDnd(true)
                setSound(sound, ALARM_AUDIO)
                enableVibration(vibrate)
            }
        )
    }

    fun createAlertsChannel(soundUri: String?, vibrate: Boolean) {
        val sound = soundUri?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALERTS, "Anova Events", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cook finished, device offline, cook started — sounds even in Silent, DND, and Mute modes"
                setBypassDnd(true)
                setSound(sound, ALARM_AUDIO)
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
