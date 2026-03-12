package com.template.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.template.app.anova.AnovaSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: AnovaSettings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (settings.autoReconnectOnBoot.first()) {
                    val serviceIntent = Intent(context, AnovaMonitorService::class.java)
                        .putExtra(AnovaMonitorService.EXTRA_AUTO_CONNECT, true)
                    context.startForegroundService(serviceIntent)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
