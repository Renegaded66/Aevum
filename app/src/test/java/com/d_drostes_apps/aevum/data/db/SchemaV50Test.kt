package com.d_drostes_apps.aevum.data.db

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * M27: Schema-Guard für v50 — Sucht-Detektor.
 *
 * v50 ist rein additiv: zwei neue Tabellen, keine Bestandstabelle ändert
 * sich. Der Daten-Beweis (echte SQLite aus 49.json → Room) läuft in
 * [AddictionMigrationTest].
 */
class SchemaV50Test {

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
    fun `Schema-Version-ist-50-und-die-Konstante-stimmt`() {
        assertThat(database(50).getInt("version")).isEqualTo(50)
        assertThat(AppDatabase.SCHEMA_VERSION).isEqualTo(50)
    }

    @Test
    fun `50-ist-49-plus-Sucht-Detektor-Tabellen`() {
        val before = entities(49)
        val after = entities(50)

        assertThat(after.keys).containsExactlyElementsIn(
            before.keys + setOf("addiction_watch_app", "addiction_session")
        )

        // Alle Bestandstabellen bleiben Element für Element identisch.
        val beforeRest = before.mapValues { it.value.toString() }
        val afterRest = (after - "addiction_watch_app" - "addiction_session")
            .mapValues { it.value.toString() }
        assertThat(afterRest).isEqualTo(beforeRest)
    }

    @Test
    fun `addiction_watch_app-hat-genau-die-erwarteten-Spalten`() {
        val entity = entities(50).getValue("addiction_watch_app")
        assertThat(columns(entity)).containsExactly(
            "package_name", "app_label", "added_at", "sort_order"
        ).inOrder()
    }

    @Test
    fun `addiction_session-hat-zwei-Indizes-und-keine-Fremdschluessel`() {
        val entity = entities(50).getValue("addiction_session")
        assertThat(columns(entity)).containsExactly(
            "id", "package_name", "start_at", "end_at", "duration_ms"
        ).inOrder()

        val indices = entity.getJSONArray("indices")
        val names = (0 until indices.length()).map { indices.getJSONObject(it).getString("name") }
        assertThat(names).containsExactly(
            "index_addiction_session_package_name",
            "index_addiction_session_start_at"
        )

        assertThat(entity.optJSONArray("foreignKeys")?.length() ?: 0).isEqualTo(0)
    }
}
