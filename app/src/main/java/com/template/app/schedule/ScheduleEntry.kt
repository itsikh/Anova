package com.template.app.schedule

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class ScheduleEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,              // "START" | "STOP"
    val scheduledAtMs: Long,       // when to fire
    val targetTemp: Float? = null, // for START commands
    val timerSeconds: Int? = null, // for START commands
    val createdAtMs: Long = System.currentTimeMillis(),
    val isPending: Boolean = true  // false once fired or cancelled
)
