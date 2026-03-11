package com.template.app.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.template.app.history.TemperatureReading
import com.template.app.history.TemperatureReadingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    readingDao: TemperatureReadingDao
) : ViewModel() {

    val readings: StateFlow<List<TemperatureReading>> = readingDao
        .getRecentReadings(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
