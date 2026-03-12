package com.template.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.template.app.anova.AnovaSettings
import com.template.app.logging.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class TemplateApplication : Application() {

    @Inject lateinit var alertManager: com.template.app.notifications.AnovaAlertManager
    @Inject lateinit var anovaSettings: AnovaSettings

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

        // Always delete + recreate alert channels on startup so the sound and vibration
        // settings always match what's saved in DataStore. Android 8+ ignores setSound()
        // on an existing channel, so we must delete first to force the update.
        val (savedSound, savedVibrate) = runBlocking {
            anovaSettings.alertSoundUri.first() to anovaSettings.alertVibrate.first()
        }
        alertManager.recreateAlertChannels(soundUri = savedSound, vibrate = savedVibrate)
    }
}
