package com.d_drostes_apps.aevum.data.db

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M23: Schema-Guard für v48 — App-Tracking-Ursprung.
 *
 * v48 ist bewusst MINIMAL: `activity_session` bekommt GENAU eine nullable
 * Spalte (`source_app_package`). Keine Tabelle, kein Index, keine
 * Bestandsspalte ändert sich. Damit bleiben alle Altdaten und alle
 * Nicht-App-Quellen unverändert gültig.
 */
class SchemaV48Test {

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

    private fun columns(entity: JSONObject): List<String> =
        entity.getJSONArray("fields").let { fields ->
            (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }

    @Test
    fun `Schema-Version-ist-48`() {
        assertThat(database(48).getInt("version")).isEqualTo(48)
    }

    @Test
    fun `48-ist-47-plus-genau-source_app_package`() {
        val before = entities(47)
        val after = entities(48)

        // Keine neue/entfernte Tabelle.
        assertThat(after.keys).isEqualTo(before.keys)

        // activity_session: genau eine Spalte mehr.
        val oldColumns = columns(before.getValue("activity_session"))
        val newColumns = columns(after.getValue("activity_session"))
        assertThat(newColumns).containsExactlyElementsIn(oldColumns + "source_app_package")

        // Die neue Spalte ist nullable — Altdaten bleiben gültig.
        val field = after.getValue("activity_session")
            .getJSONArray("fields")
            .let { fields ->
                (0 until fields.length())
                    .map { fields.getJSONObject(it) }
                    .first { it.getString("columnName") == "source_app_package" }
            }
        assertThat(field.optBoolean("notNull", false)).isFalse()

        // Alle anderen Tabellen sind Element für Element identisch.
        val beforeRest = (before - "activity_session").mapValues { it.value.toString() }
        val afterRest = (after - "activity_session").mapValues { it.value.toString() }
        assertThat(afterRest).isEqualTo(beforeRest)
    }
}
