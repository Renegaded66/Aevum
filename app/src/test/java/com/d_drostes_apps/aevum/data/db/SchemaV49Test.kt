package com.d_drostes_apps.aevum.data.db

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M26: Schema-Guard für v49 — Anruf-Erkennung.
 *
 * v49 ist bewusst MINIMAL: `automation_settings` bekommt GENAU zwei
 * Spalten (`call_detection_enabled`, `call_activity_type_id`). Keine
 * Tabelle, kein Index, keine Bestandszeile ändert sich. Damit behalten
 * Bestandsgeräte alle Einstellungen, und der neue Schalter ist aus, bis der
 * Nutzer ihn selbst einschaltet.
 *
 * Der Daten-Beweis (echte SQLite aus 48.json → Room) läuft in
 * [CallDetectionMigrationTest].
 */
class SchemaV49Test {

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

    private fun field(entity: JSONObject, column: String): JSONObject =
        entity.getJSONArray("fields").let { fields ->
            (0 until fields.length())
                .map { fields.getJSONObject(it) }
                .first { it.getString("columnName") == column }
        }

    @Test
    fun `Schema-Version-ist-49`() {
        assertThat(database(49).getInt("version")).isEqualTo(49)
    }

    @Test
    fun `49-ist-48-plus-genau-zwei-Anruf-Spalten`() {
        val before = entities(48)
        val after = entities(49)

        // Keine neue/entfernte Tabelle.
        assertThat(after.keys).isEqualTo(before.keys)

        // automation_settings: genau zwei Spalten mehr.
        val oldColumns = columns(before.getValue("automation_settings"))
        val newColumns = columns(after.getValue("automation_settings"))
        assertThat(newColumns).containsExactlyElementsIn(
            oldColumns + listOf("call_detection_enabled", "call_activity_type_id")
        )

        // Alle anderen Tabellen sind Element für Element identisch.
        val beforeRest = (before - "automation_settings").mapValues { it.value.toString() }
        val afterRest = (after - "automation_settings").mapValues { it.value.toString() }
        assertThat(afterRest).isEqualTo(beforeRest)
    }

    @Test
    fun `Anruf-Spalten-sind-NOT-NULL-mit-Default`() {
        val settings = entities(49).getValue("automation_settings")

        val enabled = field(settings, "call_detection_enabled")
        assertThat(enabled.getBoolean("notNull")).isTrue()
        // Default AUS: READ_PHONE_STATE ist eine dangerous-Permission.
        assertThat(enabled.getString("defaultValue")).isEqualTo("0")

        val typeId = field(settings, "call_activity_type_id")
        assertThat(typeId.getBoolean("notNull")).isTrue()
        // Default „social" = System-Typ „Soziales" (User: „Default sozial").
        // Room schreibt TEXT-Defaults in Anführungszeichen: 'social'.
        assertThat(typeId.getString("defaultValue")).isEqualTo("'social'")
    }
}
