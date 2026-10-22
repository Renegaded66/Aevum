package com.d_drostes_apps.aevum.data.db

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M28: Schema-Guard für v51 — zugehörige Aktivität der Bildschirm-Aufzeichnung.
 *
 * v51 ist rein additiv: EINE neue Spalte in `automation_settings` mit
 * Default `'digital'`. Keine Tabelle ändert sonst ihr Aussehen, es gibt
 * keinen Backfill und keinen Datenverlust. Der Daten-Beweis (echte SQLite
 * aus 50.json → Room) läuft in [ScreenRecordingActivityMigrationTest].
 */
class SchemaV51Test {

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
    fun `Schema-Version-ist-51-und-die-Konstante-stimmt`() {
        assertThat(database(51).getInt("version")).isEqualTo(51)
        assertThat(AppDatabase.SCHEMA_VERSION).isEqualTo(51)
    }

    @Test
    fun `51-ist-50-plus-eine-Spalte-in-automation_settings`() {
        val before = entities(50)
        val after = entities(51)

        // Keine Tabelle kommt hinzu, keine verschwindet.
        assertThat(after.keys).containsExactlyElementsIn(before.keys)

        // Alle Bestandstabellen bleiben identisch — außer automation_settings.
        val beforeRest = (before - "automation_settings").mapValues { it.value.toString() }
        val afterRest = (after - "automation_settings").mapValues { it.value.toString() }
        assertThat(afterRest).isEqualTo(beforeRest)

        // automation_settings unterscheidet sich NUR um die neue Spalte.
        // (JSONObject hat keine Wert-Gleichheit — deshalb String-Snapshots.)
        val beforeFields = fields(before.getValue("automation_settings"))
            .mapValues { it.value.toString() }
        val afterFields = fields(after.getValue("automation_settings"))
            .mapValues { it.value.toString() }
        assertThat(afterFields.keys)
            .containsExactlyElementsIn(beforeFields.keys + "screen_recording_activity_type_id")
        assertThat(afterFields - "screen_recording_activity_type_id")
            .isEqualTo(beforeFields)
    }

    @Test
    fun `neue-Spalte-ist-NOT-NULL-mit-Default-digital`() {
        val field = fields(entities(51).getValue("automation_settings"))
            .getValue("screen_recording_activity_type_id")

        assertThat(field.getString("columnName")).isEqualTo("screen_recording_activity_type_id")
        assertThat(field.getString("affinity")).isEqualTo("TEXT")
        assertThat(field.getBoolean("notNull")).isTrue()
        assertThat(field.getString("defaultValue")).isEqualTo("'digital'")
        // M28: Das Entity-Feld muss exakt denselben Default tragen — sonst
        // kippt Room beim Validieren des Schemas.
        assertThat(AutomationSettings().screenRecordingActivityTypeId).isEqualTo("digital")
    }
}
