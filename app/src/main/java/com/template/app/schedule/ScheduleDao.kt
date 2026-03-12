package com.template.app.schedule

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ScheduleEntry): Long

    @Delete
    suspend fun delete(entry: ScheduleEntry)

    @Update
    suspend fun update(entry: ScheduleEntry)

    @Query("SELECT * FROM schedules WHERE isPending = 1 ORDER BY scheduledAtMs ASC")
    fun getPendingSchedules(): Flow<List<ScheduleEntry>>

    @Query("SELECT * FROM schedules ORDER BY scheduledAtMs DESC")
    fun getAllSchedules(): Flow<List<ScheduleEntry>>

    @Query("SELECT * FROM schedules WHERE isPending = 1 AND scheduledAtMs <= :nowMs ORDER BY scheduledAtMs ASC")
    suspend fun getDueSchedules(nowMs: Long): List<ScheduleEntry>

    @Query("UPDATE schedules SET isPending = 0 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("DELETE FROM schedules WHERE scheduledAtMs < :beforeMs AND isPending = 0")
    suspend fun purgeOld(beforeMs: Long)
}
