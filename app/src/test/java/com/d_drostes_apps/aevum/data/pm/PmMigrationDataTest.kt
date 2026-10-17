package com.d_drostes_apps.aevum.data.pm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.domain.projects.PmProgress
import com.d_drostes_apps.aevum.domain.projects.PmTree
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M19.2: Daten-Migration 44 → 45 — auf einer ECHTEN Datenbank.
 *
 * ── WARUM DIESER TEST EXISTIERT ───────────────────────────────────────────
 *
 * Die Migration 44→45 ändert KEIN Schema, sondern die BEDEUTUNG des Feldes
 * `type` (Nutzer-Entscheidung: „Ich möchte keine Unterscheidung zwischen
 * Projekt, Kategorie und Aufgabe"):
 *
 *   CATEGORY                    → PROJECT
 *   TASK mit Kindern („Schritte")→ PROJECT (die Kinder bleiben Aufgaben)
 *   TODO                        → TASK
 *   TASK ohne Kinder            → TASK
 *
 * Geht dabei etwas schief, verliert Devon beim App-Update stillschweigend
 * seinen kompletten Baum — oder schlimmer: Knoten werden zu Blättern und
 * ihre Kinder verwaisen. Deshalb wird hier NICHT gegen ein Mock geprüft,
 * sondern so realistisch wie möglich:
 *
 *  1. Aus der vom Room-Compiler erzeugten Schema-Datei 44.json wird eine
 *     echte SQLite-Datenbank der Version 44 gebaut — Tabellen, Indizes,
 *     Master-Tabelle, Identitäts-Hash — genau so, wie MigrationTestHelper
 *     es auf dem Gerät tut ([MigrationTestHelper] braucht ein Gerät, dieser
 *     Test läuft als Robolectric-Test auf der JVM).
 *  2. Altdaten im Stil wirklicher v44-Bestände werden eingefüllt
 *     (Kategorie, Aufgabe mit Schritten, Aufgabe ohne Schritte, Todo ohne
 *     Aufgabe — inklusive Datum, das erhalten bleiben muss).
 *  3. Room öffnet die Datei MIT [AppDatabase.MIGRATION_44_45]; damit läuft
 *     die echte Upgrade-Kette (SQL ausführen → Gesamtschema validieren →
 *     Version fortschreiben).
 *  4. Geprüft wird das Ergebnis: Typen, Datum, Sortierung — und aus Sicht
 *     der UI: Fortschritt und Aufgabengruppen des migrierten Baumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PmMigrationDataTest {

    private val dbName = "pm-migration-44-to-45"

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

    /**
     * Baut eine echte Datenbank der Version 44 aus der Schema-Datei —
     * dieselbe Technik wie MigrationTestHelper.createDatabase().
     */
    private fun createVersion44Database(): File {
        val path = context.getDatabasePath(dbName)
        path.delete()
        context.getDatabasePath("$dbName-journal").delete()
        context.getDatabasePath("$dbName-wal").delete()
        context.getDatabasePath("$dbName-shm").delete()

        val database = JSONObject(schemaFile(44).readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)

        fun resolve(sql: String, table: String): String =
            sql.replace("\${TABLE_NAME}", table)

        // 1) Alle Tabellen, 2) alle Indizes — Reihenfolge wie im Schema.
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
        // 3) Views (derzeit keine) und Master-Tabelle samt Identitäts-Hash.
        val views = database.optJSONArray("views") ?: org.json.JSONArray()
        for (i in 0 until views.length()) {
            val view = views.getJSONObject(i)
            sqlite.execSQL(resolve(view.getString("createSql"), view.getString("viewName")))
        }
        val setups = database.optJSONArray("setupQueries") ?: org.json.JSONArray()
        for (i in 0 until setups.length()) sqlite.execSQL(setups.getString(i))

        // 4) Altdaten der Version 44 — vier Welten plus Sonderfall.
        val now = 1_700_000_000_000L
        fun insert(
            id: String,
            parent: String?,
            type: String,
            title: String,
            order: Int,
            scheduled: String?,
            doneAt: Long?
        ) {
            sqlite.execSQL(
                "INSERT INTO pm_node " +
                    "(id, parent_id, type, title, note, color, sort_order, scheduled_date, due_date, done_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, '', NULL, ?, ?, NULL, ?, ?, ?)",
                arrayOf<Any?>(id, parent, type, title, order, scheduled, doneAt, now, now)
            )
        }
        insert("cat1", null, "CATEGORY", "Projektgruppe", 0, null, null)
        insert("task1", null, "TASK", "Präsentation", 1, null, null)
        insert("todo1", "task1", "TODO", "Erste Hälfte lesen", 0, null, null)
        insert("todo2", "task1", "TODO", "Zweite Hälfte lesen", 1, "2026-10-17", null)
        insert("todo_done", "task1", "TODO", "Deckblatt entworfen", 2, null, now)
        insert("task2", null, "TASK", "Alleinige Aufgabe", 2, null, null)
        insert("todo3", null, "TODO", "Schritt ohne Aufgabe", 3, "2026-10-17", null)

        sqlite.version = 44
        sqlite.close()
        return path
    }

    /** Öffnet die Datei über Room — die echte 44→45-Migration läuft. */
    private fun migrate(): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_44_45)
            .allowMainThreadQueries()
            .build()
        opened = database
        database.openHelper.writableDatabase
        return database
    }

    private fun readAll(): List<PmNode> {
        val db = opened!!.openHelper.writableDatabase
        return db.query(
            "SELECT id, parent_id, type, title, sort_order, scheduled_date, done_at, created_at, updated_at FROM pm_node"
        ).use { cursor ->
            val out = mutableListOf<PmNode>()
            while (cursor.moveToNext()) {
                out += PmNode(
                    id = cursor.getString(0),
                    parentId = cursor.getString(1),
                    type = cursor.getString(2),
                    title = cursor.getString(3),
                    sortOrder = cursor.getInt(4),
                    scheduledDate = cursor.getString(5),
                    doneAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                    createdAt = cursor.getLong(7),
                    updatedAt = cursor.getLong(8)
                )
            }
            out
        }
    }

    @Test
    fun `Kategorien-werden-Unterprojekte`() {
        createVersion44Database()
        migrate()
        val nodes = readAll().associateBy { it.id }
        assertThat(nodes.getValue("cat1").type).isEqualTo(PmType.PROJECT)
    }

    @Test
    fun `Aufgabe-mit-Schritten-wird-Unterprojekt-und-die-Schritte-werden-Aufgaben`() {
        createVersion44Database()
        migrate()
        val nodes = readAll().associateBy { it.id }

        // Eltern-Knoten: die alte „Aufgabe" ist jetzt ein Unterprojekt …
        assertThat(nodes.getValue("task1").type).isEqualTo(PmType.PROJECT)
        // … und ihre Schritte sind abhakbare Aufgaben — KEIN Kind geht verloren.
        assertThat(nodes.getValue("todo1").type).isEqualTo(PmType.TASK)
        assertThat(nodes.getValue("todo1").parentId).isEqualTo("task1")
        assertThat(nodes.getValue("todo2").type).isEqualTo(PmType.TASK)

        // Aus Sicht der neuen Oberfläche: Fortschritt 1 von 3 (eine Aufgabe
        // war bereits erledigt) und die Gruppe existiert.
        val tree = PmTree(readAll())
        assertThat(tree.progress("task1")).isEqualTo(PmProgress(done = 1, total = 3))
        assertThat(tree.taskGroups().map { it.id }).contains("task1")
    }

    @Test
    fun `Aufgabe-ohne-Schritte-bleibt-Aufgabe`() {
        createVersion44Database()
        migrate()
        val nodes = readAll().associateBy { it.id }
        assertThat(nodes.getValue("task2").type).isEqualTo(PmType.TASK)
    }

    @Test
    fun `Todos-werden-Aufgaben-und-behalten-Datum-und-Erledigung`() {
        createVersion44Database()
        migrate()
        val nodes = readAll().associateBy { it.id }

        val withoutParent = nodes.getValue("todo3")
        assertThat(withoutParent.type).isEqualTo(PmType.TASK)
        // Wichtig: das geplante Datum darf die Typ-Änderung NICHT verlieren.
        assertThat(withoutParent.scheduledDate).isEqualTo("2026-10-17")

        val done = nodes.getValue("todo_done")
        assertThat(done.type).isEqualTo(PmType.TASK)
        assertThat(done.doneAt).isNotNull()
    }

    @Test
    fun `die-Sortierung-der-Geschwister-bleibt-erhalten`() {
        createVersion44Database()
        migrate()
        val tree = PmTree(readAll())
        assertThat(tree.children("task1").map { it.id })
            .containsExactly("todo1", "todo2", "todo_done").inOrder()
        assertThat(tree.roots().map { it.id })
            .containsExactly("cat1", "task1", "task2", "todo3").inOrder()
    }

    @Test
    fun `nach-der-Migration-fuehrt-Room-die-Datenbank-als-Version-45`() {
        createVersion44Database()
        migrate()
        val db = opened!!.openHelper.writableDatabase
        db.query("PRAGMA user_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(45)
        }
        // Und die Identitäts-Hash wurde auf die neue Version fortgeschrieben
        // (sonst würde jeder weitere Start erneut migrieren wollen).
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            val expected = JSONObject(schemaFile(45).readText())
                .getJSONObject("database").getString("identityHash")
            assertThat(cursor.getString(0)).isEqualTo(expected)
        }
    }
}
