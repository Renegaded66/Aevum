package com.d_drostes_apps.aevum.ui.screens.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * Entscheidet, ob der Willkommens-Dialog (M34) erscheinen muss.
 *
 * ── WARUM NICHT EINFACH „ERSTER START = ONBOARDING" ──────────────────────
 *
 * Devon (Auftrag): „wenn man die App neu installiert (die Änderung
 * beeinflusst also nicht bestehende Installationen)". Ein bloßes
 * „Flag noch nicht gesetzt" trifft das NICHT: jede bestehende Installation,
 * die dieses Update bekommt, hat das Flag ebenfalls nicht — und bekäme den
 * Wizard vorgesetzt, obwohl sie längst eingerichtet ist.
 *
 * Deshalb zwei Bedingungen:
 *
 *  1. Das Abschluss-Flag ist nicht gesetzt ([markCompleted]).
 *  2. Es ist eine NEUINSTALLATION. Erkennungsmerkmal:
 *     `firstInstallTime ≈ lastUpdateTime`. Bei einer frischen Installation
 *     setzt Android beide Zeitstempel im selben Moment; bei einem Update
 *     bleibt `firstInstallTime` der alte und `lastUpdateTime` wandert nach
 *     vorn. Die Toleranz [FRESH_INSTALL_WINDOW_MS] fängt Rundungen im
 *     Paketmanager ab.
 *
 * Ist (2) falsch und (1) nicht gesetzt, wird das Flag SOFORT gesetzt: der
 * Nutzer hat die App schon benutzt, das Onboarding ist für ihn erledigt.
 * Damit ist die Entscheidung nach dem ersten Start stabil und die Prüfung
 * läuft nur einmal.
 */
object OnboardingGate {

    const val PREFS_NAME = "aevum_onboarding"

    /** Abschluss des Wizards. Version im Namen: eine neue Wizard-Generation
     *  kann später bewusst erneut fragen, ohne alte Flags zu missdeuten. */
    const val KEY_COMPLETED = "wizard_v1_completed"

    /** Zwei Minuten: „gleicher Moment" inkl. Paketmanager-Rundung. */
    private const val FRESH_INSTALL_WINDOW_MS = 2L * 60L * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /**
     * Soll der Wizard jetzt gezeigt werden? Setzt bei einer bestehenden
     * Installation zugleich das Abschluss-Flag (siehe Klassendoku).
     */
    fun shouldShow(context: Context): Boolean {
        if (isCompleted(context)) return false
        if (isFreshInstall(context)) return true
        // Bestehende Installation: Wizard überspringen und nie wieder fragen.
        markCompleted(context)
        return false
    }

    /**
     * Neuinstallation? `true`, wenn Installations- und Update-Zeitstempel
     * dicht beieinanderliegen.
     *
     * Ausgelagert und `internal`, damit die Regel ohne Android-Rahmen
     * prüfbar ist (OnboardingGateTest): Die Entscheidung „Wizard zeigen oder
     * nicht" ist die einzige Stelle, an der ein Fehler eine bestehende
     * Installation unterbricht.
     */
    internal fun isFreshInstall(firstInstallTime: Long, lastUpdateTime: Long): Boolean {
        val delta = lastUpdateTime - firstInstallTime
        return delta in 0..FRESH_INSTALL_WINDOW_MS
    }

    /**
     * Neuinstallation? Im Zweifel (Paketmanager-Fehler) wird `false`
     * geliefert — ein bestehender Nutzer soll nicht durch einen Wizard
     * unterbrochen werden; ein neuer Nutzer verliert dadurch nur den
     * Wizard, nie seine Daten.
     */
    private fun isFreshInstall(context: Context): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            isFreshInstall(info.firstInstallTime, info.lastUpdateTime)
        } catch (e: Exception) {
            android.util.Log.e("OnboardingGate", "Paket-Zeitstempel nicht lesbar", e)
            false
        }
    }
}
