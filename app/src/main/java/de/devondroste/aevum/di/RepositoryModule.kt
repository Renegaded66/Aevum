package de.devondroste.aevum.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.data.db.ActivityCandidateDao
import de.devondroste.aevum.data.db.ActivitySessionChangeDao
import de.devondroste.aevum.data.db.ActivitySessionDao
import de.devondroste.aevum.data.db.ActivityTypeDao
import de.devondroste.aevum.data.db.AppUsageSampleDao
import de.devondroste.aevum.data.db.BucketListItemDao
import de.devondroste.aevum.data.db.CategoryDao
import de.devondroste.aevum.data.db.DataSourceDao
import de.devondroste.aevum.data.db.DetectionEventDao
import de.devondroste.aevum.data.db.GoalDao
import de.devondroste.aevum.data.db.HabitDao
import de.devondroste.aevum.data.db.HabitLogDao
import de.devondroste.aevum.data.db.LifeProfileDao
import de.devondroste.aevum.data.db.PlaceGeofenceDao
import de.devondroste.aevum.data.db.RawDetectionEventDao
import de.devondroste.aevum.data.db.RawSourceEventDao
import de.devondroste.aevum.data.db.SessionEvidenceDao
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityCandidateRepositoryImpl
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityRepositoryImpl
import de.devondroste.aevum.data.repository.ActivitySessionChangeRepository
import de.devondroste.aevum.data.repository.ActivitySessionChangeRepositoryImpl
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepositoryImpl
import de.devondroste.aevum.data.repository.AppUsageSampleRepository
import de.devondroste.aevum.data.repository.AppUsageSampleRepositoryImpl
import de.devondroste.aevum.data.repository.BucketListRepository
import de.devondroste.aevum.data.repository.BucketListRepositoryImpl
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.CategoryRepositoryImpl
import de.devondroste.aevum.data.repository.DataSourceRepository
import de.devondroste.aevum.data.repository.DataSourceRepositoryImpl
import de.devondroste.aevum.data.repository.DetectionEventRepository
import de.devondroste.aevum.data.repository.DetectionEventRepositoryImpl
import de.devondroste.aevum.data.repository.GoalRepository
import de.devondroste.aevum.data.repository.GoalRepositoryImpl
import de.devondroste.aevum.data.repository.HabitRepository
import de.devondroste.aevum.data.repository.HabitRepositoryImpl
import de.devondroste.aevum.data.repository.LifeProfileRepository
import de.devondroste.aevum.data.repository.LifeProfileRepositoryImpl
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import de.devondroste.aevum.data.repository.PlaceGeofenceRepositoryImpl
import de.devondroste.aevum.data.repository.RawDetectionEventRepository
import de.devondroste.aevum.data.repository.RawDetectionEventRepositoryImpl
import de.devondroste.aevum.data.repository.RawSourceEventRepository
import de.devondroste.aevum.data.repository.RawSourceEventRepositoryImpl
import de.devondroste.aevum.data.repository.SessionEvidenceRepository
import de.devondroste.aevum.data.repository.SessionEvidenceRepositoryImpl
import de.devondroste.aevum.data.repository.TagRepository
import de.devondroste.aevum.data.repository.TagRepositoryImpl
import de.devondroste.aevum.data.db.TagDao
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
    fun provideRawDetectionEventRepository(dao: RawDetectionEventDao): RawDetectionEventRepository = RawDetectionEventRepositoryImpl(dao)

    @Provides @Singleton
    fun provideAppUsageSampleRepository(dao: AppUsageSampleDao): AppUsageSampleRepository = AppUsageSampleRepositoryImpl(dao)
}
