package com.d_drostes_apps.aevum.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.db.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "aevum_database"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_23_24, AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_25_26, AppDatabase.MIGRATION_26_27, AppDatabase.MIGRATION_27_28, AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31)
            // M18.56: Notnagel gegen Schema-Validierungs-Crash. Wenn die DB
            // aus irgendeinem Grund nicht zum Entity-Schema passt (z.B. nach
            // Package-Rename, abgebrochenem Update oder korrupter Datei),
            // würde Room beim Öffnen crashen und ALLE DB-Operationen
            // stillschweigend fehlschlagen (Symptom: "nichts speichert",
            // Toggles tot, keine Defaults). Mit diesem Fallback wird die DB
            // neu erstellt — Daten gehen nur im Crash-Fall verloren, die App
            // funktioniert aber immer. Backup/Export existieren als Schutz.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideLifeProfileDao(database: AppDatabase): LifeProfileDao = database.lifeProfileDao()
    @Provides fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()
    @Provides fun provideTagDao(database: AppDatabase): TagDao = database.tagDao()
    @Provides fun provideActivitySessionDao(database: AppDatabase): ActivitySessionDao = database.activitySessionDao()
    @Provides fun provideActivityCandidateDao(database: AppDatabase): ActivityCandidateDao = database.activityCandidateDao()
    @Provides fun provideActivityTypeDao(database: AppDatabase): ActivityTypeDao = database.activityTypeDao()
    @Provides fun provideRawDetectionEventDao(database: AppDatabase): RawDetectionEventDao = database.rawDetectionEventDao()
    @Provides fun provideRawSourceEventDao(database: AppDatabase): RawSourceEventDao = database.rawSourceEventDao()
    @Provides fun provideDetectionEventDao(database: AppDatabase): DetectionEventDao = database.detectionEventDao()
    @Provides fun provideDataSourceDao(database: AppDatabase): DataSourceDao = database.dataSourceDao()
    @Provides fun providePlaceGeofenceDao(database: AppDatabase): PlaceGeofenceDao = database.placeGeofenceDao()
    @Provides fun provideTriggerEventDao(database: AppDatabase): TriggerEventDao = database.triggerEventDao()
    @Provides fun provideAutomationSettingsDao(database: AppDatabase): AutomationSettingsDao = database.automationSettingsDao()
    @Provides fun provideGoalDao(database: AppDatabase): GoalDao = database.goalDao()
    @Provides fun provideHabitDao(database: AppDatabase): HabitDao = database.habitDao()
    @Provides fun provideHabitLogDao(database: AppDatabase): HabitLogDao = database.habitLogDao()
    @Provides fun provideBucketListItemDao(database: AppDatabase): BucketListItemDao = database.bucketListItemDao()
    @Provides fun provideAppUsageSampleDao(database: AppDatabase): AppUsageSampleDao = database.appUsageSampleDao()
    @Provides fun provideActivitySessionChangeDao(database: AppDatabase): ActivitySessionChangeDao = database.activitySessionChangeDao()
    @Provides fun provideSessionEvidenceDao(database: AppDatabase): SessionEvidenceDao = database.sessionEvidenceDao()
    @Provides fun provideActivityAggregateDayDao(database: AppDatabase): ActivityAggregateDayDao = database.activityAggregateDayDao()
    @Provides fun provideGeofenceEventLogDao(database: AppDatabase): GeofenceEventLogDao = database.geofenceEventLogDao()
    @Provides fun provideUnknownPlaceSessionDao(database: AppDatabase): UnknownPlaceSessionDao = database.unknownPlaceSessionDao()
    @Provides fun provideDailyAllowanceDao(database: AppDatabase): DailyAllowanceDao = database.dailyAllowanceDao()
    // M18.30: Todos
    @Provides fun provideTodoDao(database: AppDatabase): TodoDao = database.todoDao()
    // M18.58: Garmin Connect
    @Provides fun provideGarminDao(database: AppDatabase): GarminDao = database.garminDao()
    // M18.61: Digital Balance — App-Limits
    @Provides fun provideAppLimitDao(database: AppDatabase): AppLimitDao = database.appLimitDao()
    // M18.61f: Digital Balance — Profile
    @Provides fun provideBalanceProfileDao(database: AppDatabase): BalanceProfileDao = database.balanceProfileDao()
    // M18.61g: Ping-Trigger
    @Provides fun providePingTriggerDao(database: AppDatabase): PingTriggerDao = database.pingTriggerDao()
}