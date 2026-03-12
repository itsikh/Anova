package com.template.app.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.template.app.schedule.ScheduleDao
import com.template.app.schedule.ScheduleEntry

@Database(
    entities = [TemperatureReading::class, ScheduleEntry::class],
    version = 2,
    exportSchema = false
)
abstract class AnovaDatabase : RoomDatabase() {
    abstract fun temperatureReadingDao(): TemperatureReadingDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile private var INSTANCE: AnovaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS schedules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        scheduledAtMs INTEGER NOT NULL,
                        targetTemp REAL,
                        timerSeconds INTEGER,
                        createdAtMs INTEGER NOT NULL,
                        isPending INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AnovaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnovaDatabase::class.java,
                    "anova_db"
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
            }
    }
}
