package com.template.app.presets

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class Preset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetTemp: Float,       // Always stored in Celsius
    val timerMinutes: Int,       // 0 = no timer
    val notes: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)
