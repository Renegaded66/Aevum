package com.d_drostes_apps.aevum.data.db

import com.d_drostes_apps.aevum.data.model.Goal
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M36: Schema-Guard für v53.
 *
 * v53:
 *  - goal + kind (NOT NULL, 'LEGACY') + icon (nullable)
 *  - activity_session.external_id wird UNIQUE (Import-Idempotenz)
 *
 * Der Daten-Beweis (echte SQLite aus 52.json → Room, inkl. Duplikat-Aufräumen)
 * läuft in [HabitTrackerMigrationTest].
 */
class SchemaV53Test {

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
    fun `Schema-Version-ist-53-und-die-Konstante-stimmt`() {
        assertThat(database(53).getInt("version")).isEqualTo(53)
        assertThat(AppDatabase.SCHEMA_VERSION).isEqualTo(53)
    }

    @Test
    fun `53-ist-52-plus-kind-und-icon-in-goal`() {
        val before = entities(52)
        val after = entities(53)
        assertThat(after.keys).containsExactlyElementsIn(before.keys)

        // activity_session ändert nur den Index (unique), nicht die Felder.
        val beforeRest = (before - setOf("goal", "activity_session")).mapValues { it.value.toString() }
        val afterRest = (after - setOf("goal", "activity_session")).mapValues { it.value.toString() }
        assertThat(afterRest).isEqualTo(beforeRest)
        assertThat(fields(after.getValue("activity_session")).mapValues { it.value.toString() })
            .isEqualTo(fields(before.getValue("activity_session")).mapValues { it.value.toString() })

        val beforeGoal = fields(before.getValue("goal")).mapValues { it.value.toString() }
        val afterGoal = fields(after.getValue("goal")).mapValues { it.value.toString() }
        assertThat(afterGoal.keys).containsExactlyElementsIn(beforeGoal.keys + setOf("kind", "icon"))
        assertThat(afterGoal - setOf("kind", "icon")).isEqualTo(beforeGoal)
    }

    @Test
    fun `kind-ist-NOT-NULL-mit-Legacy-Default-icon-ist-nullable`() {
        val goalFields = fields(entities(53).getValue("goal"))

        val kind = goalFields.getValue("kind")
        assertThat(kind.getString("affinity")).isEqualTo("TEXT")
        assertThat(kind.getBoolean("notNull")).isTrue()
        assertThat(kind.getString("defaultValue")).isEqualTo("'LEGACY'")

        val icon = goalFields.getValue("icon")
        assertThat(icon.getString("affinity")).isEqualTo("TEXT")
        assertThat(icon.optBoolean("notNull", false)).isFalse()

        val default = Goal(id = "g", title = "Habit", targetValue = 1f)
        assertThat(default.kind).isEqualTo(Goal.KIND_LEGACY)
        assertThat(default.icon).isNull()
    }

    @Test
    fun `external_id-Index-ist-eindeutig`() {
        val entity = entities(53).getValue("activity_session")
        val indices = entity.getJSONArray("indices")
        var found = false
        for (i in 0 until indices.length()) {
            val index = indices.getJSONObject(i)
            if (index.getString("name") == "index_activity_session_external_id") {
                found = true
                assertThat(index.getBoolean("unique")).isTrue()
                val columns = index.getJSONArray("columnNames")
                assertThat((0 until columns.length()).map { columns.getString(it) })
                    .containsExactly("external_id")
            }
        }
        assertThat(found).isTrue()
    }
}
