package com.d_drostes_apps.aevum.data.db

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M35: Schema-Guard für v52.
 *
 * v52 ist rein additiv:
 *  - goal_checkin + activity_type_id (nullable) + duration_ms (NOT NULL, 0)
 *  - automation_settings + drive_activity_type_id (NOT NULL, 'driving')
 *
 * Keine Tabelle kommt hinzu oder verschwindet. Der Daten-Beweis (echte
 * SQLite aus 51.json → Room) läuft in [ManualGoalCreditMigrationTest].
 */
class SchemaV52Test {

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

    private fun fields(entity: JSONObject): Map<String, JSONObject> {
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).associate { index ->
            val field = fields.getJSONObject(index)
            field.getString("columnName") to field
        }
    }

    @Test
    fun `Schema-52-bleibt-historisch-und-die-Konstante-ist-53`() {
        assertThat(database(52).getInt("version")).isEqualTo(52)
        assertThat(AppDatabase.SCHEMA_VERSION).isEqualTo(53)
    }

    @Test
    fun `52-ist-51-plus-drei-additive-Spalten`() {
        val before = entities(51)
        val after = entities(52)

        assertThat(after.keys).containsExactlyElementsIn(before.keys)

        // Alle unveränderten Tabellen bleiben identisch.
        val beforeRest = (before - setOf("goal_checkin", "automation_settings"))
            .mapValues { it.value.toString() }
        val afterRest = (after - setOf("goal_checkin", "automation_settings"))
            .mapValues { it.value.toString() }
        assertThat(afterRest).isEqualTo(beforeRest)

        val beforeCheckin = fields(before.getValue("goal_checkin")).mapValues { it.value.toString() }
        val afterCheckin = fields(after.getValue("goal_checkin")).mapValues { it.value.toString() }
        assertThat(afterCheckin.keys)
            .containsExactlyElementsIn(beforeCheckin.keys + setOf("activity_type_id", "duration_ms"))
        assertThat(afterCheckin - setOf("activity_type_id", "duration_ms"))
            .isEqualTo(beforeCheckin)

        val beforeSettings = fields(before.getValue("automation_settings")).mapValues { it.value.toString() }
        val afterSettings = fields(after.getValue("automation_settings")).mapValues { it.value.toString() }
        assertThat(afterSettings.keys)
            .containsExactlyElementsIn(beforeSettings.keys + "drive_activity_type_id")
        assertThat(afterSettings - "drive_activity_type_id").isEqualTo(beforeSettings)
    }

    @Test
    fun `goal_checkin-Spalten-haben-die-richtigen-Defaults`() {
        val fields = fields(entities(52).getValue("goal_checkin"))

        val activity = fields.getValue("activity_type_id")
        assertThat(activity.getString("affinity")).isEqualTo("TEXT")
        assertThat(activity.optBoolean("notNull", false)).isFalse()
        assertThat(activity.has("defaultValue")).isFalse()

        val duration = fields.getValue("duration_ms")
        assertThat(duration.getString("affinity")).isEqualTo("INTEGER")
        assertThat(duration.getBoolean("notNull")).isTrue()
        assertThat(duration.getString("defaultValue")).isEqualTo("0")

        // Entity-Defaults müssen exakt dazu passen, sonst kippt Room bei der
        // Schema-Validierung.
        assertThat(GoalCheckin(id = "c", goalId = "g", timestampMs = 0, dateKey = "2026-01-01").durationMs)
            .isEqualTo(0L)
        assertThat(GoalCheckin(id = "c", goalId = "g", timestampMs = 0, dateKey = "2026-01-01").activityTypeId)
            .isNull()
    }

    @Test
    fun `Fahrt-Activity-hat-Default-driving`() {
        val field = fields(entities(52).getValue("automation_settings"))
            .getValue("drive_activity_type_id")

        assertThat(field.getString("affinity")).isEqualTo("TEXT")
        assertThat(field.getBoolean("notNull")).isTrue()
        assertThat(field.getString("defaultValue")).isEqualTo("'driving'")
        assertThat(AutomationSettings().driveActivityTypeId).isEqualTo("driving")
    }
}
