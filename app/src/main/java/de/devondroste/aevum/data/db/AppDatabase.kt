package de.devondroste.aevum.data.db

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
        ActivityAggregateDay::class,
        GeofenceEventLogEntry::class
    ],
    version = 13,
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
    abstract fun geofenceEventLogDao(): GeofenceEventLogDao
    abstract fun sessionEvidenceDao(): SessionEvidenceDao
    abstract fun activityAggregateDayDao(): ActivityAggregateDayDao

    companion object {

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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_data_source_type` ON `data_source` (`type`)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_source_event_source_id_observed_at` ON `raw_source_event` (`source_id`, `observed_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_source_event_processed_at` ON `raw_source_event` (`processed_at`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_source_event_source_id_external_id` ON `raw_source_event` (`source_id`, `external_id`)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_kind_start_at` ON `detection_event` (`kind`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_source_id_start_at` ON `detection_event` (`source_id`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_raw_event_id` ON `detection_event` (`raw_event_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_place_id` ON `detection_event` (`place_id`)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_type_is_system` ON `activity_type` (`is_system`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_type_default_category_id` ON `activity_type` (`default_category_id`)")

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
                        source_candidate_id TEXT,
                        FOREIGN KEY(suggested_category_id) REFERENCES category(id) ON DELETE SET NULL,
                        FOREIGN KEY(activity_type_id) REFERENCES activity_type(id) ON DELETE SET NULL,
                        FOREIGN KEY(resolved_session_id) REFERENCES activity_session(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_status_created_at` ON `activity_candidate` (`status`, `created_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_activity_type_id` ON `activity_candidate` (`activity_type_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_resolved_session_id` ON `activity_candidate` (`resolved_session_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_start_at_end_at` ON `activity_candidate` (`start_at`, `end_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_suggested_category_id` ON `activity_candidate` (`suggested_category_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_source_candidate_id` ON `activity_candidate` (`source_candidate_id`)")


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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_start_at` ON `activity_session` (`start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_end_at` ON `activity_session` (`end_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_category_id_start_at` ON `activity_session` (`category_id`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_activity_type_id_start_at` ON `activity_session` (`activity_type_id`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_source_type_start_at` ON `activity_session` (`source_type`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_deleted_at_start_at` ON `activity_session` (`deleted_at`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_source_candidate_id` ON `activity_session` (`source_candidate_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_supersedes_session_id` ON `activity_session` (`supersedes_session_id`)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_change_session_id_changed_at` ON `activity_session_change` (`session_id`, `changed_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_change_change_type_changed_at` ON `activity_session_change` (`change_type`, `changed_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_change_source_candidate_id` ON `activity_session_change` (`source_candidate_id`)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_session_evidence_session_id_detection_event_id` ON `session_evidence` (`session_id`, `detection_event_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_session_evidence_candidate_id_detection_event_id` ON `session_evidence` (`candidate_id`, `detection_event_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_session_evidence_detection_event_id` ON `session_evidence` (`detection_event_id`)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_date` ON `activity_aggregate_day` (`date`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_timezone_id` ON `activity_aggregate_day` (`timezone_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_category_id` ON `activity_aggregate_day` (`category_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_activity_type_id` ON `activity_aggregate_day` (`activity_type_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_tag_id` ON `activity_aggregate_day` (`tag_id`)")

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE automation_settings ADD COLUMN health_sleep_enabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE automation_settings ADD COLUMN digital_balance_enabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS geofence_event_log (
                        id TEXT NOT NULL PRIMARY KEY,
                        occurred_at INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        geofence_id TEXT,
                        geofence_name TEXT,
                        detail TEXT NOT NULL,
                        success INTEGER NOT NULL DEFAULT 1,
                        lat REAL,
                        lon REAL,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // Drop any previously created indices with wrong prefix (from b92e661)
                database.execSQL("DROP INDEX IF EXISTS idx_geofence_event_log_occurred_at")
                database.execSQL("DROP INDEX IF EXISTS idx_geofence_event_log_category")
                database.execSQL("DROP INDEX IF EXISTS idx_geofence_event_log_event_type")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofence_event_log_occurred_at ON geofence_event_log(occurred_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofence_event_log_category ON geofence_event_log(category)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofence_event_log_event_type ON geofence_event_log(event_type)")
            }
        }

        /**
         * M8.2.2: Repair geofence_event_log schema after b92e661 created
         * wrong index names (idx_* instead of index_*).
         * Also removes DEFAULT clauses that Room doesn't expect.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Drop wrong-named indices from the buggy v5 migration
                database.execSQL("DROP INDEX IF EXISTS idx_geofence_event_log_occurred_at")
                database.execSQL("DROP INDEX IF EXISTS idx_geofence_event_log_category")
                database.execSQL("DROP INDEX IF EXISTS idx_geofence_event_log_event_type")
                // Recreate table without DEFAULT clauses to match Room entity exactly
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS geofence_event_log_fixed (
                        id TEXT NOT NULL PRIMARY KEY,
                        occurred_at INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        geofence_id TEXT,
                        geofence_name TEXT,
                        detail TEXT NOT NULL,
                        success INTEGER NOT NULL,
                        lat REAL,
                        lon REAL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("INSERT INTO geofence_event_log_fixed (id, occurred_at, category, event_type, geofence_id, geofence_name, detail, success, lat, lon, created_at) SELECT id, occurred_at, category, event_type, geofence_id, geofence_name, detail, success, lat, lon, created_at FROM geofence_event_log")
                database.execSQL("DROP TABLE geofence_event_log")
                database.execSQL("ALTER TABLE geofence_event_log_fixed RENAME TO geofence_event_log")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofence_event_log_occurred_at ON geofence_event_log(occurred_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofence_event_log_category ON geofence_event_log(category)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_geofence_event_log_event_type ON geofence_event_log(event_type)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // M9: Live Activity Recording — extend activity_session with live status fields
                database.execSQL("ALTER TABLE activity_session ADD COLUMN session_status TEXT NOT NULL DEFAULT 'FINISHED'")
                database.execSQL("ALTER TABLE activity_session ADD COLUMN total_paused_ms INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE activity_session ADD COLUMN current_pause_started_at INTEGER")
                database.execSQL("ALTER TABLE activity_session ADD COLUMN pause_segments_json TEXT")
                database.execSQL("ALTER TABLE activity_session ADD COLUMN note TEXT")
                // Index for quickly finding the active live session
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_session_status` ON `activity_session` (`session_status`)")
                // Migrate existing open sessions (end_at IS NULL) to RUNNING
                database.execSQL("UPDATE activity_session SET session_status = 'RUNNING' WHERE end_at IS NULL AND deleted_at IS NULL")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // M11: add optional automation rules to place_geofence
                database.execSQL("ALTER TABLE place_geofence ADD COLUMN auto_start_activity_type_id TEXT")
                database.execSQL("ALTER TABLE place_geofence ADD COLUMN auto_stop_enabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // M12.1: add source_trigger_id to activity_session for auto-start traceability
                database.execSQL("ALTER TABLE activity_session ADD COLUMN source_trigger_id TEXT")
            }
        }

        /**
         * M12.0.2: Bereinigt alle veralteten idx_* Indizes, die von der
         * ursprünglichen MIGRATION_1_2 (vor der Korrektur) erstellt wurden.
         *
         * Auf Bestandsgeräten, die von v1 bis v12 migriert wurden, existieren
         * noch Indizes mit dem alten idx_* Namensschema. Room's Schema-Validierung
         * bei v12 erwartet jedoch index_* Namen (index_<table>_<col>).
         *
         * Da die idx_* Indizes nicht mit DROP INDEX IF EXISTS in späteren
         * Migrationen bereinigt wurden, persistieren sie bis v12 und führen
         * zu einer IllegalStateException beim ersten DB-Zugriff.
         *
         * Diese Migration droppt alle bekannten idx_* Indizes, die von der
         * ursprünglichen MIGRATION_1_2 erstellt wurden. Die korrekten
         * index_* Indizes werden von Room automatisch beim Validieren
         * der v13-Schema-Erwartung erstellt (falls sie fehlen).
         *
         * Zusätzlich wird der Index idx_session_status gedroppt, der von
         * MIGRATION_6_7 (vor M9.0.1 Korrektur) erstellt wurde.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // === data_source ===
                database.execSQL("DROP INDEX IF EXISTS `idx_data_source_type`")
                database.execSQL("DROP INDEX IF EXISTS `idx_data_source_enabled`")

                // === raw_source_event ===
                database.execSQL("DROP INDEX IF EXISTS `idx_raw_source_observed`")
                database.execSQL("DROP INDEX IF EXISTS `idx_raw_processed`")
                database.execSQL("DROP INDEX IF EXISTS `idx_raw_source_external`")

                // === detection_event ===
                database.execSQL("DROP INDEX IF EXISTS `idx_detection_kind_time`")
                database.execSQL("DROP INDEX IF EXISTS `idx_detection_source_time`")
                database.execSQL("DROP INDEX IF EXISTS `idx_detection_raw_event`")

                // === activity_type ===
                database.execSQL("DROP INDEX IF EXISTS `idx_activity_type_system`")
                database.execSQL("DROP INDEX IF EXISTS `idx_activity_type_category`")

                // === activity_candidate ===
                database.execSQL("DROP INDEX IF EXISTS `idx_candidate_status_time`")
                database.execSQL("DROP INDEX IF EXISTS `idx_candidate_type_time`")
                database.execSQL("DROP INDEX IF EXISTS `idx_candidate_resolved`")

                // === activity_session ===
                database.execSQL("DROP INDEX IF EXISTS `idx_session_start`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_end`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_category_start`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_type_start`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_deleted_start`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_source_candidate`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_supersedes`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_status`")

                // === activity_session_change ===
                database.execSQL("DROP INDEX IF EXISTS `idx_session_change_session_time`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_change_type_time`")
                database.execSQL("DROP INDEX IF EXISTS `idx_session_change_source_candidate`")

                // === session_evidence ===
                database.execSQL("DROP INDEX IF EXISTS `idx_evidence_session`")
                database.execSQL("DROP INDEX IF EXISTS `idx_evidence_candidate`")
                database.execSQL("DROP INDEX IF EXISTS `idx_evidence_detection`")

                // === activity_aggregate_day ===
                database.execSQL("DROP INDEX IF EXISTS `idx_aggregate_date`")
                database.execSQL("DROP INDEX IF EXISTS `idx_aggregate_category`")
                database.execSQL("DROP INDEX IF EXISTS `idx_aggregate_type`")
                database.execSQL("DROP INDEX IF EXISTS `idx_aggregate_tag`")

                // === geofence_event_log ===
                database.execSQL("DROP INDEX IF EXISTS `idx_geofence_event_log_occurred_at`")
                database.execSQL("DROP INDEX IF EXISTS `idx_geofence_event_log_category`")
                database.execSQL("DROP INDEX IF EXISTS `idx_geofence_event_log_event_type`")

                // === place_geofence: auto_stop_enabled DEFAULT-Mismatch reparieren ===
                // M12.0.2: v11 hat auto_stop_enabled als NOT NULL ohne DEFAULT.
                // v12/v13 erwartet DEFAULT 0. SQLite kann den DEFAULT nicht per
                // ALTER TABLE ändern — die Tabelle muss neu erstellt werden.
                database.execSQL("DROP INDEX IF EXISTS `index_place_geofence_enabled`")
                database.execSQL("DROP INDEX IF EXISTS `index_place_geofence_category_id`")
                database.execSQL("DROP INDEX IF EXISTS `index_place_geofence_activity_type_id`")
                database.execSQL("DROP INDEX IF EXISTS `index_place_geofence_deleted_at`")
                database.execSQL("DROP INDEX IF EXISTS `index_place_geofence_latitude_longitude`")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS place_geofence_fixed (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        radius_meters REAL NOT NULL,
                        icon TEXT NOT NULL,
                        color TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        activity_type_id TEXT,
                        category_id TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        auto_start_activity_type_id TEXT,
                        auto_stop_enabled INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE SET NULL,
                        FOREIGN KEY(activity_type_id) REFERENCES activity_type(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO place_geofence_fixed (
                        id, name, latitude, longitude, radius_meters, icon, color, enabled,
                        activity_type_id, category_id, created_at, updated_at, deleted_at,
                        auto_start_activity_type_id, auto_stop_enabled
                    )
                    SELECT
                        id, name, latitude, longitude, radius_meters, icon, color, enabled,
                        activity_type_id, category_id, created_at, updated_at, deleted_at,
                        auto_start_activity_type_id, COALESCE(auto_stop_enabled, 0)
                    FROM place_geofence
                """.trimIndent())
                database.execSQL("DROP TABLE place_geofence")
                database.execSQL("ALTER TABLE place_geofence_fixed RENAME TO place_geofence")

                // place_geofence Indizes nach Neu-Erstellung
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_enabled` ON `place_geofence` (`enabled`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_category_id` ON `place_geofence` (`category_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_activity_type_id` ON `place_geofence` (`activity_type_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_deleted_at` ON `place_geofence` (`deleted_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_latitude_longitude` ON `place_geofence` (`latitude`, `longitude`)")

                // Stelle sicher, dass alle korrekten index_* Indizes existieren.
                // Room erstellt fehlende Indizes bei der Schema-Validierung nicht
                // automatisch — wir müssen sie explizit anlegen.
                // activity_session
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_start_at` ON `activity_session` (`start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_end_at` ON `activity_session` (`end_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_category_id_start_at` ON `activity_session` (`category_id`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_activity_type_id_start_at` ON `activity_session` (`activity_type_id`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_source_type_start_at` ON `activity_session` (`source_type`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_deleted_at_start_at` ON `activity_session` (`deleted_at`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_source_candidate_id` ON `activity_session` (`source_candidate_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_supersedes_session_id` ON `activity_session` (`supersedes_session_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_session_status` ON `activity_session` (`session_status`)")

                // activity_candidate
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_start_at_end_at` ON `activity_candidate` (`start_at`, `end_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_status_created_at` ON `activity_candidate` (`status`, `created_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_resolved_session_id` ON `activity_candidate` (`resolved_session_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_suggested_category_id` ON `activity_candidate` (`suggested_category_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_activity_type_id` ON `activity_candidate` (`activity_type_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_candidate_source_candidate_id` ON `activity_candidate` (`source_candidate_id`)")

                // data_source
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_data_source_type` ON `data_source` (`type`)")

                // raw_source_event
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_source_event_source_id_observed_at` ON `raw_source_event` (`source_id`, `observed_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_source_event_processed_at` ON `raw_source_event` (`processed_at`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_source_event_source_id_external_id` ON `raw_source_event` (`source_id`, `external_id`)")

                // detection_event
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_kind_start_at` ON `detection_event` (`kind`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_source_id_start_at` ON `detection_event` (`source_id`, `start_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_raw_event_id` ON `detection_event` (`raw_event_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_detection_event_place_id` ON `detection_event` (`place_id`)")

                // activity_type
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_type_is_system` ON `activity_type` (`is_system`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_type_default_category_id` ON `activity_type` (`default_category_id`)")

                // activity_session_change
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_change_session_id_changed_at` ON `activity_session_change` (`session_id`, `changed_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_change_change_type_changed_at` ON `activity_session_change` (`change_type`, `changed_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_change_source_candidate_id` ON `activity_session_change` (`source_candidate_id`)")

                // session_evidence
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_session_evidence_session_id_detection_event_id` ON `session_evidence` (`session_id`, `detection_event_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_session_evidence_candidate_id_detection_event_id` ON `session_evidence` (`candidate_id`, `detection_event_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_session_evidence_detection_event_id` ON `session_evidence` (`detection_event_id`)")

                // activity_aggregate_day
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_date` ON `activity_aggregate_day` (`date`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_timezone_id` ON `activity_aggregate_day` (`timezone_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_category_id` ON `activity_aggregate_day` (`category_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_activity_type_id` ON `activity_aggregate_day` (`activity_type_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_aggregate_day_tag_id` ON `activity_aggregate_day` (`tag_id`)")

                // geofence_event_log
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_geofence_event_log_occurred_at` ON `geofence_event_log`(`occurred_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_geofence_event_log_category` ON `geofence_event_log`(`category`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_geofence_event_log_event_type` ON `geofence_event_log`(`event_type`)")

                // trigger_event
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_event_occurred_at` ON `trigger_event`(`occurred_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_event_type_occurred_at` ON `trigger_event`(`type`, `occurred_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_event_source_occurred_at` ON `trigger_event`(`source`, `occurred_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_event_geofence_id` ON `trigger_event`(`geofence_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_event_detection_event_id` ON `trigger_event`(`detection_event_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_event_anchor_quality` ON `trigger_event`(`anchor_quality`)")

                // M12.0.2: activity_recognition DataSource seeden.
                // ActivityRecognitionWorker fügt RawSourceEvents mit sourceId="activity_recognition"
                // ein. Ohne diesen Eintrag verletzt das die FK-Constraint.
                database.execSQL("""
                    INSERT OR IGNORE INTO data_source (id, type, name, enabled, permission_state, created_at, updated_at)
                    VALUES ('activity_recognition', 'ANDROID_API', 'Activity Recognition', 1, 'UNKNOWN', ${System.currentTimeMillis()}, ${System.currentTimeMillis()})
                """.trimIndent())
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // M10.1: Add trigger quality + suppressed count for anchor-aware
                // session creation. Defaults ensure existing rows are valid.
                database.execSQL("ALTER TABLE trigger_event ADD COLUMN anchor_quality TEXT NOT NULL DEFAULT 'MEDIUM'")
                database.execSQL("ALTER TABLE trigger_event ADD COLUMN suppressed_count INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_event_anchor_quality ON trigger_event(anchor_quality)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // M9.2: Add favorite flag to activity_type
                database.execSQL("ALTER TABLE activity_type ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // M9.0.1: Repair index name from M9 (idx_session_status → Room-expected name)
                database.execSQL("DROP INDEX IF EXISTS idx_session_status")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_session_session_status` ON `activity_session` (`session_status`)")
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
                    icon TEXT NOT NULL,
                    color TEXT NOT NULL,
                    enabled INTEGER NOT NULL,
                    activity_type_id TEXT,
                    category_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
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
            rebuildPlaceGeofenceIfMissingActivityTypeForeignKey(database)
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_enabled ON place_geofence(enabled)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_category_id ON place_geofence(category_id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_activity_type_id ON place_geofence(activity_type_id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_deleted_at ON place_geofence(deleted_at)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_latitude_longitude ON place_geofence(latitude, longitude)")
        }

        private fun rebuildPlaceGeofenceIfMissingActivityTypeForeignKey(database: SupportSQLiteDatabase) {
            var hasActivityTypeForeignKey = false
            database.query("PRAGMA foreign_key_list(place_geofence)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("table")) == "activity_type") {
                        hasActivityTypeForeignKey = true
                    }
                }
            }
            if (hasActivityTypeForeignKey) return

            database.execSQL("DROP INDEX IF EXISTS index_place_geofence_enabled")
            database.execSQL("DROP INDEX IF EXISTS index_place_geofence_category_id")
            database.execSQL("DROP INDEX IF EXISTS index_place_geofence_activity_type_id")
            database.execSQL("DROP INDEX IF EXISTS index_place_geofence_deleted_at")
            database.execSQL("DROP INDEX IF EXISTS index_place_geofence_latitude_longitude")
            database.execSQL("""
                CREATE TABLE place_geofence_new (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    radius_meters REAL NOT NULL,
                    icon TEXT NOT NULL,
                    color TEXT NOT NULL,
                    enabled INTEGER NOT NULL,
                    activity_type_id TEXT,
                    category_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE SET NULL,
                    FOREIGN KEY(activity_type_id) REFERENCES activity_type(id) ON DELETE SET NULL
                )
            """.trimIndent())
            database.execSQL("""
                INSERT INTO place_geofence_new (
                    id, name, latitude, longitude, radius_meters, icon, color, enabled,
                    activity_type_id, category_id, created_at, updated_at, deleted_at
                )
                SELECT
                    id, name, latitude, longitude, radius_meters, icon, color, enabled,
                    activity_type_id, category_id, created_at, updated_at, deleted_at
                FROM place_geofence
            """.trimIndent())
            database.execSQL("DROP TABLE place_geofence")
            database.execSQL("ALTER TABLE place_geofence_new RENAME TO place_geofence")
        }

        private fun addColumnIfMissing(database: SupportSQLiteDatabase, table: String, column: String, definition: String) {
            database.query("PRAGMA table_info($table)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) return
                }
            }
            database.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }
}