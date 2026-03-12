package com.template.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.template.app.logging.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TemplateApplication : Application() {

    // Injected after Hilt initialises; used to recreate channels with user's saved settings on boot.
    @Inject lateinit var alertManager: com.template.app.notifications.AnovaAlertManager

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(this, Thread.getDefaultUncaughtExceptionHandler())
        )
        // Create all channels with safe defaults first (Hilt DI runs as part of onCreate injection).
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_BACKUP, "Backup", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications for completed backups"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_COOK_STATUS, "Cook Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while a cook is active — shows temp, timer, and controls"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(AppConfig.NOTIFICATION_CHANNEL_SERVICE, "Background Monitor", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required for background device monitoring"
            }
        )

        // Alert channels — delegated to AnovaAlertManager so it owns the channel spec and can
        // recreate them later when the user changes alert sound / vibration settings.
        // Default: alarm sound + vibration enabled, bypasses DND and hardware silent mode.
        alertManager.createAlarmChannel(soundUri = null, vibrate = true)
        alertManager.createAlertsChannel(soundUri = null, vibrate = true)
    }
}
