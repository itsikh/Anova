package com.template.app

object AppConfig {
    const val GITHUB_ISSUES_REPO_OWNER   = "itsikh"
    const val GITHUB_ISSUES_REPO_NAME    = "Anova"
    const val GITHUB_RELEASES_REPO_OWNER = "itsikh"
    const val GITHUB_RELEASES_REPO_NAME  = "Anova"
    const val APP_NAME                   = "Anova"
    const val SECURE_PREFS_FILENAME      = "anova_secure_keys"

    // Notification channels
    const val NOTIFICATION_CHANNEL_BACKUP       = "channel_backup"
    /** Temperature threshold alerts — alarm priority, bypasses DND */
    const val NOTIFICATION_CHANNEL_ALARM        = "channel_anova_alarm"
    /** Cook-active persistent notification with Stop / +1h actions */
    const val NOTIFICATION_CHANNEL_COOK_STATUS  = "channel_anova_cook"
    /** General event alerts (cook finished, offline, etc.) */
    const val NOTIFICATION_CHANNEL_ALERTS       = "channel_anova_alerts"
    /** Foreground service notification */
    const val NOTIFICATION_CHANNEL_SERVICE      = "channel_anova_service"
}
