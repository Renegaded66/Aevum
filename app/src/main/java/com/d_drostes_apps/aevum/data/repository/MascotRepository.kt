package com.d_drostes_apps.aevum.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M19.5 — Maskottchen-Konfiguration (Devon-Wunsch: „so ein richtig süßes
 * putziges wesen", das man sich in den Einstellungen selbst erstellen kann,
 * ein-/ausschaltbar, Default AUS).
 *
 * Gleiches Muster wie ThemeRepository: DataStore als Quelle (Flow für die
 * UI), SharedPreferences-Spiegel für den synchronen Read (Dashboard soll
 * beim ersten Frame schon wissen, ob das Maskottchen an ist).
 *
 * Persistierte Eigenschaften:
 *  - enabled  : an/aus (Default aus — „Wenn aus ist soll alles so sein wie
 *               aktuell.")
 *  - colorId  : Körperfarbe aus fester Palette (8 niedliche Töne)
 *  - earStyle : Ohrenform (rund / spitz / lang)
 *  - cheeks   : Bäckchen an/aus
 *
 * Ungültige IDs (z. B. aus alten Ständen) fallen IMMER auf den Default
 * zurück — eine kaputte Pref darf nie das Maskottchen verhindern.
 */
@Singleton
class MascotRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    data class MascotConfig(
        val enabled: Boolean = false,
        val colorId: String = COLOR_DEFAULT,
        val earStyle: String = EARS_DEFAULT,
        val cheeks: Boolean = CHEEKS_DEFAULT
    )

    companion object {
        const val PREFS_NAME = "aevum_mascot"
        const val KEY_ENABLED = "mascot_enabled"
        const val KEY_COLOR = "mascot_color"
        const val KEY_EARS = "mascot_ears"
        const val KEY_CHEEKS = "mascot_cheeks"

        const val COLOR_DEFAULT = "ice"
        const val EARS_ROUND = "round"
        const val EARS_POINTY = "pointy"
        const val EARS_LONG = "long"
        const val EARS_DEFAULT = EARS_ROUND
        const val CHEEKS_DEFAULT = true

        /** Acht niedliche Körperfarben — bewusst in der Aevum-Farbwelt. */
        val COLOR_PALETTE: List<Pair<String, Color>> = listOf(
            "ice" to Color(0xFF7DD3FC),      // Eisblau (Brand)
            "mint" to Color(0xFF5EEAD4),     // Minze
            "apple" to Color(0xFF86EFAC),    // Apfelgrün
            "sun" to Color(0xFFFDE047),      // Sonnengelb
            "peach" to Color(0xFFFDBA74),    // Pfirsich
            "coral" to Color(0xFFFB7185),    // Koralle
            "rose" to Color(0xFFF9A8D4),     // Rosé
            "lavender" to Color(0xFFA78BFA)  // Lavendel
        )

        val EAR_STYLES: List<String> = listOf(EARS_ROUND, EARS_POINTY, EARS_LONG)

        fun colorFor(colorId: String): Color =
            COLOR_PALETTE.firstOrNull { it.first == colorId }?.second
                ?: COLOR_PALETTE.first().second

        fun isValidColor(colorId: String): Boolean =
            COLOR_PALETTE.any { it.first == colorId }

        fun isValidEarStyle(style: String): Boolean =
            EAR_STYLES.contains(style)

        private val KEY_ENABLED_P = booleanPreferencesKey(KEY_ENABLED)
        private val KEY_COLOR_P = stringPreferencesKey(KEY_COLOR)
        private val KEY_EARS_P = stringPreferencesKey(KEY_EARS)
        private val KEY_CHEEKS_P = booleanPreferencesKey(KEY_CHEEKS)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Aktuelle Konfiguration als Flow (Dashboard + Einstellungen). */
    val config: Flow<MascotConfig> = dataStore.data.map { it.toConfig() }

    /** Synchroner Read — erster Frame (Dashboard-Rebuild, Settings-Aufbau). */
    fun currentSync(): MascotConfig = MascotConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        colorId = prefs.getString(KEY_COLOR, COLOR_DEFAULT)
            ?.takeIf(::isValidColor) ?: COLOR_DEFAULT,
        earStyle = prefs.getString(KEY_EARS, EARS_DEFAULT)
            ?.takeIf(::isValidEarStyle) ?: EARS_DEFAULT,
        cheeks = prefs.getBoolean(KEY_CHEEKS, CHEEKS_DEFAULT)
    )

    private fun Preferences.toConfig(): MascotConfig {
        val colorId = this[KEY_COLOR_P] ?: COLOR_DEFAULT
        val earStyle = this[KEY_EARS_P] ?: EARS_DEFAULT
        return MascotConfig(
            enabled = this[KEY_ENABLED_P] ?: false,
            colorId = if (isValidColor(colorId)) colorId else COLOR_DEFAULT,
            earStyle = if (isValidEarStyle(earStyle)) earStyle else EARS_DEFAULT,
            cheeks = this[KEY_CHEEKS_P] ?: CHEEKS_DEFAULT
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ENABLED_P] = enabled }
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    suspend fun setColor(colorId: String) {
        val safe = if (isValidColor(colorId)) colorId else COLOR_DEFAULT
        dataStore.edit { it[KEY_COLOR_P] = safe }
        prefs.edit().putString(KEY_COLOR, safe).apply()
    }

    suspend fun setEarStyle(style: String) {
        val safe = if (isValidEarStyle(style)) style else EARS_DEFAULT
        dataStore.edit { it[KEY_EARS_P] = safe }
        prefs.edit().putString(KEY_EARS, safe).apply()
    }

    suspend fun setCheeks(cheeks: Boolean) {
        dataStore.edit { it[KEY_CHEEKS_P] = cheeks }
        prefs.edit().putBoolean(KEY_CHEEKS, cheeks).apply()
    }
}
