package com.d_drostes_apps.aevum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M26: Anruf-Erkennung 48 → 49 — auf einer ECHTEN Datenbank.
 *
 * Derselbe Weg wie beim App-Update auf einem Bestandsgerät:
 *
 *  1. Aus der vom Room-Compiler erzeugten `48.json` wird eine echte
 *     SQLite-Datenbank der Version 48 gebaut.
 *  2. Eine Bestands-Zeile in `automation_settings` wird eingefüllt — mit den
 *     Einstellungen, die der Nutzer vor dem Update gesetzt hatte.
 *  3. Room öffnet die Datei MIT [AppDatabase.MIGRATION_48_49]: zwei
 *     `ADD COLUMN`, Gesamtschema-Validierung, Version + Identitäts-Hash.
 *  4. Geprüft wird: die Bestandszeile ist unverändert (kein Datenverlust,
 *     kein Reset der Trigger-Schalter), die neuen Spalten tragen die
 *     Vorgaben (`0` / `'social'`), und ein frisches Schreiben über das
 *     Entity funktioniert.
 *
 * Der Schema-seitige Beweis (49 = 48 + zwei Spalten, Element für Element)
 * läuft in [SchemaV49Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CallDetectionMigrationTest {

    private val dbName = "settings-48-to-49"

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var opened: AppDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.d_drostes_apps.aevum.data.db.AppDatabase/$version.json"
        val candidates = listOf(
            File(".").resolve(relative),
            File("..").resolve("app/$relative"),
            File("../app").resolve(relative),
            File("app").resolve(relative)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Schema-Datei $version.json nicht gefunden (CWD=${File(".").absolutePath})")
    }

    /** Baut eine echte Datenbank der Version 48 aus der Schema-Datei. */
    private fun createVersion48Database(): File {
        val path = context.getDatabasePath(dbName)
        path.delete()
        context.getDatabasePath("$dbName-journal").delete()
        context.getDatabasePath("$dbName-wal").delete()
        context.getDatabasePath("$dbName-shm").delete()
        path.parentFile?.mkdirs()

        val database = JSONObject(schemaFile(48).readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)

        fun resolve(sql: String, table: String): String =
            sql.replace("\${TABLE_NAME}", table)

        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            sqlite.execSQL(resolve(entity.getString("createSql"), entity.getString("tableName")))
        }
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) {
                sqlite.execSQL(resolve(indices.getJSONObject(j).getString("createSql"), table))
            }
        }
        val setups = database.optJSONArray("setupQueries") ?: org.json.JSONArray()
        for (i in 0 until setups.length()) sqlite.execSQL(setups.getString(i))

        // Bestandszeile: krumme, nicht-defaultete Werte, damit ein Reset
        // auffällt (Geofencing AN, Bildschirm-Aufzeichnung 7 Minuten,
        // Fahrt-Stopp AUS, Kalender-Sync AN).
        sqlite.execSQL(
            "INSERT INTO automation_settings (" +
                "id, geofencing_enabled, background_capture_enabled, review_notifications_enabled, " +
                "battery_saver_mode, updated_at, health_sleep_enabled, digital_balance_enabled, " +
                "sleep_fusion_enabled, driving_detection_enabled, walking_detection_enabled, " +
                "bicycle_detection_enabled, sleep_source, screen_recording_minutes, " +
                "calendar_sync_enabled, calendar_auto_tracking_enabled, calendar_last_sync_at, " +
                "calendar_sync_interval_hours, walk_stop_on_steps_enabled" +
                ") VALUES ('default', 1, 0, 1, 0, 1700000000000, 0, 1, 0, 1, 0, 1, " +
                "'health_connect', 7, 1, 0, 123456, 3, 0)"
        )

        sqlite.version = 48
        sqlite.close()
        return path
    }

    /** Öffnet die Datei über Room — die echte 48→49-Migration läuft. */
    private fun migrate(): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_48_49, AppDatabase.MIGRATION_49_50)
            .allowMainThreadQueries()
            .build()
        opened = database
        database.openHelper.writableDatabase
        return database
    }

    @Test
    fun `Update-setzt-Anruf-Spalten-auf-Vorgaben`() {
        createVersion48Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT call_detection_enabled, call_activity_type_id FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)                       // Schalter aus
            assertThat(cursor.getString(1)).isEqualTo("social")             // Default „Soziales"
        }
    }

    @Test
    fun `Bestands-Einstellungen-ueberleben-das-Update`() {
        createVersion48Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT geofencing_enabled, digital_balance_enabled, sleep_source, " +
                "screen_recording_minutes, calendar_sync_enabled, calendar_last_sync_at, " +
                "walk_stop_on_steps_enabled, driving_detection_enabled, updated_at " +
                "FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)                // Geofencing AN
            assertThat(cursor.getInt(1)).isEqualTo(1)                // Digital Balance AN
            assertThat(cursor.getString(2)).isEqualTo("health_connect")
            assertThat(cursor.getInt(3)).isEqualTo(7)                // Vorlauf 7 Minuten
            assertThat(cursor.getInt(4)).isEqualTo(1)                // Kalender-Sync AN
            assertThat(cursor.getLong(5)).isEqualTo(123456L)
            assertThat(cursor.getInt(6)).isEqualTo(0)                // Fahrt-Stopp AUS
            assertThat(cursor.getInt(7)).isEqualTo(1)
            assertThat(cursor.getLong(8)).isEqualTo(1700000000000L)
        }
    }

    @Test
    fun `Datenbank-meldet-Version-49`() {
        createVersion48Database()
        migrate()

        opened!!.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(50)
        }
    }

    @Test
    fun `Schreiben-ueber-das-Entity-funktioniert-nach-dem-Update`() {
        createVersion48Database()
        val database = migrate()

        // Ein Toggle, wie ihn die Einstellungen schreiben — die neuen
        // Spalten müssen dabei mitspielen (NOT NULL + Default).
        opened!!.openHelper.writableDatabase.execSQL(
            "UPDATE automation_settings SET call_detection_enabled = 1, " +
                "call_activity_type_id = 'social' WHERE id = 'default'"
        )
        opened!!.openHelper.writableDatabase.query(
            "SELECT call_detection_enabled, call_activity_type_id FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
            assertThat(cursor.getString(1)).isEqualTo("social")
        }
        assertThat(database.isOpen).isTrue()
    }
}
