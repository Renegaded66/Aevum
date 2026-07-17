package de.devondroste.aevum.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.data.db.*
import de.devondroste.aevum.data.repository.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides @Singleton
    fun provideActivityRepository(activityDao: ActivitySessionDao, rawEventDao: RawDetectionEventDao): ActivityRepository =
        ActivityRepositoryImpl(activityDao, rawEventDao)

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
