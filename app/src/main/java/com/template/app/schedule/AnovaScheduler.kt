package com.template.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.template.app.anova.AnovaRepository
import com.template.app.anova.AnovaSettings
import com.template.app.logging.AppLogger
import com.template.app.notifications.AnovaAlertManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaScheduler"
private const val ACTION_SCHEDULE_FIRE = "com.template.app.ACTION_SCHEDULE_FIRE"
private const val EXTRA_SCHEDULE_ID    = "schedule_id"

@Singleton
class AnovaScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleDao: ScheduleDao,
    private val repository: AnovaRepository,
    private val settings: AnovaSettings,
    private val alertManager: AnovaAlertManager
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(entry: ScheduleEntry) {
        CoroutineScope(Dispatchers.IO).launch {
            val id = scheduleDao.insert(entry)
            setAlarm(id, entry.scheduledAtMs)
            AppLogger.i(TAG, "Scheduled ${entry.type} at ${java.util.Date(entry.scheduledAtMs)} id=$id")
        }
    }

    fun cancel(entry: ScheduleEntry) {
        CoroutineScope(Dispatchers.IO).launch {
            cancelAlarm(entry.id)
            scheduleDao.delete(entry)
        }
    }

    suspend fun fireDue() {
        val due = scheduleDao.getDueSchedules(System.currentTimeMillis())
        due.forEach { entry -> fire(entry) }
    }

    private suspend fun fire(entry: ScheduleEntry) {
        AppLogger.i(TAG, "Firing schedule id=${entry.id} type=${entry.type}")
        val maxRetries = settings.schedulerMaxRetries.first()
        val retryMs    = settings.schedulerRetryMs.first()
        var retries = 0
        var ok = false

        while (retries <= maxRetries && !ok) {
            ok = when (entry.type) {
                "START" -> {
                    if (entry.targetTemp != null) repository.updateCook(targetTemp = entry.targetTemp)
                    if (entry.timerSeconds != null) repository.updateCook(timerSeconds = entry.timerSeconds)
                    repository.startCook()
                }
                "STOP"  -> repository.stopCook()
                else    -> false
            }
            if (!ok) {
                retries++
                if (retries <= maxRetries) {
                    AppLogger.w(TAG, "Schedule retry $retries/$maxRetries in ${retryMs}ms")
                    kotlinx.coroutines.delay(retryMs)
                }
            }
        }

        if (!ok) {
            AppLogger.e(TAG, "Schedule id=${entry.id} failed after $maxRetries retries")
            alertManager.postEventAlert(
                "Schedule failed",
                "Could not ${entry.type.lowercase()} the device — it may be unreachable.",
                AnovaAlertManager.NOTIFICATION_ID_SCHED_FAIL
            )
        }
        scheduleDao.markFired(entry.id)
    }

    private fun setAlarm(id: Long, atMs: Long) {
        val pi = pendingIntent(id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "Exact alarm permission denied, using inexact: ${e.message}")
            alarmManager.set(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun cancelAlarm(id: Long) = alarmManager.cancel(pendingIntent(id))

    private fun pendingIntent(id: Long): PendingIntent {
        val intent = Intent(ACTION_SCHEDULE_FIRE).setPackage(context.packageName)
            .putExtra(EXTRA_SCHEDULE_ID, id)
        return PendingIntent.getBroadcast(context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

@AndroidEntryPoint
class ScheduleAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: AnovaScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SCHEDULE_FIRE) {
            CoroutineScope(Dispatchers.IO).launch { scheduler.fireDue() }
        }
    }
}
