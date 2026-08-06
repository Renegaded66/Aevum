package de.devondroste.aevum.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.data.db.*
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
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17)
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
}