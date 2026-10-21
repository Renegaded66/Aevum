package com.d_drostes_apps.aevum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.model.AddictionSession
import com.d_drostes_apps.aevum.data.model.AddictionWatchApp
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M27: Sucht-Detektor 49 → 50 auf einer ECHTEN SQLite-Datenbank.
 *
 * Baut aus `49.json` eine Bestands-DB, legt eine `app_limit`-Zeile an und
 * öffnet sie über Room mit [AppDatabase.MIGRATION_49_50]. Geprüft wird:
 *  - user_version = 50,
 *  - die neuen Tabellen existieren mit den erwarteten Spalten,
 *  - Bestandsdaten (app_limit) bleiben unangetastet,
 *  - die neuen DAOs schreiben und lesen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AddictionMigrationTest {

    private val dbName = "addiction-49-to-50"

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

    private fun createVersion49Database() {
        val path = context.getDatabasePath(dbName)
        path.delete()
        context.getDatabasePath("$dbName-journal").delete()
        context.getDatabasePath("$dbName-wal").delete()
        context.getDatabasePath("$dbName-shm").delete()
        path.parentFile?.mkdirs()

        val database = JSONObject(schemaFile(49).readText()).getJSONObject("database")
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

        sqlite.execSQL(
            "INSERT INTO app_limit (package_name, limit_minutes, enabled, exception_type, " +
                "window_start_min, window_end_min, updated_at) " +
                "VALUES ('com.instagram.android', 45, 1, 'NONE', 0, 0, 1700000000000)"
        )
        sqlite.version = 49
        sqlite.close()
    }

    private fun migrate(): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_49_50)
            .allowMainThreadQueries()
            .build()
        opened = database
        database.openHelper.writableDatabase
        return database
    }

    @Test
    fun `Update-erstellt-beide-Detektor-Tabellen`() {
        createVersion49Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN " +
                "('addiction_watch_app', 'addiction_session') ORDER BY name"
        ).use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(0)
            assertThat(names).containsExactly("addiction_session", "addiction_watch_app")
        }
    }

    @Test
    fun `Bestandsdaten-ueberleben-das-Update`() {
        createVersion49Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT limit_minutes, enabled FROM app_limit WHERE package_name = 'com.instagram.android'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(45)
            assertThat(cursor.getInt(1)).isEqualTo(1)
        }
    }

    @Test
    fun `neue-DAOs-schreiben-und-lesen`() {
        createVersion49Database()
        val database = migrate()

        kotlinx.coroutines.runBlocking {
            database.addictionWatchAppDao().upsert(
                AddictionWatchApp(
                    packageName = "com.instagram.android",
                    appLabel = "Instagram",
                    addedAt = 1_700_000_000_000,
                    sortOrder = 0
                )
            )
            database.addictionSessionDao().upsertAll(
                listOf(
                    AddictionSession(
                        id = "com.instagram.android@1700000000000",
                        packageName = "com.instagram.android",
                        startAt = 1_700_000_000_000,
                        endAt = 1_700_000_600_000,
                        durationMs = 600_000
                    )
                )
            )
            val apps = database.addictionWatchAppDao().getAllOnce()
            val sessions = database.addictionSessionDao().getSinceOnce(0)
            assertThat(apps).hasSize(1)
            assertThat(apps.single().appLabel).isEqualTo("Instagram")
            assertThat(sessions).hasSize(1)
            assertThat(sessions.single().durationMs).isEqualTo(600_000)
        }
    }

    @Test
    fun `Datenbank-meldet-Version-50`() {
        createVersion49Database()
        migrate()

        opened!!.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(50)
        }
    }
}
