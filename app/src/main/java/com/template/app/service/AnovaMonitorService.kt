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
 * - RUNNING + connected → foreground with the live cook status notification (no separate "monitoring" banner)
 * - Connected but not running → demoted to background (no persistent notification)
 * - Disconnected → service stops itself
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
        // If the device is already cooking we use the cook notification immediately;
        // otherwise we start foreground with a silent placeholder and remove it at once.
        val state = repository.deviceState.value
        if (state.status == AnovaStatus.RUNNING &&
            state.connectionState == ConnectionState.CONNECTED) {
            startForeground(
                AnovaAlertManager.NOTIFICATION_ID_COOK_STATUS,
                alertManager.buildCookNotification(state.currentTemp, state.targetTemp, state.timerMinutes, state.unit.symbol)
            )
            isForeground = true
        } else {
            // Satisfy the 5-second requirement, then immediately remove the notification.
            startForeground(AnovaAlertManager.NOTIFICATION_ID_SERVICE, alertManager.buildServiceNotification())
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }

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
                        // Promote (or keep) foreground with the live cook notification.
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
                    isForeground -> {
                        // Connected but not cooking — demote, no notification needed.
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        isForeground = false
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
