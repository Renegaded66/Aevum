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
 * M19.3: Projektmanagement-Rückbau 45 → 46 — auf einer ECHTEN Datenbank.
 *
 * Nutzerentscheidung: „Ich nehme es zurück. Ich will das ganze PM Management
 * doch nicht. Also alles davon." Der Rückbau löscht die Tabelle `pm_node`
 * samt Inhalt (MIGRATION_45_46). Dieser Test fährt denselben Weg wie ein
 * Bestandsgerät beim Update:
 *
 *  1. Aus der vom Room-Compiler erzeugten Schema-Datei 45.json wird eine
 *     echte SQLite-Datenbank der Version 45 gebaut — Tabellen, Indizes,
 *     Master-Tabelle, Identitäts-Hash (dieselbe Technik wie
 *     MigrationTestHelper; der braucht ein Gerät, dieser Test läuft als
 *     Robolectric-Test auf der JVM).
 *  2. Ein „Alter Baum" aus zwei pm_node-Knoten wird eingefüllt.
 *  3. Room öffnet die Datei MIT der Migrations-Kette bis zur AKTUELLEN
 *     Version (M20: 45→46→47) — die echte Upgrade-Kette läuft (DROP →
 *     Gesamtschema validieren → Version und Identitäts-Hash fortschreiben).
 *  4. Geprüft wird: pm_node ist weg (Tabelle UND Inhalt), alle übrigen
 *     Tabellen stehen unangetastet, die Datenbank meldet die aktuelle
 *     Version samt Identitäts-Hash.
 *
 * Der Schema-seitige Beweis (46 = 45 minus pm_node, Element für Element)
 * läuft in [SchemaV46Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PmRemovalMigrationTest {

    private val dbName = "pm-removal-45-to-46"

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

    /** Baut eine echte Datenbank der Version 45 aus der Schema-Datei. */
    private fun createVersion45Database(): File {
        val path = context.getDatabasePath(dbName)
        path.delete()
        context.getDatabasePath("$dbName-journal").delete()
        context.getDatabasePath("$dbName-wal").delete()
        context.getDatabasePath("$dbName-shm").delete()

        val database = JSONObject(schemaFile(45).readText()).getJSONObject("database")
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

        // Altbestand: ein kleiner „Alter Baum" aus zwei PM-Knoten — wird beim
        // Update komplett entsorgt (Nutzerentscheidung, siehe KDoc).
        sqlite.execSQL(
            "INSERT INTO pm_node " +
                "(id, parent_id, type, title, note, color, sort_order, scheduled_date, due_date, done_at, created_at, updated_at) " +
                "VALUES ('pm-alt-1', NULL, 'PROJECT', 'Alter Baum', '', NULL, 0, NULL, NULL, NULL, 1, 1)"
        )
        sqlite.execSQL(
            "INSERT INTO pm_node " +
                "(id, parent_id, type, title, note, color, sort_order, scheduled_date, due_date, done_at, created_at, updated_at) " +
                "VALUES ('pm-alt-2', 'pm-alt-1', 'TASK', 'Alte Aufgabe', '', NULL, 0, '2026-10-17', NULL, NULL, 1, 1)"
        )

        sqlite.version = 45
        sqlite.close()
        return path
    }

    /** Öffnet die Datei über Room — die echte Kette 45→46(→47…) läuft. */
    private fun migrate(): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_43_44,
                AppDatabase.MIGRATION_44_45,
                AppDatabase.MIGRATION_45_46,
                // M20: Room validiert gegen das AKTUELLE Schema — die Kette
                // muss deshalb bis zur aktuellen Version durchlaufen.
                AppDatabase.MIGRATION_46_47,
                // M23: v48 (source_app_package) — Kette bis zur aktuellen Version.
                AppDatabase.MIGRATION_47_48,
                // M26: v49 (ADD COLUMN in automation_settings).
                AppDatabase.MIGRATION_48_49,
                // M27: v50 (Sucht-Detektor-Tabellen).
                AppDatabase.MIGRATION_49_50
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
    fun `Update-entfernt-den-Projektmanagement-Baum-komplett`() {
        createVersion45Database()
        migrate()

        // Tabelle und damit auch die beiden Alt-Knoten sind Geschichte.
        assertThat(tableNames()).doesNotContain("pm_node")
        val db = opened!!.openHelper.writableDatabase
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'pm_node'").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun `alle-uebrigen-Tabellen-stehen-unangetastet`() {
        createVersion45Database()
        migrate()

        // Erwartung direkt aus der 45er-Schema-Datei: alles außer pm_node.
        val database = JSONObject(schemaFile(45).readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val expected = (0 until entities.length())
            .map { entities.getJSONObject(it).getString("tableName") }
            .filter { it != "pm_node" }

        assertThat(tableNames()).containsAtLeastElementsIn(expected)
        assertThat(expected).hasSize(38) // 39 Entities in v45, pm_node ist raus
        // M20: goal_checkin ist die einzige seither hinzugekommene Tabelle.
        assertThat(tableNames()).contains("goal_checkin")
    }

    @Test
    fun `nach-der-Migration-fuehrt-Room-die-Datenbank-auf-der-aktuellen-Version`() {
        createVersion45Database()
        migrate()

        // M20/M23/M26/M27: Die Kette läuft bis 50 durch (die aktuelle Version).
        val db = opened!!.openHelper.writableDatabase
        db.query("PRAGMA user_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(50)
        }
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            val expected = JSONObject(schemaFile(50).readText())
                .getJSONObject("database").getString("identityHash")
            assertThat(cursor.getString(0)).isEqualTo(expected)
        }
    }
}
