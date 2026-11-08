package com.d_drostes_apps.aevum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M36: v52 → v53 auf einer ECHTEN SQLite-Datenbank.
 *
 * Geprüft:
 *  1. Bestandsziele bekommen die richtige Habit-Art (DONE/COUNT/TIME),
 *     Wochen-/Monatsziele und AUTO-Ziele bleiben LEGACY.
 *  2. activity_session.external_id wird UNIQUE; vorhandene Duplikate
 *     (der gemeldete PC-Sync-Fall) werden auf die neueste Zeile reduziert.
 *  3. Der Unique-Index verhindert danach neue Duplikate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HabitTrackerMigrationTest {

    private val dbName = "m36-52-to-53"
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

    private fun createVersion52Database(): File {
        val path = context.getDatabasePath(dbName)
        listOf("", "-journal", "-wal", "-shm").forEach {
            context.getDatabasePath("$dbName$it").delete()
        }
        path.parentFile?.mkdirs()

        val db = database(52)
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
        sqlite.version = 52
        sqlite.close()
        return path
    }

    private fun insertLegacyRows(sqlite: android.database.sqlite.SQLiteDatabase) {
        fun goal(
            id: String,
            type: String,
            period: String,
            target: Float,
            unit: String,
            mode: String
        ) = sqlite.execSQL(
            "INSERT INTO goal (id, title, type, period, target_value, target_unit, " +
                "tracking_mode, schedule_days, start_at, status, created_at, updated_at) " +
                "VALUES ('$id', '$id', '$type', '$period', $target, '$unit', " +
                "'$mode', 127, 1700000000000, 'ACTIVE', 1700000000000, 1700000000000)"
        )

        goal("g_done", "COUNT", "DAILY", 1f, "COUNT", "MANUAL")
        goal("g_count", "COUNT", "DAILY", 5f, "COUNT", "MANUAL")
        goal("g_time", "COUNT", "DAILY", 1f, "COUNT", "MANUAL")
        goal("g_count_credit", "COUNT", "DAILY", 5f, "COUNT", "MANUAL")
        goal("g_weekly", "COUNT", "WEEKLY", 4f, "COUNT", "MANUAL")
        goal("g_auto", "AT_MOST", "DAILY", 2f, "HOURS", "AUTO")

        // Zeitgutschrift am Zeit-Ziel (macht es in der Migration zu TIME).
        sqlite.execSQL(
            "INSERT INTO goal_checkin (id, goal_id, timestamp_ms, date_key, count, duration_ms) " +
                "VALUES ('c1', 'g_time', 1700000000000, '2026-07-20', 1, 60000)"
        )
        // M35 erlaubte Zeitgutschrift auch auf Zählzielen. Das bleibt COUNT.
        sqlite.execSQL(
            "INSERT INTO goal_checkin (id, goal_id, timestamp_ms, date_key, count, duration_ms) " +
                "VALUES ('c2', 'g_count_credit', 1700000000000, '2026-07-20', 1, 60000)"
        )

        fun session(id: String, externalId: String, createdAt: Long) = sqlite.execSQL(
            "INSERT INTO activity_session (id, title, start_at, end_at, timezone_id, " +
                "source_type, created_by, external_id, confidence, is_user_edited, " +
                "created_at, updated_at, revision) VALUES ('$id', 'Zocken', 1000, 2000, " +
                "'Europe/Berlin', 'PC_TRACKER', 'PC_IMPORT', '$externalId', 1.0, 0, " +
                "$createdAt, $createdAt, 1)"
        )
        session("old_dup", "pc_dup", 100)
        session("new_dup", "pc_dup", 200)
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
    fun `Bestandsziele werden korrekt zugeordnet`() {
        createVersion52Database()
        val db = migrate()
        val goals = runBlocking { db.goalDao().getAll().first() }.associateBy { it.id }

        assertThat(goals.getValue("g_done").kind).isEqualTo("DONE")
        assertThat(goals.getValue("g_count").kind).isEqualTo("COUNT")
        assertThat(goals.getValue("g_count_credit").kind).isEqualTo("COUNT")
        assertThat(goals.getValue("g_time").kind).isEqualTo("TIME")
        assertThat(goals.getValue("g_weekly").kind).isEqualTo("LEGACY")
        assertThat(goals.getValue("g_auto").kind).isEqualTo("LEGACY")
    }

    @Test
    fun `Duplikate mit gleicher externalId werden auf die neueste Zeile reduziert`() {
        createVersion52Database()
        val db = migrate()
        val rows = runBlocking {
            db.activitySessionDao().getByExternalIdsOnce(listOf("pc_dup"))
        }
        assertThat(rows).hasSize(1)
        assertThat(rows.first().id).isEqualTo("new_dup")
    }

    @Test
    fun `Unique-Index verhindert neue Duplikate`() {
        createVersion52Database()
        val db = migrate()
        val writable = db.openHelper.writableDatabase

        var failed = false
        try {
            writable.execSQL(
                "INSERT INTO activity_session (id, title, start_at, end_at, timezone_id, " +
                    "source_type, created_by, external_id, confidence, is_user_edited, " +
                    "created_at, updated_at, revision) VALUES ('third', 'Zocken', 1000, 3000, " +
                    "'Europe/Berlin', 'PC_TRACKER', 'PC_IMPORT', 'pc_dup', 1.0, 0, 300, 300, 1)"
            )
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            failed = true
        }
        assertThat(failed).isTrue()
    }
}
