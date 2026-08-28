package com.d_drostes_apps.aevum.di

import android.content.Context
import com.d_drostes_apps.aevum.automation.rules.RuleStrings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuleStringsModule {
    @Provides
    @Singleton
    fun provideRuleStrings(@ApplicationContext context: Context): RuleStrings = RuleStrings(context)
}
