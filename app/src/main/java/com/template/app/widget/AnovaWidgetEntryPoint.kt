package com.template.app.widget

import com.template.app.anova.AnovaRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AnovaWidgetEntryPoint {
    fun anovaRepository(): AnovaRepository
}
