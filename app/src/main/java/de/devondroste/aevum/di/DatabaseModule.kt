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
        ).build()

    @Provides fun provideLifeProfileDao(database: AppDatabase): LifeProfileDao = database.lifeProfileDao()
    @Provides fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()
    @Provides fun provideTagDao(database: AppDatabase): TagDao = database.tagDao()
    @Provides fun provideActivitySessionDao(database: AppDatabase): ActivitySessionDao = database.activitySessionDao()
    @Provides fun provideRawDetectionEventDao(database: AppDatabase): RawDetectionEventDao = database.rawDetectionEventDao()
    @Provides fun providePlaceGeofenceDao(database: AppDatabase): PlaceGeofenceDao = database.placeGeofenceDao()
    @Provides fun provideGoalDao(database: AppDatabase): GoalDao = database.goalDao()
    @Provides fun provideHabitDao(database: AppDatabase): HabitDao = database.habitDao()
    @Provides fun provideHabitLogDao(database: AppDatabase): HabitLogDao = database.habitLogDao()
    @Provides fun provideBucketListItemDao(database: AppDatabase): BucketListItemDao = database.bucketListItemDao()
    @Provides fun provideAppUsageSampleDao(database: AppDatabase): AppUsageSampleDao = database.appUsageSampleDao()
}
