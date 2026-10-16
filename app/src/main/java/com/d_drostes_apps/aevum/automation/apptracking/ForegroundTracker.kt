package com.d_drostes_apps.aevum.automation.apptracking

/**
 * M18.151: Reine Faltung von UsageStats-Foreground-Events — herausgezogen
 * aus [AppTrackingService.processForegroundEvents] und [ForegroundAppProbe],
 * damit die Semantik per Unit-Test festgenagelt ist:
 *
 *  - FOREGROUND/RESUMED → diese App ist der neue Vordergrund.
 *  - BACKGROUND/PAUSED/STOPPED NUR der aktuell gemerkten App → Vordergrund
 *    wird leer (Homescreen/Sperrbildschirm/App beendet).
 *  - Alle anderen Events (z. B. PAUSED einer anderen App) → unverändert.
 *
 * Kein Android-Import — bewusst rein (JVM-Test).
 */
object ForegroundTracker {

    fun next(
        current: String?,
        packageName: String,
        isForegroundEvent: Boolean,
        isBackgroundEvent: Boolean
    ): String? = when {
        isForegroundEvent -> packageName
        isBackgroundEvent && packageName == current -> null
        else -> current
    }
}
