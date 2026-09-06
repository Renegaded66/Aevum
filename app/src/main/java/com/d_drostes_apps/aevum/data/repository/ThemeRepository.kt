package com.d_drostes_apps.aevum.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.106: Persistiert das vom User gewählte App-Theme.
 *
 * Werte: "dark" (Standard — User-Wunsch: "default dark theme sein soll"),
 * "light", "system" (folgt dem Geräte-Theme).
 *
 * Gleiches Muster wie [LanguageRepository]: DataStore als Quelle (Flow für
 * die UI), SharedPreferences-Spiegel für den SYNCHRONEN Read beim App-Start
 * — sonst würde die App bei jedem Kaltstart kurz im falschen Theme
 * aufblitzen (DataStore ist nur asynchron lesbar).
 *
 * WARUM Dark als Default (User-Kontext): Der Daily-Tester nutzt ein
 * Motorola mit Dark-System-Theme — die App war dort "von Anfang an im
 * Dark Theme" und schien nicht umstellbar. Tatsächlich folgte sie
 * isSystemInDarkTheme(). Da Aevum optisch auf Dark ausgelegt ist
 * (Glassmorphism-Hero, Neon-Akzente), wird Dark jetzt der explizite
 * Standard — unabhängig vom System-Theme. "System" bleibt wählbar,
 * ebenso Light.
 */
@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        /** M18.106: Default = Dark (User-Anforderung). */
        const val THEME_DEFAULT = THEME_DARK

        const val PREFS_NAME = "aevum_theme"
        const val PREFS_KEY = "app_theme"

        private val KEY_THEME = stringPreferencesKey("app_theme")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Aktuelles Theme als Flow (für die Settings-UI). */
    val theme: Flow<String> = dataStore.data.map { it[KEY_THEME] ?: THEME_DEFAULT }

    /** Synchroner Read für App-Start / Activity-Creation. */
    fun currentThemeSync(): String = prefs.getString(PREFS_KEY, THEME_DEFAULT) ?: THEME_DEFAULT

    suspend fun setTheme(theme: String) {
        dataStore.edit { it[KEY_THEME] = theme }
        // Synchroner Spiegel für den nächsten Kaltstart.
        prefs.edit().putString(PREFS_KEY, theme).apply()
    }
}