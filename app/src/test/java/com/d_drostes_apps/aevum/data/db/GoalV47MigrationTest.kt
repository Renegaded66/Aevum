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
 * M20: Ziel-Architektur 46 → 47 — auf einer ECHTEN Datenbank.
 *
 * Der Weg ist derselbe wie bei einem Bestandsgerät beim App-Update:
 *
 *  1. Aus der vom Room-Compiler erzeugten Schema-Datei 46.json wird eine
 *     echte SQLite-Datenbank der Version 46 gebaut (dieselbe Technik wie
 *     MigrationTestHelper; der braucht ein Gerät, dieser Test läuft als
 *     Robolectric-Test auf der JVM).
 *  2. Ein „Bestands-Ziel" wird eingefüllt — genau mit den v46-Spalten
 *     (M18.138-Format, OHNE die M20-Spalten).
 *  3. Room öffnet die Datei MIT [AppDatabase.MIGRATION_46_47] — die echte
 *     Upgrade-Kette läuft (4 × ADD COLUMN, CREATE TABLE goal_checkin,
 *     2 Indizes, Gesamtschema validieren, Version + Hash fortschreiben).
 *  4. Geprüft wird: Bestandsziele verhalten sich unverändert
 *     (tracking_mode=AUTO, schedule_days=127, source_kind/accent NULL),
 *     Check-ins funktionieren inkl. CASCADE beim Ziel-Löschen, und die
 *     Datenbank meldet Version 47 mit dem 47er-Identitäts-Hash.
 *
 * Der Schema-seitige Beweis (47 = 46 + M20-Spalten + goal_checkin,
 * Element für Element) läuft in [SchemaV47Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GoalV47MigrationTest {

    private val dbName = "goal-46-to-47"

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

    /** Baut eine echte Datenbank der Version 46 aus der Schema-Datei. */
    private fun createVersion46Database(): File {
        val path = context.getDatabasePath(dbName)
        path.delete()
        context.getDatabasePath("$dbName-journal").delete()
        context.getDatabasePath("$dbName-wal").delete()
        context.getDatabasePath("$dbName-shm").delete()

        val database = JSONObject(schemaFile(46).readText()).getJSONObject("database")
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

        // Altbestand: ein M18.138-Ziel (v46-Spalten, ohne M20-Felder).
        sqlite.execSQL(
            "INSERT INTO goal " +
                "(id, title, category_id, tag_id, activity_type_id, type, period, " +
                "target_value, target_unit, filter_json, start_at, end_at, status, created_at, updated_at) " +
                "VALUES ('goal-alt-1', '8h Schlaf', NULL, NULL, 'sleep', 'AT_LEAST', 'DAILY', " +
                "8.0, 'HOURS', NULL, 1, NULL, 'ACTIVE', 1, 1)"
        )

        sqlite.version = 46
        sqlite.close()
        return path
    }

    /** Öffnet die Datei über Room — die echte 46→47-Migration läuft. */
    private fun migrate(): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_43_44,
                AppDatabase.MIGRATION_44_45,
                AppDatabase.MIGRATION_45_46,
                AppDatabase.MIGRATION_46_47,
                // M23/M26: Room validiert gegen das AKTUELLE Schema (v49) —
                // die Kette muss bis zur aktuellen Version durchlaufen.
                AppDatabase.MIGRATION_47_48,
                // M26: Kette bis zur aktuellen v49 (ADD COLUMN in automation_settings).
                AppDatabase.MIGRATION_48_49
            )
            .allowMainThreadQueries()
            .build()
        opened = database
        database.openHelper.writableDatabase
        return database
    }

    private fun tableNames(): Set<String> {
        val db = opened!!.openHelper.writableDatabase
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names
    }

    @Test
    fun `Update-erzeugt-goal_checkin-und-erhaelt-alle-anderen-Tabellen`() {
        createVersion46Database()
        migrate()

        assertThat(tableNames()).contains("goal_checkin")
        // Erwartung direkt aus der 47er-Schema-Datei: alle 39 Tabellen.
        val database = JSONObject(schemaFile(47).readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val expected = (0 until entities.length())
            .map { entities.getJSONObject(it).getString("tableName") }
        assertThat(expected).hasSize(39) // 38 aus v46 + goal_checkin
        assertThat(tableNames()).containsAtLeastElementsIn(expected)
    }

    @Test
    fun `Bestandsziele-verhalten-sich-unveraendert - AUTO-alle-Tage-keine-Quelle`() {
        createVersion46Database()
        migrate()

        val db = opened!!.openHelper.writableDatabase
        db.query(
            "SELECT tracking_mode, schedule_days, source_kind, accent FROM goal WHERE id = 'goal-alt-1'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("AUTO")
            assertThat(cursor.getInt(1)).isEqualTo(127)
            assertThat(cursor.isNull(2)).isTrue()
            assertThat(cursor.isNull(3)).isTrue()
        }
    }

    @Test
    fun `Check-ins-funktionieren - Ziel-Loeschen-nimmt-sie-per-CASCADE-mit`() {
        createVersion46Database()
        migrate()

        val db = opened!!.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO goal_checkin (id, goal_id, timestamp_ms, date_key, count) " +
                "VALUES ('chk-1', 'goal-alt-1', 123456, '2026-10-17', 1)"
        )
        db.query("SELECT COUNT(*) FROM goal_checkin").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        db.execSQL("DELETE FROM goal WHERE id = 'goal-alt-1'")
        db.query("SELECT COUNT(*) FROM goal_checkin").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun `nach-der-Migration-fuehrt-Room-die-Datenbank-als-aktuelle-Version-49`() {
        createVersion46Database()
        migrate()

        val db = opened!!.openHelper.writableDatabase
        db.query("PRAGMA user_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(49)
        }
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            val expected = JSONObject(schemaFile(49).readText())
                .getJSONObject("database").getString("identityHash")
            assertThat(cursor.getString(0)).isEqualTo(expected)
        }
    }
}
