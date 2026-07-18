package de.devondroste.aevum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.devondroste.aevum.data.converters.Converters
import de.devondroste.aevum.data.model.*

@Database(
    entities = [
        LifeProfile::class,
        Category::class,
        Tag::class,
        ActivitySession::class,
        ActivityCandidate::class,
        ActivityType::class,
        ActivitySessionTag::class,
        RawDetectionEvent::class,
        RawSourceEvent::class,
        DetectionEvent::class,
        DataSource::class,
        PlaceGeofence::class,
        PlaceGeofenceTag::class,
        TriggerEvent::class,
        AutomationSettings::class,
        Goal::class,
        Habit::class,
        HabitLog::class,
        BucketListItem::class,
        AppUsageSample::class,
        ActivitySessionChange::class,
        SessionEvidence::class,
        ActivityAggregateDay::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lifeProfileDao(): LifeProfileDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun activitySessionDao(): ActivitySessionDao
    abstract fun activityCandidateDao(): ActivityCandidateDao
    abstract fun activityTypeDao(): ActivityTypeDao
    abstract fun rawDetectionEventDao(): RawDetectionEventDao
    abstract fun rawSourceEventDao(): RawSourceEventDao
    abstract fun detectionEventDao(): DetectionEventDao
    abstract fun dataSourceDao(): DataSourceDao
    abstract fun placeGeofenceDao(): PlaceGeofenceDao
    abstract fun triggerEventDao(): TriggerEventDao
    abstract fun automationSettingsDao(): AutomationSettingsDao
    abstract fun goalDao(): GoalDao
    abstract fun habitDao(): HabitDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun bucketListItemDao(): BucketListItemDao
    abstract fun appUsageSampleDao(): AppUsageSampleDao
    abstract fun activitySessionChangeDao(): ActivitySessionChangeDao
    abstract fun sessionEvidenceDao(): SessionEvidenceDao
    abstract fun activityAggregateDayDao(): ActivityAggregateDayDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Create new tables
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS data_source (
                        id TEXT PRIMARY KEY NOT NULL,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        permission_state TEXT NOT NULL DEFAULT 'UNKNOWN',
                        last_sync_at INTEGER,
                        config_json TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_data_source_type ON data_source(type)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_data_source_enabled ON data_source(enabled)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS raw_source_event (
                        id TEXT PRIMARY KEY NOT NULL,
                        source_id TEXT NOT NULL,
                        external_id TEXT,
                        event_type TEXT NOT NULL,
                        observed_at INTEGER NOT NULL,
                        start_at INTEGER,
                        end_at INTEGER,
                        timezone_id TEXT,
                        payload_json TEXT NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        ingested_at INTEGER NOT NULL,
                        processed_at INTEGER,
                        FOREIGN KEY(source_id) REFERENCES data_source(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_source_observed ON raw_source_event(source_id, observed_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_processed ON raw_source_event(processed_at)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_raw_source_external ON raw_source_event(source_id, external_id) WHERE external_id IS NOT NULL")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS detection_event (
                        id TEXT PRIMARY KEY NOT NULL,
                        raw_event_id TEXT,
                        source_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        start_at INTEGER NOT NULL,
                        end_at INTEGER,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        place_id TEXT,
                        metadata_json TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(raw_event_id) REFERENCES raw_source_event(id) ON DELETE SET NULL,
                        FOREIGN KEY(source_id) REFERENCES data_source(id) ON DELETE CASCADE,
                        FOREIGN KEY(place_id) REFERENCES place_geofence(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_detection_kind_time ON detection_event(kind, start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_detection_source_time ON detection_event(source_id, start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_detection_raw_event ON detection_event(raw_event_id)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS activity_type (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        default_category_id TEXT,
                        is_system INTEGER NOT NULL DEFAULT 1,
                        properties_json TEXT,
                        FOREIGN KEY(default_category_id) REFERENCES category(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_activity_type_system ON activity_type(is_system)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_activity_type_category ON activity_type(default_category_id)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS activity_candidate (
                        id TEXT PRIMARY KEY NOT NULL,
                        suggested_title TEXT NOT NULL,
                        suggested_category_id TEXT,
                        activity_type_id TEXT,
                        start_at INTEGER NOT NULL,
                        end_at INTEGER NOT NULL,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        reason TEXT,
                        created_by TEXT NOT NULL DEFAULT 'AUTO',
                        created_at INTEGER NOT NULL,
                        resolved_at INTEGER,
                        resolved_session_id TEXT,
                        FOREIGN KEY(suggested_category_id) REFERENCES category(id) ON DELETE SET NULL,
                        FOREIGN KEY(activity_type_id) REFERENCES activity_type(id) ON DELETE SET NULL,
                        FOREIGN KEY(resolved_session_id) REFERENCES activity_session(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_candidate_status_time ON activity_candidate(status, start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_candidate_type_time ON activity_candidate(activity_type_id, start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_candidate_resolved ON activity_candidate(resolved_session_id)")


                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS activity_session (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        category_id TEXT,
                        activity_type_id TEXT,
                        start_at INTEGER NOT NULL,
                        end_at INTEGER,
                        timezone_id TEXT NOT NULL DEFAULT 'UTC',
                        description TEXT,
                        source_type TEXT NOT NULL DEFAULT 'MANUAL',
                        created_by TEXT NOT NULL DEFAULT 'MANUAL',
                        updated_by TEXT,
                        source_candidate_id TEXT,
                        supersedes_session_id TEXT,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        is_user_edited INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        revision INTEGER NOT NULL DEFAULT 1,
                        origin_device_id TEXT,
                        FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE SET NULL,
                        FOREIGN KEY(activity_type_id) REFERENCES activity_type(id) ON DELETE SET NULL,
                        FOREIGN KEY(source_candidate_id) REFERENCES activity_candidate(id) ON DELETE SET NULL,
                        FOREIGN KEY(supersedes_session_id) REFERENCES activity_session(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_start ON activity_session(start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_end ON activity_session(end_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_category_start ON activity_session(category_id, start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_type_start ON activity_session(activity_type_id, start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_deleted_start ON activity_session(deleted_at, start_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_source_candidate ON activity_session(source_candidate_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_supersedes ON activity_session(supersedes_session_id)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS activity_session_change (
                        id TEXT PRIMARY KEY NOT NULL,
                        session_id TEXT NOT NULL,
                        change_type TEXT NOT NULL,
                        changed_by TEXT NOT NULL,
                        changed_at INTEGER NOT NULL,
                        before_json TEXT,
                        after_json TEXT NOT NULL,
                        reason TEXT,
                        source_candidate_id TEXT,
                        FOREIGN KEY(session_id) REFERENCES activity_session(id) ON DELETE CASCADE,
                        FOREIGN KEY(source_candidate_id) REFERENCES activity_candidate(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_change_session_time ON activity_session_change(session_id, changed_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_change_type_time ON activity_session_change(change_type, changed_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_session_change_source_candidate ON activity_session_change(source_candidate_id)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS session_evidence (
                        id TEXT PRIMARY KEY NOT NULL,
                        session_id TEXT,
                        candidate_id TEXT,
                        detection_event_id TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        relationship TEXT NOT NULL,
                        reason TEXT,
                        FOREIGN KEY(session_id) REFERENCES activity_session(id) ON DELETE CASCADE,
                        FOREIGN KEY(candidate_id) REFERENCES activity_candidate(id) ON DELETE CASCADE,
                        FOREIGN KEY(detection_event_id) REFERENCES detection_event(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_evidence_session ON session_evidence(session_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_evidence_candidate ON session_evidence(candidate_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_evidence_detection ON session_evidence(detection_event_id)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS raw_source_event (
                        id TEXT PRIMARY KEY NOT NULL,
                        source_id TEXT NOT NULL,
                        external_id TEXT,
                        event_type TEXT NOT NULL,
                        observed_at INTEGER NOT NULL,
                        start_at INTEGER,
                        end_at INTEGER,
                        timezone_id TEXT,
                        payload_json TEXT NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        ingested_at INTEGER NOT NULL,
                        processed_at INTEGER,
                        FOREIGN KEY(source_id) REFERENCES data_source(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_source_observed ON raw_source_event(source_id, observed_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_processed ON raw_source_event(processed_at)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_raw_source_external ON raw_source_event(source_id, external_id) WHERE external_id IS NOT NULL")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS activity_aggregate_day (
                        date TEXT NOT NULL,
                        timezone_id TEXT NOT NULL,
                        category_id TEXT,
                        activity_type_id TEXT,
                        tag_id TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        session_count INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (date, timezone_id)
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_aggregate_date ON activity_aggregate_day(date)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_aggregate_category ON activity_aggregate_day(category_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_aggregate_type ON activity_aggregate_day(activity_type_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_aggregate_tag ON activity_aggregate_day(tag_id)")

                // Seed default data sources
                database.execSQL("""
                    INSERT OR IGNORE INTO data_source (id, type, name, enabled, permission_state, created_at, updated_at)
                    VALUES 
                        ('phone_activity_recognition', 'ANDROID_API', 'Activity Recognition', 1, 'UNKNOWN', ${System.currentTimeMillis()}, ${System.currentTimeMillis()}),
                        ('phone_geofence', 'ANDROID_API', 'Geofencing', 1, 'UNKNOWN', ${System.currentTimeMillis()}, ${System.currentTimeMillis()}),
                        ('health_connect', 'HEALTH_CONNECT', 'Health Connect', 1, 'UNKNOWN', ${System.currentTimeMillis()}, ${System.currentTimeMillis()}),
                        ('usage_stats', 'ANDROID_API', 'Usage Stats', 1, 'UNKNOWN', ${System.currentTimeMillis()}, ${System.currentTimeMillis()}),
                        ('manual', 'MANUAL', 'Manuelle Eingabe', 1, 'GRANTED', ${System.currentTimeMillis()}, ${System.currentTimeMillis()})
                """.trimIndent())

                // Seed default activity types
                database.execSQL("""
                    INSERT OR IGNORE INTO activity_type (id, name, default_category_id, is_system, properties_json)
                    VALUES
                        ('sleep', 'Schlaf', 'sleep', 1, '{"overlay": false}'),
                        ('work', 'Arbeit', 'work', 1, '{"overlay": false}'),
                        ('driving', 'Autofahren', 'transport', 1, '{"overlay": true}'),
                        ('fitness', 'Fitness', 'sport', 1, '{"overlay": false}'),
                        ('learning', 'Lernen', 'learning', 1, '{"overlay": false}'),
                        ('meditation', 'Meditation', 'health', 1, '{"overlay": false}'),
                        ('reading', 'Lesen', 'leisure', 1, '{"overlay": false}'),
                        ('digital', 'Digital', 'digital', 1, '{"overlay": true}'),
                        ('leisure', 'Freizeit', 'leisure', 1, '{"overlay": false}'),
                        ('transport', 'Transport', 'transport', 1, '{"overlay": true}'),
                        ('health', 'Gesundheit', 'health', 1, '{"overlay": false}'),
                        ('eating', 'Essen', 'leisure', 1, '{"overlay": false}'),
                        ('social', 'Soziales', 'relationships', 1, '{"overlay": false}'),
                        ('household', 'Haushalt', 'household', 1, '{"overlay": false}'),
                        ('other', 'Sonstiges', 'unknown', 1, '{"overlay": false}')
                """.trimIndent())

                // Migrate existing activity_session data: add default values for new columns
                // Note: Room handles column additions with default values automatically in most cases
                // but we ensure the status column is handled
                database.execSQL("""
                    UPDATE activity_session 
                    SET 
                        timezone_id = COALESCE(timezone_id, 'UTC'),
                        source_type = COALESCE(source_type, 'MANUAL'),
                        created_by = COALESCE(created_by, 'MANUAL'),
                        confidence = COALESCE(confidence, 1.0),
                        is_user_edited = COALESCE(is_user_edited, 0),
                        revision = COALESCE(revision, 1)
                    WHERE 1=1
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                ensurePlaceGeofenceV3(database)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS place_geofence_tag (
                        geofence_id TEXT NOT NULL,
                        tag_id TEXT NOT NULL,
                        PRIMARY KEY(geofence_id, tag_id),
                        FOREIGN KEY(geofence_id) REFERENCES place_geofence(id) ON DELETE CASCADE,
                        FOREIGN KEY(tag_id) REFERENCES tag(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_tag_tag_id ON place_geofence_tag(tag_id)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS trigger_event (
                        id TEXT PRIMARY KEY NOT NULL,
                        occurred_at INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        source TEXT NOT NULL,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        geofence_id TEXT,
                        detection_event_id TEXT,
                        metadata_json TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(geofence_id) REFERENCES place_geofence(id) ON DELETE SET NULL,
                        FOREIGN KEY(detection_event_id) REFERENCES detection_event(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_event_occurred_at ON trigger_event(occurred_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_event_type_occurred_at ON trigger_event(type, occurred_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_event_source_occurred_at ON trigger_event(source, occurred_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_event_geofence_id ON trigger_event(geofence_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_event_detection_event_id ON trigger_event(detection_event_id)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS automation_settings (
                        id TEXT PRIMARY KEY NOT NULL,
                        geofencing_enabled INTEGER NOT NULL DEFAULT 0,
                        background_capture_enabled INTEGER NOT NULL DEFAULT 0,
                        review_notifications_enabled INTEGER NOT NULL DEFAULT 0,
                        battery_saver_mode INTEGER NOT NULL DEFAULT 1,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT OR IGNORE INTO automation_settings (
                        id, geofencing_enabled, background_capture_enabled,
                        review_notifications_enabled, battery_saver_mode, updated_at
                    ) VALUES ('default', 0, 0, 0, 1, ${System.currentTimeMillis()})
                """.trimIndent())
                database.execSQL("""
                    INSERT OR IGNORE INTO data_source (id, type, name, enabled, permission_state, created_at, updated_at)
                    VALUES ('phone_geofencing', 'ANDROID_API', 'Geofencing', 1, 'UNKNOWN', ${System.currentTimeMillis()}, ${System.currentTimeMillis()})
                """.trimIndent())
            }
        }

        private fun ensurePlaceGeofenceV3(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS place_geofence (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    radius_meters REAL NOT NULL,
                    icon TEXT NOT NULL DEFAULT '📍',
                    color TEXT NOT NULL DEFAULT '#6366F1',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    activity_type_id TEXT,
                    category_id TEXT,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER,
                    FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE SET NULL,
                    FOREIGN KEY(activity_type_id) REFERENCES activity_type(id) ON DELETE SET NULL
                )
            """.trimIndent())
            addColumnIfMissing(database, "place_geofence", "icon", "TEXT NOT NULL DEFAULT '📍'")
            addColumnIfMissing(database, "place_geofence", "color", "TEXT NOT NULL DEFAULT '#6366F1'")
            addColumnIfMissing(database, "place_geofence", "activity_type_id", "TEXT")
            addColumnIfMissing(database, "place_geofence", "created_at", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(database, "place_geofence", "updated_at", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(database, "place_geofence", "deleted_at", "INTEGER")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_enabled ON place_geofence(enabled)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_category_id ON place_geofence(category_id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_activity_type_id ON place_geofence(activity_type_id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_deleted_at ON place_geofence(deleted_at)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_latitude_longitude ON place_geofence(latitude, longitude)")
        }

        private fun addColumnIfMissing(database: SupportSQLiteDatabase, table: String, column: String, definition: String) {
            database.query("PRAGMA table_info($table)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) return
                }
            }
            database.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aevum_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}