package de.devondroste.aevum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import de.devondroste.aevum.data.model.*
import de.devondroste.aevum.data.converters.Converters

@Database(
    entities = [
        LifeProfile::class,
        Category::class,
        Tag::class,
        ActivitySession::class,
        ActivitySessionTag::class,
        RawDetectionEvent::class,
        PlaceGeofence::class,
        Goal::class,
        Habit::class,
        HabitLog::class,
        BucketListItem::class,
        AppUsageSample::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lifeProfileDao(): LifeProfileDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun activitySessionDao(): ActivitySessionDao
    abstract fun rawDetectionEventDao(): RawDetectionEventDao
    abstract fun placeGeofenceDao(): PlaceGeofenceDao
    abstract fun goalDao(): GoalDao
    abstract fun habitDao(): HabitDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun bucketListItemDao(): BucketListItemDao
    abstract fun appUsageSampleDao(): AppUsageSampleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aevum_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}