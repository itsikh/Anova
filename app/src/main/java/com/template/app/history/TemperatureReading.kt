package com.template.app.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single temperature reading persisted to the local Room database.
 * Recorded once per poll cycle (approximately every 2 seconds) while connected.
 */
@Entity(tableName = "temperature_readings")
data class TemperatureReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,          // System.currentTimeMillis()
    val temperature: Float,       // In the unit reported by the device
    val unit: String,             // "C" or "F"
    val status: String,           // "running", "stopped", "unknown"
    val timerMinutes: Int?        // null if timer is not active
)
