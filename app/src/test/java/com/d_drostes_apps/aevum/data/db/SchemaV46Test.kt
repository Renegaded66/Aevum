package com.d_drostes_apps.aevum.data.db

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M19.3: Schema-Guard für den Projektmanagement-Rückbau (Version 46).
 *
 * ── WARUM DIESER TEST EXISTIERT ───────────────────────────────────────────
 *
 * Aevum öffnet die Datenbank bewusst FAIL-FAST (seit M18.109 kein
 * `fallbackToDestructiveMigration`). Eine falsche Migration ist deshalb ein
 * STARTABSTURZ beim Update, kein Schönheitsfehler — das gilt auch fürs
 * ENTFERNEN eines Features: Wird die Entity entfernt, aber eine
 * Schema-Migration für Bestandsgeräte vergessen, würde Room beim Öffnen der
 * alten Datenbank sofort abbrechen.
 *
 * Der Rückbau selbst ist klein: NUR die Tabelle `pm_node` verschwindet
 * (MIGRATION_45_46 = DROP TABLE). Dieser Test hält die beiden
 * Versprechen fest, auf denen der Rückbau beruht:
 *
 *  1. Die Entity-Liste von 46 ist Element für Element die von 45 — MINUS
 *     genau `pm_node`. Alles andere (Spalten, Indizes, Fremdschlüssel,
 *     Setup-Abfragen) bleibt unangetastet.
 *  2. Rooms Identitäts-Hash unterscheidet sich dadurch von 45 (das Schema
 *     IST kleiner geworden) — Beweis, dass der Compiler die Entfernung
 *     tatsächlich gesehen hat.
 *
 * Der Daten-Beweis (echte SQLite-Datei von 45 wird migriert, Tabelle und
 * Inhalt verschwinden, die übrigen 38 Tabellen bleiben) läuft in
 * [PmRemovalMigrationTest].
 */
class SchemaV46Test {

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

    private fun entities(version: Int): List<Pair<String, String>> {
        val array = database(version).getJSONArray("entities")
        return (0 until array.length()).map { index ->
            val entity = array.getJSONObject(index)
            entity.getString("tableName") to entity.toString()
        }
    }

    @Test
    fun `Schema-Version-ist-46`() {
        assertWithMessage("Nach dem PM-Rückbau (M19.3) muss die Datenbank-Version 46 sein")
            .that(database(46).getInt("version")).isEqualTo(46)
        assertThat(AppDatabase::class.java.name).isNotEmpty()
    }

    @Test
    fun `46-ist-45-MINUS-genau-die-pm_node-Tabelle`() {
        val before = entities(45)
        val after = entities(46)

        // Gegenprobe: 45 HAT den PM-Baum (sonst wäre der Test blind) …
        assertThat(before.map { it.first }).contains("pm_node")
        // … und 46 hat ihn nicht mehr.
        assertThat(after.map { it.first }).doesNotContain("pm_node")

        // Alles Übrige ist Element für Element identisch (Spalten, Indizes,
        // Fremdschlüssel, Reihenfolge) — nur pm_node fehlt.
        val expectedRest = before.filter { it.first != "pm_node" }
        assertThat(after).isEqualTo(expectedRest)
        assertThat(after).hasSize(before.size - 1)
    }

    @Test
    fun `Views-und-Setup-Abfragen-bleiben-strukturell-unveraendert`() {
        val before = database(45)
        val after = database(46)
        assertThat(before.optJSONArray("views")?.toString() ?: "[]")
            .isEqualTo(after.optJSONArray("views")?.toString() ?: "[]")
        // Die Setup-Abfragen enthalten die jeweilige Identitäts-Hash
        // (`INSERT OR REPLACE INTO room_master_table … VALUES(42, '<hash>')`)
        // — die MUSS sich mit dem Schema ändern. Verglichen wird deshalb
        // strukturell: eigene Hash durch einen Platzhalter ersetzt.
        val beforeSetups = (before.optJSONArray("setupQueries")?.toString() ?: "[]")
            .replace(before.getString("identityHash"), "<identityHash>")
        val afterSetups = (after.optJSONArray("setupQueries")?.toString() ?: "[]")
            .replace(after.getString("identityHash"), "<identityHash>")
        assertThat(afterSetups).isEqualTo(beforeSetups)
    }

    @Test
    fun `die-Identitaets-Hash-von-46-unterscheidet-sich-von-45`() {
        // Das Schema IST kleiner geworden (eine Tabelle weniger) — hätte der
        // Compiler das nicht gesehen, wäre 46.json identisch zu 45.json und
        // die ganze Entfernung wirkungslos.
        val before = database(45).getString("identityHash")
        val after = database(46).getString("identityHash")
        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `die-Migrations-Konstante-loescht-genau-die-pm_node-Tabelle`() {
        assertThat(AppDatabase.SQL_PM_45_46_DROP)
            .isEqualTo("DROP TABLE IF EXISTS `pm_node`")
    }
}
