package com.template.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.provider.Settings
import com.template.app.logging.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TemplateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(this, Thread.getDefaultUncaughtExceptionHandler())
        )
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_BACKUP, "Backup", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications for completed backups"
            }
        )

        // Alarm channel — bypasses DND for critical temperature alerts
        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALARM, "Temperature Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical temperature threshold alerts — sounds even in silent mode"
                setBypassDnd(true)
                setSound(
                    Settings.System.DEFAULT_ALARM_ALERT_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_COOK_STATUS, "Cook Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while a cook is active — shows temp, timer, and controls"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ALERTS, "Anova Events", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cook finished, device offline, and other event notifications"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_SERVICE, "Background Monitor", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required for background device monitoring"
            }
        )
    }
}
