package com.template.app.ui.screens.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.template.app.schedule.AnovaScheduler
import com.template.app.schedule.ScheduleDao
import com.template.app.schedule.ScheduleEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val scheduler: AnovaScheduler
) : ViewModel() {

    val schedules: StateFlow<List<ScheduleEntry>> = scheduleDao.getAllSchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addSchedule(entry: ScheduleEntry) = scheduler.schedule(entry)

    fun deleteSchedule(entry: ScheduleEntry) {
        viewModelScope.launch { scheduleDao.delete(entry) }
        scheduler.cancel(entry)
    }
}
