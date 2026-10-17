package com.d_drostes_apps.aevum.data.pm

import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M19.2: Schema-Guard für die Migration 43 → 44 → 45.
 *
 * ── WARUM DIESER TEST EXISTIERT ───────────────────────────────────────────
 *
 * Aevum öffnet die Datenbank bewusst FAIL-FAST: seit M18.109 gibt es kein
 * `fallbackToDestructiveMigration` mehr. Fehlt oder passt eine Migration
 * nicht, wirft Room beim Öffnen eine IllegalStateException — die App stürzt
 * beim Start ab. Genau deshalb ist eine falsche Migration ein STARTABSTURZ
 * beim Update und kein Schönheitsfehler.
 *
 * Die Migration 44→45 ändert KEIN Schema — sie deutet nur das `type`-Feld
 * neu (CATEGORY→PROJECT, TASK-mit-Kindern→PROJECT, TODO→TASK; siehe
 * [PmMigrationDataTest] für den Daten-Beweis). Dieser Test hält fest, dass
 * die Schema-Dateien 44 und 45 Element für Element identisch sind: bricht das
 * jemand (z. B. mit einer Spalte in der Entity UND einer Schema-Datei nur
 * für 45), dann fehlt der Schritt für Bestandsgeräte — hier fällt es auf,
 * nicht erst auf Devons Handy.
 *
 * Der zweite Teil vergleicht weiterhin die SQL-Anweisungen, die die
 * Migration WIRKLICH ausführt ([AppDatabase.SQL_CREATE_PM_NODE] und
 * [AppDatabase.SQL_PM_NODE_INDICES]), mit der vom Compiler erzeugten
 * Schema-Datei 45.json.
 *
 * DETAIL: In der Schema-Datei steht statt des Tabellennamens der Platzhalter
 * `${TABLE_NAME}` (Room setzt ihn zur Laufzeit ein). Der Test ersetzt ihn
 * durch den echten Namen, bevor er vergleicht.
 */
class PmMigrationSchemaTest {

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

    private fun database(version: Int = 45): JSONObject =
        JSONObject(schemaFile(version).readText()).getJSONObject("database")

    private fun pmEntity(version: Int = 45): JSONObject {
        val database = database(version)
        return (0 until database.getJSONArray("entities").length())
            .map { database.getJSONArray("entities").getJSONObject(it) }
            .first { it.getString("tableName") == "pm_node" }
    }

    /** Room schreibt `${TABLE_NAME}` als Platzhalter in die Schema-Datei. */
    private fun resolved(sql: String): String = sql.replace("\${TABLE_NAME}", "pm_node")

    @Test
    fun `Schema-Version-ist-45`() {
        assertWithMessage("Nach M19.2 muss die Datenbank-Version 45 sein")
            .that(database().getInt("version")).isEqualTo(45)
        assertThat(AppDatabase::class.java.name).isNotEmpty()
    }

    @Test
    fun `die-Schemata-44-und-45-sind-element-fuer-element-identisch`() {
        // Die Migration 44→45 ist eine REINE Daten-Migration: ändert sich hier
        // etwas, braucht es zusätzlich eine Schema-Migration für Bestandsgeräte.
        val before = JSONObject(schemaFile(44).readText()).getJSONObject("database")
        val after = JSONObject(schemaFile(45).readText()).getJSONObject("database")

        // Tabellen einschließlich Spalten, Indizes, Fremdschlüsseln:
        assertThat(before.getJSONArray("entities").toString())
            .isEqualTo(after.getJSONArray("entities").toString())
        // Views und Setup-Abfragen (Master-Tabelle); beide Felder fehlen,
        // wenn sie leer sind — deshalb optJSONArray.
        assertThat(before.optJSONArray("views")?.toString() ?: "[]")
            .isEqualTo(after.optJSONArray("views")?.toString() ?: "[]")
        assertThat(before.optJSONArray("setupQueries")?.toString() ?: "[]")
            .isEqualTo(after.optJSONArray("setupQueries")?.toString() ?: "[]")
        // Nur die Version unterscheidet sich: dasselbe Schema unter neuer
        // Nummer — und weil Room die Versionsnummer nicht in die
        // Identitäts-Hash einrechnet, ist sogar diese identisch.
        assertThat(before.getInt("version")).isEqualTo(44)
        assertThat(after.getInt("version")).isEqualTo(45)
        assertThat(before.getString("identityHash")).isEqualTo(after.getString("identityHash"))
    }

