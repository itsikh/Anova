package com.template.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.template.app.anova.AnovaRepository
import com.template.app.anova.AnovaStatus
import com.template.app.anova.ConnectionState
import com.template.app.notifications.AnovaAlertManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app process alive in the background while connected.
 *
 * Notification policy:
 * - RUNNING + connected → foreground with the live cook status notification
 * - Connected but not running → foreground with a minimal "monitoring" banner
 * - Disconnected → service stops itself
 *
 * Staying foreground whenever connected (not just when cooking) prevents Android battery
 * optimization from killing the process mid-cook, especially on aggressive OEMs.
 */
@AndroidEntryPoint
class AnovaMonitorService : Service() {

    companion object {
        const val EXTRA_AUTO_CONNECT = "extra_auto_connect"
    }

    @Inject lateinit var alertManager: AnovaAlertManager
    @Inject lateinit var repository: AnovaRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: Job? = null
    private var isForeground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground within 5 s of startForegroundService.
        val state = repository.deviceState.value
        if (state.status == AnovaStatus.RUNNING &&
            state.connectionState == ConnectionState.CONNECTED) {
            startForeground(
                AnovaAlertManager.NOTIFICATION_ID_COOK_STATUS,
                alertManager.buildCookNotification(state.currentTemp, state.targetTemp, state.timerMinutes, state.unit.symbol)
            )
        } else {
            // Connected but not cooking — show a minimal monitoring notification.
            // We stay foreground to prevent battery optimization from killing us mid-cook.
            startForeground(AnovaAlertManager.NOTIFICATION_ID_SERVICE, alertManager.buildServiceNotification())
        }
        isForeground = true

        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true) {
            repository.connect()
        }

        observeState()
        return START_STICKY
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            repository.deviceState.collect { state ->
                val cooking = state.status == AnovaStatus.RUNNING &&
                              state.connectionState == ConnectionState.CONNECTED

                when {
                    cooking -> {
                        // Show (or update) the live cook notification.
                        startForeground(
                            AnovaAlertManager.NOTIFICATION_ID_COOK_STATUS,
                            alertManager.buildCookNotification(
                                state.currentTemp, state.targetTemp,
                                state.timerMinutes, state.unit.symbol
                            )
                        )
                        isForeground = true
                    }
                    state.connectionState == ConnectionState.DISCONNECTED -> {
                        // Device went offline — nothing left to monitor.
                        stopSelf()
                    }
                    !isForeground -> {
                        // Connected but not cooking — stay foreground with minimal banner
                        // to prevent the OS from killing us before a cook starts.
                        startForeground(AnovaAlertManager.NOTIFICATION_ID_SERVICE, alertManager.buildServiceNotification())
                        isForeground = true
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stateJob?.cancel()
        serviceScope.cancel()
        if (isForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
