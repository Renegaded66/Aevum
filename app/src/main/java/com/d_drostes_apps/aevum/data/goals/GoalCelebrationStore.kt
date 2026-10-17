package com.d_drostes_apps.aevum.data.goals

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M20: Merkt sich, für WELCHE Periode ein Ziel zuletzt gefeiert wurde.
 *
 * Die Feier (Konfetti + Vollbild-Karte, [com.d_drostes_apps.aevum.ui.screens.goals.GoalCelebrationOverlay])
 * soll genau EINMAL pro Ziel und Periode passieren — nicht bei jedem
 * Öffnen der Liste, solange das Ziel erfüllt bleibt. Der Schlüssel je Ziel
 * ist deshalb der Perioden-Schlüssel (z. B. "2026-10-17" täglich,
 * "W2026-42" wöchentlich, "2026-10" monatlich) statt eines simplen
 * Ja/Nein-Merkers wie im Rest der App (Wochenrückblick-Präzedenz):
 * dadurch feuert es morgen bzw. nächste Woche wieder.
 *
 * Wird ein Check-in zurückgenommen ([forget] aus dem ViewModel), darf die
 * Feier erneut kommen — sonst wäre „versehentlich abgehakt, korrigiert,
 * wieder abgehakt" endgültig still.
 */
@Singleton
class GoalCelebrationStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** true = diese Periode wurde für dieses Ziel noch nicht gefeiert. */
    fun shouldCelebrate(goalId: String, periodKey: String): Boolean =
        prefs.getString(key(goalId), null) != periodKey

    fun markCelebrated(goalId: String, periodKey: String) {
        prefs.edit().putString(key(goalId), periodKey).apply()
    }

    /** Beim Löschen eines Ziels und beim Rücknehmen von Check-ins. */
    fun forget(goalId: String) {
        prefs.edit().remove(key(goalId)).apply()
    }

    private fun key(goalId: String) = "celebrated_$goalId"

    companion object {
        private const val PREFS = "aevum_goal_celebrations"
    }
}
