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
 * M28: Bildschirm-Aufzeichnung 50 → 51 — auf einer ECHTEN Datenbank.
 *
 * Derselbe Weg wie beim App-Update auf einem Bestandsgerät:
 *
 *  1. Aus der vom Room-Compiler erzeugten `50.json` wird eine echte
 *     SQLite-Datenbank der Version 50 gebaut.
 *  2. Eine Bestandszeile in `automation_settings` wird eingefüllt — mit
 *     krummen, nicht-defaulteten Werten (Geofencing AN, Vorlauf 7 min).
 *  3. Room öffnet die Datei MIT [AppDatabase.MIGRATION_50_51]: ein
 *     `ADD COLUMN`, Gesamtschema-Validierung, Version + Identitäts-Hash.
 *  4. Geprüft wird: Bestandszeile unverändert, neue Spalte = 'digital',
 *     Schreiben über das Entity funktioniert.
 *
 * Der Schema-seitige Beweis (51 = 50 + eine Spalte, Element für Element)
 * läuft in [SchemaV51Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ScreenRecordingActivityMigrationTest {

    private val dbName = "settings-50-to-51"

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

    private fun automationSettingsEntity(version: Int): JSONObject {
        val entities = JSONObject(schemaFile(version).readText())
            .getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == "automation_settings") return entity
        }
        error("automation_settings fehlt in $version.json")
    }

    /** Baut eine echte Datenbank der Version 50 aus der Schema-Datei. */
    private fun createVersion50Database(): File {
        val path = context.getDatabasePath(dbName)
        path.delete()
        context.getDatabasePath("$dbName-journal").delete()
        context.getDatabasePath("$dbName-wal").delete()
        context.getDatabasePath("$dbName-shm").delete()
        path.parentFile?.mkdirs()

        val database = JSONObject(schemaFile(50).readText()).getJSONObject("database")
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

        insertLegacySettingsRow(sqlite)

        sqlite.version = 50
        sqlite.close()
        return path
    }

    /**
     * Bestandszeile mit allen Spalten (NICHT-NULL-Spalten ohne Default
     * brauchen einen expliziten Wert). Krumme Werte, damit ein Reset auffällt.
     */
    private fun insertLegacySettingsRow(sqlite: android.database.sqlite.SQLiteDatabase) {
        val fields = automationSettingsEntity(50).getJSONArray("fields")
        val columns = mutableListOf<String>()
        val values = mutableListOf<String>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            val column = field.getString("columnName")
            columns += column
            values += when (column) {
                "id" -> "'default'"
                "updated_at" -> "1700000000000"
                "screen_recording_minutes" -> "7"
                "geofencing_enabled" -> "1"
                else -> when {
                    field.has("defaultValue") -> field.getString("defaultValue")
                    field.getString("affinity") == "TEXT" -> "''"
                    field.getString("affinity") == "REAL" -> "0"
                    else -> "0"
                }
            }
        }
        sqlite.execSQL(
            "INSERT INTO automation_settings (${columns.joinToString(", ")}) " +
                "VALUES (${values.joinToString(", ")})"
        )
    }

    /** Öffnet die Datei über Room — die echte 50→51-Migration läuft. */
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
    fun `Update-setzt-die-neue-Spalte-auf-digital`() {
        createVersion50Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT screen_recording_activity_type_id FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("digital")
        }
    }

    @Test
    fun `Bestands-Einstellungen-ueberleben-das-Update`() {
        createVersion50Database()
        migrate()

        opened!!.openHelper.writableDatabase.query(
            "SELECT geofencing_enabled, screen_recording_minutes, updated_at " +
                "FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)              // Geofencing AN
            assertThat(cursor.getInt(1)).isEqualTo(7)              // Vorlauf 7 min
            assertThat(cursor.getLong(2)).isEqualTo(1700000000000L)
        }
    }

    @Test
    fun `Datenbank-meldet-Version-51`() {
        createVersion50Database()
        migrate()

        opened!!.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(51)
        }
    }

    @Test
    fun `Schreiben-ueber-das-Entity-funktioniert-nach-dem-Update`() {
        createVersion50Database()
        val database = migrate()

        opened!!.openHelper.writableDatabase.execSQL(
            "UPDATE automation_settings SET screen_recording_activity_type_id = 'reading' " +
                "WHERE id = 'default'"
        )
        opened!!.openHelper.writableDatabase.query(
            "SELECT screen_recording_activity_type_id FROM automation_settings WHERE id = 'default'"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("reading")
        }
        assertThat(database.isOpen).isTrue()
    }
}
