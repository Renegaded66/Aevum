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
            // M28: EINE Quelle für alle Migrationen (Produktion + Tests).
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // M18.109 (User-Vorgabe): KEIN fallbackToDestructiveMigration mehr!
            // Vorher (M18.56) wurde die DB bei fehlender Migration still
            // gelöscht — Datenverlust ohne Warnung. Jetzt gilt Fail-Fast:
            // Fehlt eine Migration, wirft Room beim Öffnen eine
            // IllegalStateException. AevumApplication.onCreate öffnet die
            // DB bewusst OHNE try/catch (Fail-Fast-Check) → die App
            // crasht beim Start mit klarem Stacktrace in last-crash.log
            // (CrashLogger), statt die Datenbank zu vernichten. Ein
            // Update mit der fehlenden Migration repariert die
            // Bestands-DB dann normal.
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
    // M20: Check-ins manueller Ziele (Ziel-Architektur).
    @Provides fun provideGoalCheckinDao(database: AppDatabase): GoalCheckinDao = database.goalCheckinDao()
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
    // M18.67: App-Aufzeichnung
    @Provides fun provideAppTrackingEntryDao(database: AppDatabase): AppTrackingEntryDao = database.appTrackingEntryDao()
    // M18.86: GPS-Streckenpunkte (Orts-Timeline-Karte)
    @Provides fun provideLocationTrackPointDao(database: AppDatabase): LocationTrackPointDao = database.locationTrackPointDao()
    // M27: Sucht-Detektor
    @Provides fun provideAddictionWatchAppDao(database: AppDatabase): AddictionWatchAppDao = database.addictionWatchAppDao()
    @Provides fun provideAddictionSessionDao(database: AppDatabase): AddictionSessionDao = database.addictionSessionDao()
}