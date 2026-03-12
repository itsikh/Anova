package com.template.app.presets

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdAtMs DESC")
    fun getAllFlow(): Flow<List<Preset>>

    @Upsert
    suspend fun upsert(preset: Preset)

    @Delete
    suspend fun delete(preset: Preset)
}
