package com.d_drostes_apps.aevum.data.repository

import android.content.Context
import com.d_drostes_apps.aevum.domain.projects.PmCelebrationGate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M19.1: Persistenter Feierstand — verhindert das Dauerfeuerwerk.
 *
 * Warum SharedPreferences und nicht Room/DataStore: Der Wert ist ein einziger
 * Tag + eine Zahl, wird synchron im ersten Frame gelesen und muss einen
 * Prozess-Neustart überleben. Er ist kein Fachdatum (kein Export, kein Backup),
 * sondern eine Anzeigemarke — genau wie das gespiegelte Theme
 * ([ThemeRepository]). Deshalb dieselbe Technik wie dort.
 */
@Singleton
class PmCelebrationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun state(): PmCelebrationGate.State = PmCelebrationGate.State(
        date = prefs.getString(KEY_DATE, null),
        celebratedDone = prefs.getInt(KEY_DONE, 0)
    )

    fun save(state: PmCelebrationGate.State) {
        prefs.edit()
            .putString(KEY_DATE, state.date)
            .putInt(KEY_DONE, state.celebratedDone)
            .apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_DATE).remove(KEY_DONE).apply()
    }

    companion object {
        const val PREFS_NAME = "aevum_pm"
        const val KEY_DATE = "pm_celebrated_date"
        const val KEY_DONE = "pm_celebrated_done"
    }
}
