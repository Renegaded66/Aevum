package com.d_drostes_apps.aevum.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.db.ActivityCandidateDao
import com.d_drostes_apps.aevum.data.db.ActivitySessionChangeDao
import com.d_drostes_apps.aevum.data.db.ActivitySessionDao
import com.d_drostes_apps.aevum.data.db.ActivityTypeDao
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.data.db.AppLimitDao
import com.d_drostes_apps.aevum.data.db.AppUsageSampleDao
import com.d_drostes_apps.aevum.data.db.GeofenceEventLogDao
import com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepository
import com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.UnknownPlaceSessionRepository
import com.d_drostes_apps.aevum.data.repository.UnknownPlaceSessionRepositoryImpl
import com.d_drostes_apps.aevum.data.db.UnknownPlaceSessionDao
import com.d_drostes_apps.aevum.data.db.DailyAllowanceDao
import com.d_drostes_apps.aevum.data.db.BucketListItemDao
import com.d_drostes_apps.aevum.data.db.CategoryDao
import com.d_drostes_apps.aevum.data.db.DataSourceDao
import com.d_drostes_apps.aevum.data.db.DetectionEventDao
import com.d_drostes_apps.aevum.data.db.GoalDao
import com.d_drostes_apps.aevum.data.db.HabitDao
import com.d_drostes_apps.aevum.data.db.HabitLogDao
import com.d_drostes_apps.aevum.data.db.LifeProfileDao
import com.d_drostes_apps.aevum.data.db.PlaceGeofenceDao
import com.d_drostes_apps.aevum.data.db.RawDetectionEventDao
import com.d_drostes_apps.aevum.data.db.RawSourceEventDao
import com.d_drostes_apps.aevum.data.db.SessionEvidenceDao
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.ActivitySessionChangeRepository
import com.d_drostes_apps.aevum.data.repository.ActivitySessionChangeRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.AppLimitRepository
import com.d_drostes_apps.aevum.data.repository.AppLimitRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.AppUsageSampleRepository
import com.d_drostes_apps.aevum.data.repository.AppUsageSampleRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.GeofenceEventLogRepository
import com.d_drostes_apps.aevum.data.repository.GeofenceEventLogRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.BucketListRepository
import com.d_drostes_apps.aevum.data.repository.BucketListRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.DataSourceRepository
import com.d_drostes_apps.aevum.data.repository.DataSourceRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.DetectionEventRepository
import com.d_drostes_apps.aevum.data.repository.DetectionEventRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import com.d_drostes_apps.aevum.data.repository.GoalRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.HabitRepository
import com.d_drostes_apps.aevum.data.repository.HabitRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.LifeProfileRepository
import com.d_drostes_apps.aevum.data.repository.LifeProfileRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.RawDetectionEventRepository
import com.d_drostes_apps.aevum.data.repository.RawDetectionEventRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.RawSourceEventRepository
import com.d_drostes_apps.aevum.data.repository.RawSourceEventRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.SessionEvidenceRepository
import com.d_drostes_apps.aevum.data.repository.SessionEvidenceRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.TagRepository
import com.d_drostes_apps.aevum.data.repository.TagRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepositoryImpl
import com.d_drostes_apps.aevum.data.db.TagDao
import com.d_drostes_apps.aevum.data.db.TriggerEventDao
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.GarminRepository
import com.d_drostes_apps.aevum.data.repository.GarminRepositoryImpl
import com.d_drostes_apps.aevum.data.db.GarminDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides @Singleton
    fun provideActivityRepository(activityDao: ActivitySessionDao, rawEventDao: RawDetectionEventDao): ActivityRepository =
        ActivityRepositoryImpl(activityDao, rawEventDao)

    @Provides @Singleton
    fun provideActivityCandidateRepository(dao: ActivityCandidateDao): ActivityCandidateRepository = ActivityCandidateRepositoryImpl(dao)

    @Provides @Singleton
    fun provideActivityTypeRepository(dao: ActivityTypeDao): ActivityTypeRepository = ActivityTypeRepositoryImpl(dao)

    @Provides @Singleton
    fun provideSessionEvidenceRepository(dao: SessionEvidenceDao): SessionEvidenceRepository = SessionEvidenceRepositoryImpl(dao)

    @Provides @Singleton
    fun provideActivitySessionChangeRepository(dao: ActivitySessionChangeDao): ActivitySessionChangeRepository = ActivitySessionChangeRepositoryImpl(dao)

    @Provides @Singleton
    fun provideRawSourceEventRepository(dao: RawSourceEventDao): RawSourceEventRepository = RawSourceEventRepositoryImpl(dao)

    @Provides @Singleton
    fun provideDetectionEventRepository(dao: DetectionEventDao): DetectionEventRepository = DetectionEventRepositoryImpl(dao)

    @Provides @Singleton
    fun provideDataSourceRepository(dao: DataSourceDao): DataSourceRepository = DataSourceRepositoryImpl(dao)

    @Provides @Singleton
    fun provideCategoryRepository(dao: CategoryDao): CategoryRepository = CategoryRepositoryImpl(dao)

    @Provides @Singleton
    fun provideTagRepository(dao: TagDao): TagRepository = TagRepositoryImpl(dao)

    @Provides @Singleton
    fun provideGoalRepository(dao: GoalDao): GoalRepository = GoalRepositoryImpl(dao)

    @Provides @Singleton
    fun provideHabitRepository(habitDao: HabitDao, habitLogDao: HabitLogDao): HabitRepository =
        HabitRepositoryImpl(habitDao, habitLogDao)

    @Provides @Singleton
    fun provideBucketListRepository(dao: BucketListItemDao): BucketListRepository = BucketListRepositoryImpl(dao)

    @Provides @Singleton
    fun provideLifeProfileRepository(dao: LifeProfileDao): LifeProfileRepository = LifeProfileRepositoryImpl(dao)

    @Provides @Singleton
    fun providePlaceGeofenceRepository(dao: PlaceGeofenceDao): PlaceGeofenceRepository = PlaceGeofenceRepositoryImpl(dao)

    @Provides @Singleton
    fun provideTriggerEventRepository(dao: TriggerEventDao): TriggerEventRepository = TriggerEventRepositoryImpl(dao)

    @Provides @Singleton
    fun provideAutomationSettingsRepository(dao: AutomationSettingsDao): AutomationSettingsRepository = AutomationSettingsRepositoryImpl(dao)

    // M18.58: Garmin Connect
    @Provides @Singleton
    fun provideGarminRepository(dao: GarminDao): GarminRepository = GarminRepositoryImpl(dao)

    @Provides @Singleton
    fun provideRawDetectionEventRepository(dao: RawDetectionEventDao): RawDetectionEventRepository = RawDetectionEventRepositoryImpl(dao)

    @Provides @Singleton
    fun provideAppUsageSampleRepository(dao: AppUsageSampleDao): AppUsageSampleRepository = AppUsageSampleRepositoryImpl(dao)

    // M18.61: Digital Balance — App-Limits
    @Provides @Singleton
    fun provideAppLimitRepository(dao: AppLimitDao): AppLimitRepository = AppLimitRepositoryImpl(dao)

    @Provides
    fun provideGeofenceEventLogRepository(dao: GeofenceEventLogDao): GeofenceEventLogRepository = GeofenceEventLogRepositoryImpl(dao)

    @Provides
    fun provideUnknownPlaceSessionRepository(dao: UnknownPlaceSessionDao): UnknownPlaceSessionRepository = UnknownPlaceSessionRepositoryImpl(dao)

    @Provides
    fun provideDailyAllowanceRepository(dao: DailyAllowanceDao): DailyAllowanceRepository = DailyAllowanceRepositoryImpl(dao)
}
