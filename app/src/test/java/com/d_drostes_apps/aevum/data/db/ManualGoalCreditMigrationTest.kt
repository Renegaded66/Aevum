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
 * M35: v51 → v52 auf einer ECHTEN SQLite-Datenbank.
 *
 * Geprüft wird der Update-Pfad eines Bestandsgeräts:
 *  - bestehende Check-ins bleiben unverändert (count=1) und bekommen
 *    duration_ms=0 sowie activity_type_id=NULL (altes Verhalten),
 *  - automation_settings bekommt drive_activity_type_id='driving',
 *  - alle Bestandswerte (Geofencing, Bildschirm-Vorlauf, Check-in-Datum)
 *    überleben,
 *  - die neuen Spalten sind über SQL beschreibbar.
 *
 * Der Schema-seitige Element-für-Element-Beweis läuft in [SchemaV52Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ManualGoalCreditMigrationTest {

    private val dbName = "m35-51-to-52"
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

    private fun database(version: Int): JSONObject =
        JSONObject(schemaFile(version).readText()).getJSONObject("database")

    private fun createVersion51Database(): File {
        val path = context.getDatabasePath(dbName)
        listOf("", "-journal", "-wal", "-shm").forEach {
            context.getDatabasePath("$dbName$it").delete()
        }
        path.parentFile?.mkdirs()

        val db = database(51)
        val entities = db.getJSONArray("entities")
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)

        fun resolve(sql: String, table: String): String = sql.replace("\${TABLE_NAME}", table)

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
        val setups = db.optJSONArray("setupQueries") ?: org.json.JSONArray()
        for (i in 0 until setups.length()) sqlite.execSQL(setups.getString(i))

        insertLegacyRows(sqlite)
        sqlite.version = 51
        sqlite.close()
        return path
    }

    private fun insertLegacyRows(sqlite: android.database.sqlite.SQLiteDatabase) {
        // Bestands-Ziel: „5× meditieren pro Woche", manuell.
        sqlite.execSQL(
            "INSERT INTO goal (id, title, type, period, target_value, target_unit, " +
                "tracking_mode, schedule_days, start_at, status, created_at, updated_at) " +
                "VALUES ('g1', '5x meditieren', 'COUNT', 'WEEKLY', 5, 'COUNT', " +
                "'MANUAL', 127, 1700000000000, 'ACTIVE', 1700000000000, 1700000000000)"
        )
        // Bestands-Check-in (vor M35: nur count).
        sqlite.execSQL(
            "INSERT INTO goal_checkin (id, goal_id, timestamp_ms, date_key, count) " +
                "VALUES ('c1', 'g1', 1700000000000, '2026-07-20', 1)"
        )
        // Bestands-Einstellungen (krumme Werte, damit ein Reset auffällt).
        sqlite.execSQL(
            "INSERT INTO automation_settings (id, geofencing_enabled, background_capture_enabled, " +
                "review_notifications_enabled, battery_saver_mode, updated_at, " +
                "screen_recording_minutes, screen_recording_activity_type_id) " +
                "VALUES ('default', 1, 0, 0, 1, 1700000000000, 7, 'reading')"
        )
    }

    private fun migrate(): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        opened = database
        database.openHelper.writableDatabase
        return database
    }

    @Test
    fun `Update-erhaelt-den-Bestands-Checkin-und-setzt-Zeit-auf-null`() {
        createVersion51Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT goal_id, date_key, count, activity_type_id, duration_ms " +
                "FROM goal_checkin WHERE id = 'c1'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("g1")
            assertThat(cursor.getString(1)).isEqualTo("2026-07-20")
            assertThat(cursor.getInt(2)).isEqualTo(1)
            assertThat(cursor.isNull(3)).isTrue()
            assertThat(cursor.getLong(4)).isEqualTo(0L)
        }
    }

    @Test
    fun `Update-setzt-Fahrt-Activity-auf-driving-und-erhaelt-Einstellungen`() {
        createVersion51Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT geofencing_enabled, screen_recording_minutes, " +
                "screen_recording_activity_type_id, drive_activity_type_id " +
                "FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
            assertThat(cursor.getInt(1)).isEqualTo(7)
            assertThat(cursor.getString(2)).isEqualTo("reading")
            assertThat(cursor.getString(3)).isEqualTo("driving")
        }
    }

    @Test
    fun `Datenbank-meldet-Version-53`() {
        createVersion51Database()
        migrate()

        opened!!.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(53)
        }
    }

    @Test
    fun `neue-Spalten-sind-nach-dem-Update-beschreibbar`() {
        createVersion51Database()
        migrate()

        opened!!.openHelper.writableDatabase.execSQL(
            "UPDATE goal_checkin SET activity_type_id = 'meditation', duration_ms = 300000 " +
                "WHERE id = 'c1'"
        )
        opened!!.openHelper.writableDatabase.execSQL(
            "UPDATE automation_settings SET drive_activity_type_id = 'unterwegs' WHERE id = 'default'"
        )
        opened!!.openHelper.writableDatabase.query(
            "SELECT activity_type_id, duration_ms FROM goal_checkin WHERE id = 'c1'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("meditation")
            assertThat(cursor.getLong(1)).isEqualTo(300_000L)
        }
        opened!!.openHelper.writableDatabase.query(
            "SELECT drive_activity_type_id FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("unterwegs")
        }
    }
}
