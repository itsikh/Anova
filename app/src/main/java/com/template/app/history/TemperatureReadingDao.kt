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

    @Query("SELECT * FROM temperature_readings ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentReadings(limit: Int = 500): Flow<List<TemperatureReading>>

    @Query("SELECT * FROM temperature_readings WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getReadingsInRange(from: Long, to: Long): List<TemperatureReading>

    @Query("SELECT * FROM temperature_readings ORDER BY timestamp ASC")
    suspend fun getAllReadings(): List<TemperatureReading>

    @Query("DELETE FROM temperature_readings WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    @Query("SELECT COUNT(*) FROM temperature_readings")
    suspend fun count(): Int
}
