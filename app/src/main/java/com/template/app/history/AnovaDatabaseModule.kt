package com.template.app.history

import android.content.Context
import com.template.app.presets.PresetDao
import com.template.app.schedule.ScheduleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AnovaDatabaseModule {

    @Provides
    @Singleton
    fun provideAnovaDatabase(@ApplicationContext context: Context): AnovaDatabase =
        AnovaDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideTemperatureReadingDao(db: AnovaDatabase): TemperatureReadingDao =
        db.temperatureReadingDao()

    @Provides
    @Singleton
    fun provideScheduleDao(db: AnovaDatabase): ScheduleDao =
        db.scheduleDao()

    @Provides
    @Singleton
    fun providePresetDao(db: AnovaDatabase): PresetDao =
        db.presetDao()
}
