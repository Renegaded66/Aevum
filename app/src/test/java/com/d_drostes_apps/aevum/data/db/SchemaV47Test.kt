package com.d_drostes_apps.aevum.data.db

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M20: Schema-Guard für die Ziel-Architektur (Version 47).
 *
 * Aevum öffnet die Datenbank bewusst FAIL-FAST (seit M18.109 kein
 * `fallbackToDestructiveMigration`). Eine falsche Migration ist deshalb ein
 * STARTABSTURZ beim Update, kein Schönheitsfehler. Dieser Test hält die
 * Versprechen der 46→47-Migration fest, Element für Element:
 *
 *  1. `goal` bekommt GENAU vier Spalten dazu — tracking_mode (Default
 *     'AUTO'), schedule_days (Default 127), source_kind und accent (beide
 *     NULL erlaubt). Alle Bestandsspalten bleiben unangetastet; Bestands-
 *     ziele verhalten sich damit exakt wie vor M20.
 *  2. GENAU eine Tabelle kommt dazu: `goal_checkin` (Check-ins manueller
 *     Ziele) mit FK auf `goal` (CASCADE) und zwei Lookup-Indizes.
 *  3. Alles andere (alle übrigen Entities, Views, Setup-Abfragen) bleibt
 *     unverändert.
 *
 * Der Daten-Beweis (echte SQLite-Datei von 46 wird migriert, Bestandsziel
 * behält sein Verhalten, Check-ins + CASCADE funktionieren) läuft in
 * [GoalV47MigrationTest].
 */
class SchemaV47Test {

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

    private fun entities(version: Int): Map<String, JSONObject> {
        val map = linkedMapOf<String, JSONObject>()
        val array = database(version).getJSONArray("entities")
        for (index in 0 until array.length()) {
            val entity = array.getJSONObject(index)
            map[entity.getString("tableName")] = entity
        }
        return map
    }

    @Test
    fun `Schema-Version-ist-47`() {
        assertWithMessage("Nach der Ziel-Architektur (M20) muss die Datenbank-Version 47 sein")
            .that(database(47).getInt("version")).isEqualTo(47)
        assertThat(AppDatabase::class.java.name).isNotEmpty()
    }

    @Test
    fun `47-ist-46-PLUS-genau-die-goal_checkin-Tabelle`() {
        val before = entities(46)
        val after = entities(47)

        // Gegenprobe: 46 HAT die Check-in-Tabelle nicht (sonst wäre der Test blind) …
        assertThat(before.containsKey("goal_checkin")).isFalse()
        // … und 47 hat sie.
        assertThat(after.containsKey("goal_checkin")).isTrue()

        // Alle übrigen Tabellen sind Element für Element identisch — nur
        // `goal` (vier neue Spalten) und `goal_checkin` (neu) kommen dazu.
        val beforeRest = (before - "goal").mapValues { it.value.toString() }
        val afterRest = (after - "goal" - "goal_checkin").mapValues { it.value.toString() }

        assertThat(afterRest).isEqualTo(beforeRest)
    }

    @Test
    fun `goal-bekommt-genau-vier-Spalten - Bestandsspalten-bleiben`() {
        val goal = entities(47).getValue("goal")
        val fields = goal.getJSONArray("fields")
        val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }

        // Die 15 Bestandsspalten aus v46 sind alle noch da.
        val oldColumns = listOf(
            "id", "title", "category_id", "tag_id", "activity_type_id", "type", "period",
            "target_value", "target_unit", "filter_json", "start_at", "end_at", "status",
            "created_at", "updated_at"
        )
        assertThat(columns).containsAtLeastElementsIn(oldColumns)
        assertThat(columns).hasSize(oldColumns.size + 4)

        fun field(name: String) = (0 until fields.length())
            .map { fields.getJSONObject(it) }
            .first { it.getString("columnName") == name }

        assertThat(field("tracking_mode").optBoolean("notNull", false)).isTrue()
        assertThat(field("tracking_mode").getString("defaultValue")).isEqualTo("'AUTO'")
        assertThat(field("schedule_days").optBoolean("notNull", false)).isTrue()
        assertThat(field("schedule_days").getString("defaultValue")).isEqualTo("127")
        assertThat(field("source_kind").optBoolean("notNull", false)).isFalse()
        assertThat(field("source_kind").optString("defaultValue")).isEmpty()
        assertThat(field("accent").optBoolean("notNull", false)).isFalse()
        assertThat(field("accent").optString("defaultValue")).isEmpty()
    }

    @Test
    fun `goal_checkin-ist-die-Check-in-Tabelle-mit-CASCADE-und-Indizes`() {
        val checkin = entities(47).getValue("goal_checkin")
        val fields = checkin.getJSONArray("fields")
        val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        assertThat(columns).containsExactly("id", "goal_id", "timestamp_ms", "date_key", "count")

        val fks = checkin.getJSONArray("foreignKeys")
        assertThat(fks.length()).isEqualTo(1)
        val fk = fks.getJSONObject(0)
        assertThat(fk.getString("table")).isEqualTo("goal")
        assertThat(fk.getString("onDelete")).isEqualTo("CASCADE")
        assertThat(fk.getString("onUpdate")).isEqualTo("NO ACTION")

        val indices = checkin.getJSONArray("indices")
        val names = (0 until indices.length()).map { indices.getJSONObject(it).getString("name") }
        assertThat(names).containsExactly("index_goal_checkin_goal_id", "index_goal_checkin_date_key")
    }

    @Test
    fun `die-Identitaets-Hash-von-47-unterscheidet-sich-von-46`() {
        val before = database(46).getString("identityHash")
        val after = database(47).getString("identityHash")
        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `die-M20-Migrations-Konstanten-beschreiben-exakt-die-Schema-Aenderung`() {
        assertThat(AppDatabase.SQL_M20_GOAL_TRACKING_MODE)
            .isEqualTo("ALTER TABLE `goal` ADD COLUMN `tracking_mode` TEXT NOT NULL DEFAULT 'AUTO'")
        assertThat(AppDatabase.SQL_M20_GOAL_SCHEDULE_DAYS)
            .isEqualTo("ALTER TABLE `goal` ADD COLUMN `schedule_days` INTEGER NOT NULL DEFAULT 127")
        assertThat(AppDatabase.SQL_M20_GOAL_SOURCE_KIND)
            .isEqualTo("ALTER TABLE `goal` ADD COLUMN `source_kind` TEXT")
        assertThat(AppDatabase.SQL_M20_GOAL_ACCENT)
            .isEqualTo("ALTER TABLE `goal` ADD COLUMN `accent` TEXT")
        assertThat(AppDatabase.SQL_M20_GOAL_CHECKIN_CREATE)
            .startsWith("CREATE TABLE IF NOT EXISTS `goal_checkin`")
        assertThat(AppDatabase.SQL_M20_GOAL_CHECKIN_CREATE).contains("ON DELETE CASCADE")
    }
}
