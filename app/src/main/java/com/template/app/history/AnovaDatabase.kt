package com.template.app.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TemperatureReading::class],
    version = 1,
    exportSchema = false
)
abstract class AnovaDatabase : RoomDatabase() {
    abstract fun temperatureReadingDao(): TemperatureReadingDao

    companion object {
        @Volatile
        private var INSTANCE: AnovaDatabase? = null

        fun getInstance(context: Context): AnovaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnovaDatabase::class.java,
                    "anova_db"
                ).build().also { INSTANCE = it }
            }
    }
}
