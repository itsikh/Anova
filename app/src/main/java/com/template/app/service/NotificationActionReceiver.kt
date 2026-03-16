package com.template.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.template.app.anova.AnovaRepository
import com.template.app.notifications.AnovaAlertManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AnovaRepository

    override fun onReceive(context: Context, intent: Intent) {
        val scope = CoroutineScope(Dispatchers.IO)
        when (intent.action) {
            AnovaAlertManager.ACTION_STOP_COOK -> scope.launch { repository.stopCook() }
            AnovaAlertManager.ACTION_ADD_HOUR  -> scope.launch { repository.addHour()  }
        }
    }
}
