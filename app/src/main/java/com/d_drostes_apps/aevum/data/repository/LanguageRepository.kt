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
 * Persistiert die vom User gewählte App-Sprache.
 *
 * Werte: "system" (Standard — folgt der Systemsprache), "de", "en".
 *
 * Warum zusätzlich SharedPreferences? [AevumApplication.onCreate] und
 * [LocalizedActivity] müssen die Sprache SYNCHRON beim App-Start anwenden
 * (sonst blitzt die UI kurz in der falschen Sprache auf). DataStore ist
 * nur asynchron lesbar — der SharedPreferences-Spiegel wird bei jeder
 * Änderung mitgeschrieben und beim Start synchron gelesen.
 */
@Singleton
class LanguageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_DE = "de"
        const val LANGUAGE_EN = "en"

        /** Name des SharedPreferences-Spiegels — auch von LocalizedActivity/AevumApplication gelesen. */
        const val PREFS_NAME = "aevum_language"

        /** Key im SharedPreferences-Spiegel. */
        const val PREFS_KEY = "app_language"

        private val KEY_LANGUAGE = stringPreferencesKey("app_language")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Aktuelle Sprache als Flow (für die Settings-UI). */
    val language: Flow<String> = dataStore.data.map { it[KEY_LANGUAGE] ?: LANGUAGE_SYSTEM }

    /** Synchroner Read für App-Start / Activity-Creation. */
    fun currentLanguageSync(): String = prefs.getString(PREFS_KEY, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM

    suspend fun setLanguage(language: String) {
        dataStore.edit { it[KEY_LANGUAGE] = language }
        // Synchroner Spiegel für den nächsten Kaltstart.
        prefs.edit().putString(PREFS_KEY, language).apply()
    }
}