    @Test
    fun `die-Tabelle-der-Migration-entspricht-exakt-dem-erwarteten-Schema`() {
        val expected = resolved(pmEntity().getString("createSql"))
        assertThat(AppDatabase.SQL_CREATE_PM_NODE).isEqualTo(expected)
    }

    @Test
    fun `alle-Indizes-der-Migration-entsprechen-exakt-dem-erwarteten-Schema`() {
        val indices = pmEntity().getJSONArray("indices")
        val expected = (0 until indices.length())
            .map { resolved(indices.getJSONObject(it).getString("createSql")) }
            .toList()
        assertThat(AppDatabase.SQL_PM_NODE_INDICES.toSet()).isEqualTo(expected.toSet())
        assertThat(AppDatabase.SQL_PM_NODE_INDICES).hasSize(expected.size)
    }

    @Test
    fun `die-Tabelle-hat-genau-die-erwarteten-Spalten-und-Bedingungen`() {
        // Absicherung unabhängig vom SQL-Text: Spaltennamen, Typen und
        // Nullbarkeit sind das, worauf Fortschritt, Tagesplan und Backlog
        // aufbauen (parent_id/scheduled_date/done_at MÜSSEN nullbar sein).
        val fields = pmEntity().getJSONArray("fields")
        val byName = (0 until fields.length())
            .map { fields.getJSONObject(it) }
            .associateBy { it.getString("columnName") }

        assertThat(byName.keys).containsExactly(
            "id", "parent_id", "type", "title", "note", "color", "sort_order",
            "scheduled_date", "due_date", "done_at", "created_at", "updated_at"
        )
        assertThat(byName.getValue("id").optBoolean("notNull", false)).isTrue()
        assertThat(byName.getValue("type").optBoolean("notNull", false)).isTrue()
        assertThat(byName.getValue("title").optBoolean("notNull", false)).isTrue()
        assertThat(AppDatabase.SQL_CREATE_PM_NODE).contains("`note` TEXT NOT NULL")
        assertThat(byName.getValue("sort_order").getString("affinity")).isEqualTo("INTEGER")
        assertThat(byName.getValue("created_at").getString("affinity")).isEqualTo("INTEGER")
        assertThat(byName.getValue("updated_at").getString("affinity")).isEqualTo("INTEGER")
        assertThat(byName.getValue("done_at").getString("affinity")).isEqualTo("INTEGER")
        assertThat(byName.getValue("id").getString("affinity")).isEqualTo("TEXT")
        // Nullbar: Wurzel-Knoten, Backlog-Todos, noch nicht erledigte Todos.
        assertThat(byName.getValue("parent_id").optBoolean("notNull", false)).isFalse()
        assertThat(byName.getValue("scheduled_date").optBoolean("notNull", false)).isFalse()
        assertThat(byName.getValue("due_date").optBoolean("notNull", false)).isFalse()
        assertThat(byName.getValue("done_at").optBoolean("notNull", false)).isFalse()
        assertThat(byName.getValue("color").optBoolean("notNull", false)).isFalse()
    }

    @Test
    fun `der-Primaerschluessel-ist-die-ID`() {
        val primaryKey = pmEntity().getJSONObject("primaryKey")
        assertThat(primaryKey.getBoolean("autoGenerate")).isFalse()
        assertThat(primaryKey.getJSONArray("columnNames").getString(0)).isEqualTo("id")
        assertThat(primaryKey.getJSONArray("columnNames").length()).isEqualTo(1)
    }

    @Test
    fun `der-Selbstbezug-loescht-Kinder-mit-CASCADE`() {
        val foreignKeys = pmEntity().getJSONArray("foreignKeys")
        val selfReference = (0 until foreignKeys.length())
            .map { foreignKeys.getJSONObject(it) }
            .first { it.getJSONArray("columns").getString(0) == "parent_id" }
        assertThat(selfReference.getString("table")).isEqualTo("pm_node")
        assertThat(selfReference.getJSONArray("referencedColumns").getString(0)).isEqualTo("id")
        assertThat(selfReference.getString("onDelete")).isEqualTo("CASCADE")
        // Ein Projekt zu löschen muss den kompletten Teilbaum abräumen.
        assertThat(AppDatabase.SQL_CREATE_PM_NODE).contains("ON DELETE CASCADE")
    }
}
