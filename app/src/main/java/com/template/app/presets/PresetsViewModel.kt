package com.template.app.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.template.app.anova.AnovaRepository
import com.template.app.anova.AnovaSettings
import com.template.app.logging.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PresetsViewModel @Inject constructor(
    private val presetDao: PresetDao,
    private val repository: AnovaRepository,
    private val settings: AnovaSettings
) : ViewModel() {

    val presets: StateFlow<List<Preset>> = presetDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(preset: Preset) {
        viewModelScope.launch {
            presetDao.upsert(preset)
            AppLogger.i(TAG, "Preset saved: ${preset.name}")
        }
    }

    fun delete(preset: Preset) {
        viewModelScope.launch {
            presetDao.delete(preset)
            AppLogger.i(TAG, "Preset deleted: ${preset.name}")
        }
    }

    /**
     * Set target temp + timer on device, then start the cook.
     * Presets store temp in Celsius; converts to device unit if user prefers Fahrenheit.
     */
    fun startCookWithPreset(preset: Preset, onError: (String) -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            val wantCelsius = settings.tempUnitCelsius.first()
            val targetTemp = if (wantCelsius) preset.targetTemp
                             else preset.targetTemp * 9f / 5f + 32f
            val timerSecs = if (preset.timerMinutes > 0) preset.timerMinutes * 60 else null
            val ok = repository.updateCook(targetTemp = targetTemp, timerSeconds = timerSecs)
            if (!ok) { onError("Failed to set preset — check connection."); return@launch }
            val started = repository.startCook()
            if (!started) { onError("Failed to start cook — check connection."); return@launch }
            AppLogger.i(TAG, "Cook started with preset: ${preset.name}")
            onDone()
        }
    }

    companion object {
        private const val TAG = "PresetsViewModel"
    }
}
