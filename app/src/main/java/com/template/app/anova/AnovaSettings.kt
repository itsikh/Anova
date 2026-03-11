package com.template.app.anova

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.anovaDataStore by preferencesDataStore(name = "anova_settings")

@Singleton
class AnovaSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.anovaDataStore

    companion object {
        val KEY_CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val KEY_LOCAL_WIFI_IP   = stringPreferencesKey("local_wifi_ip")
        val KEY_CLOUD_EMAIL     = stringPreferencesKey("cloud_email")
        val KEY_LOCAL_POLL_MS   = longPreferencesKey("local_poll_ms")
        val KEY_REMOTE_POLL_MS  = longPreferencesKey("remote_poll_ms")

        const val DEFAULT_LOCAL_POLL_MS  = 2_000L
        const val DEFAULT_REMOTE_POLL_MS = 60_000L
    }

    val connectionMode: Flow<ConnectionMode> = store.data.map { prefs ->
        prefs[KEY_CONNECTION_MODE]
            ?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
            ?: ConnectionMode.AUTO
    }

    val localWifiIp:  Flow<String> = store.data.map { it[KEY_LOCAL_WIFI_IP]  ?: "" }
    val cloudEmail:   Flow<String> = store.data.map { it[KEY_CLOUD_EMAIL]    ?: "" }
    val localPollMs:  Flow<Long>   = store.data.map { it[KEY_LOCAL_POLL_MS]  ?: DEFAULT_LOCAL_POLL_MS }
    val remotePollMs: Flow<Long>   = store.data.map { it[KEY_REMOTE_POLL_MS] ?: DEFAULT_REMOTE_POLL_MS }

    suspend fun setConnectionMode(mode: ConnectionMode) = store.edit { it[KEY_CONNECTION_MODE] = mode.name }
    suspend fun setLocalWifiIp(ip: String)    = store.edit { it[KEY_LOCAL_WIFI_IP]  = ip }
    suspend fun setCloudEmail(email: String)  = store.edit { it[KEY_CLOUD_EMAIL]    = email }
    suspend fun setLocalPollMs(ms: Long)      = store.edit { it[KEY_LOCAL_POLL_MS]  = ms }
    suspend fun setRemotePollMs(ms: Long)     = store.edit { it[KEY_REMOTE_POLL_MS] = ms }
}
