package com.template.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TemperatureReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: TemperatureReading)

    /** Last [limit] readings ordered newest-first, for the live monitor. */
    @Query("SELECT * FROM temperature_readings ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentReadings(limit: Int = 200): Flow<List<TemperatureReading>>

    /** All readings within the given timestamp range, for history charts. */
    @Query("SELECT * FROM temperature_readings WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getReadingsInRange(from: Long, to: Long): List<TemperatureReading>

    /** Delete readings older than [beforeTimestamp] to keep storage bounded. */
    @Query("DELETE FROM temperature_readings WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    /** Total number of stored readings. */
    @Query("SELECT COUNT(*) FROM temperature_readings")
    suspend fun count(): Int
}
