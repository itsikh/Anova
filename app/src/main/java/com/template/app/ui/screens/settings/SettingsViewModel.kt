package com.template.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.template.app.anova.AnovaSettings
import com.template.app.anova.cloud.AnovaFirebaseAuth
import com.template.app.backup.AnovaBackupManager
import com.template.app.bugreport.GitHubIssuesClient
import com.template.app.logging.AppLogger
import com.template.app.logging.DebugSettings
import com.template.app.logging.LogLevel
import com.template.app.security.SecureKeyManager
import com.template.app.update.AppUpdateManager
import com.template.app.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the generic settings screen ([SettingsScreen]).
 *
 * Manages state for:
 * - **Admin mode** — toggled by the 7-tap easter egg in [ui.components.SettingsScaffold]
 * - **Log level** — DEBUG vs WARN, persisted via [DebugSettings]
 * - **Bug button visibility** — floating bug-report FAB shown/hidden
 * - **Auto-update** — whether [AppUpdateManager] checks on launch
 * - **Auto-backup** — whether the app backs up automatically after events
 * - **GitHub token** — PAT for bug reports and update checks
 * - **App update state machine** — Idle → Checking → Available/UpToDate → Downloading → Install
 * - **Backup export state machine** — Idle → Exporting → Done/Error
 * - **Backup restore state machine** — Idle → Restoring → Done/Error
 * - **Anova device settings** — temp unit, theme, history, alerts
 *
 * ## Backup integration
 * The backup export ([exportBackupToUri]) and restore ([restoreFromBackup]) methods contain
 * placeholder implementations that log a warning. To wire in your app's actual data:
 *
 * 1. Inject your concrete [backup.BaseBackupManager] subclass into this ViewModel.
 * 2. In [exportBackupToUri], call `backupManager.exportToUri(uri)`.
 * 3. In [restoreFromBackup], call `backupManager.importFromUri(uri)`.
 *
 * The SAF URI passed to these methods already handles both local storage and Google Drive —
 * no special Google Drive SDK is needed. Android routes the I/O through the correct provider.
 *
 * ## App update installation
 * [downloadAndInstall] checks `canRequestPackageInstalls()` before downloading. If the
 * permission has not been granted, it opens the system settings page for the user to enable it.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val debugSettings: DebugSettings,
    private val secureKeyManager: SecureKeyManager,
    private val updateManager: AppUpdateManager,
    private val backupManager: AnovaBackupManager,
    private val anovaSettings: AnovaSettings,
    private val alertManager: com.template.app.notifications.AnovaAlertManager,
    private val firebaseAuth: AnovaFirebaseAuth,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Debug settings state ──────────────────────────────────────────────────

    val adminMode: StateFlow<Boolean> = debugSettings.adminMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val logLevel: StateFlow<LogLevel> = debugSettings.logLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogLevel.INFO)

    val showBugButton: StateFlow<Boolean> = debugSettings.showBugButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoUpdateEnabled: StateFlow<Boolean> = debugSettings.autoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoBackupEnabled: StateFlow<Boolean> = debugSettings.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── GitHub token ──────────────────────────────────────────────────────────

    /** `true` if a GitHub PAT is currently stored in [SecureKeyManager]. */
    val hasGitHubToken: Boolean
        get() = secureKeyManager.hasKey(GitHubIssuesClient.KEY_GITHUB_TOKEN)

    /** Saves the GitHub PAT to [SecureKeyManager]. Trims whitespace before saving. */
    fun saveGitHubToken(token: String) {
        if (token.isNotBlank()) {
            secureKeyManager.saveKey(GitHubIssuesClient.KEY_GITHUB_TOKEN, token.trim())
            AppLogger.i(TAG, "GitHub token saved")
        }
    }

    /** Removes the GitHub PAT from [SecureKeyManager]. */
    fun clearGitHubToken() {
        secureKeyManager.deleteKey(GitHubIssuesClient.KEY_GITHUB_TOKEN)
        AppLogger.i(TAG, "GitHub token cleared")
    }

    // ── Settings toggles ──────────────────────────────────────────────────────

    fun setAdminMode(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAdminMode(enabled) }
    }

    fun setDetailedLogging(enabled: Boolean) {
        viewModelScope.launch {
            debugSettings.setLogLevel(if (enabled) LogLevel.DEBUG else LogLevel.WARN)
        }
    }

    fun setShowBugButton(show: Boolean) {
        viewModelScope.launch { debugSettings.setShowBugButton(show) }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAutoUpdateEnabled(enabled) }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAutoBackupEnabled(enabled) }
    }

    /** Clears the in-memory [AppLogger] buffer. Does not affect crash log files on disk. */
    fun clearAllLogs() {
        AppLogger.clear()
        AppLogger.i(TAG, "Logs cleared by user")
    }

    // ── App update state ──────────────────────────────────────────────────────

    /**
     * State machine for the update check flow.
     * Displayed by the Auto-Update card in [SettingsScreen].
     */
    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
        object UpToDate : UpdateState()
        object Downloading : UpdateState()
        data class ReadyToInstall(val apkPath: String) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    /** Checks GitHub Releases for a newer version. Updates [updateState]. */
    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            try {
                val update = updateManager.checkForUpdate()
                _updateState.value = if (update != null) UpdateState.UpdateAvailable(update)
                                     else UpdateState.UpToDate
            } catch (e: Exception) {
                AppLogger.e(TAG, "Update check failed", e)
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Downloads the APK for [info] and launches the system package installer.
     * If `REQUEST_INSTALL_PACKAGES` has not been granted, opens the system settings page first.
     */
    fun downloadAndInstall(info: UpdateInfo) {
        viewModelScope.launch {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _updateState.value = UpdateState.Error(
                    "Allow installing from this source in Settings, then try again"
                )
                return@launch
            }
            _updateState.value = UpdateState.Downloading
            val apkFile = updateManager.downloadApk(info.downloadUrl)
            if (apkFile != null) {
                _updateState.value = UpdateState.ReadyToInstall(apkFile.absolutePath)
                context.startActivity(updateManager.createInstallIntent(apkFile))
            } else {
                _updateState.value = UpdateState.Error("Download failed — opening browser instead")
                context.startActivity(updateManager.createBrowserDownloadIntent())
            }
        }
    }

    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    // ── Backup export state ───────────────────────────────────────────────────

    /**
     * State machine for the manual backup export flow.
     * [Done.itemCount] is whatever integer [exportBackupToUri] returns (e.g. number of records).
     */
    sealed class ExportState {
        object Idle : ExportState()
        object Exporting : ExportState()
        data class Done(val itemCount: Int) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    /**
     * Exports app data to the SAF [uri] chosen by the user via [CreateDocument].
     *
     * The [uri] works transparently with any storage provider — local filesystem, Google Drive,
     * Dropbox, USB, etc. — without any extra SDK integration.
     *
     * **TODO**: Inject your [backup.BaseBackupManager] subclass and call `backupManager.exportToUri(uri)`.
     * Replace the placeholder body below with your actual backup logic.
     */
    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting
            try {
                backupManager.exportToUri(uri)
                val count = (backupManager.collectBackupData().data.asJsonArray?.size() ?: 0)
                AppLogger.i(TAG, "Backup exported: $count readings")
                _exportState.value = ExportState.Done(itemCount = count)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Export failed", e)
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    // ── Backup restore state ──────────────────────────────────────────────────

    /**
     * State machine for the backup restore flow.
     * [Done.itemCount] is whatever integer [restoreFromBackup] returns (e.g. number of records restored).
     */
    sealed class RestoreState {
        object Idle : RestoreState()
        object Restoring : RestoreState()
        data class Done(val itemCount: Int) : RestoreState()
        data class Error(val message: String) : RestoreState()
    }

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState

    /**
     * Restores app data from the backup ZIP at the SAF [uri] chosen by the user via [OpenDocument].
     *
     * **TODO**: Inject your [backup.BaseBackupManager] subclass and call `backupManager.importFromUri(uri)`.
     * Replace the placeholder body below with your actual restore logic.
     */
    fun restoreFromBackup(uri: Uri) {
        viewModelScope.launch {
            _restoreState.value = RestoreState.Restoring
            try {
                backupManager.importFromUri(uri)
                AppLogger.i(TAG, "Backup restored")
                _restoreState.value = RestoreState.Done(itemCount = 0)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Restore failed", e)
                _restoreState.value = RestoreState.Error(e.message ?: "Restore failed")
            }
        }
    }

    fun resetRestoreState() {
        _restoreState.value = RestoreState.Idle
    }

    // ── Anova device settings ─────────────────────────────────────────────────

    val tempUnitCelsius: StateFlow<Boolean> = anovaSettings.tempUnitCelsius
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val appTheme: StateFlow<String> = anovaSettings.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DARK")
    val historySampleMs: StateFlow<Long> = anovaSettings.historySampleMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnovaSettings.DEFAULT_HISTORY_SAMPLE_MS)
    val historyRetentionDays: StateFlow<Int> = anovaSettings.historyRetentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnovaSettings.DEFAULT_HISTORY_RETENTION_DAYS)
    val alertCookFinished: StateFlow<Boolean>   = anovaSettings.alertCookFinished.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertTempTarget: StateFlow<Boolean>     = anovaSettings.alertTempTarget.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertDeviceOffline: StateFlow<Boolean>  = anovaSettings.alertDeviceOffline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertScheduleFailed: StateFlow<Boolean> = anovaSettings.alertScheduleFailed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertCookStarted: StateFlow<Boolean>    = anovaSettings.alertCookStarted.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val anovaJwtExpiryMs: Long get() = firebaseAuth.anovaJwtExpiryMs
    val anovaStoredEmail: String? get() = firebaseAuth.storedEmail

    fun setTempUnitCelsius(v: Boolean) = viewModelScope.launch { anovaSettings.setTempUnitCelsius(v) }
    fun setAppTheme(t: String)         = viewModelScope.launch { anovaSettings.setAppTheme(t) }
    fun setHistorySampleMs(ms: Long)   = viewModelScope.launch { anovaSettings.setHistorySampleMs(ms) }
    fun setHistoryRetentionDays(d: Int)= viewModelScope.launch { anovaSettings.setHistoryRetentionDays(d) }
    fun setAlertCookFinished(v: Boolean)   = viewModelScope.launch { anovaSettings.setAlertCookFinished(v) }
    fun setAlertTempTarget(v: Boolean)     = viewModelScope.launch { anovaSettings.setAlertTempTarget(v) }
    fun setAlertDeviceOffline(v: Boolean)  = viewModelScope.launch { anovaSettings.setAlertDeviceOffline(v) }
    fun setAlertScheduleFailed(v: Boolean) = viewModelScope.launch { anovaSettings.setAlertScheduleFailed(v) }
    fun setAlertCookStarted(v: Boolean)    = viewModelScope.launch { anovaSettings.setAlertCookStarted(v) }

    // ── Alert sound & vibration ───────────────────────────────────────────────

    val alertSoundUri: StateFlow<String?> = anovaSettings.alertSoundUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val alertVibrate: StateFlow<Boolean> = anovaSettings.alertVibrate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAlertSoundUri(uri: String?) = viewModelScope.launch {
        if (uri != null) {
            // Take a persistable read permission so the URI stays accessible across restarts.
            // Without this, content:// URIs from the picker become invalid after the app restarts.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        anovaSettings.setAlertSoundUri(uri)
        val vibrate = anovaSettings.alertVibrate.first()
        alertManager.recreateAlertChannels(uri, vibrate)
    }

    fun setAlertVibrate(on: Boolean) = viewModelScope.launch {
        anovaSettings.setAlertVibrate(on)
        val uri = anovaSettings.alertSoundUri.first()
        alertManager.recreateAlertChannels(uri, on)
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
