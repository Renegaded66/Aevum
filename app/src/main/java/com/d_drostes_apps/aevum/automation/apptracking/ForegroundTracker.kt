package com.d_drostes_apps.aevum.automation.apptracking

/**
 * M20: Vordergrund-Zustand der UsageStats-Faltung — jetzt auf ACTIVITY-Ebene
 * (Paket + Activity-Klasse) statt nur Paket-Ebene.
 *
 * WARUM (Nutzer-Report: nach Handy-Neustart startete „Digital" statt der
 * zugewiesenen App-Activity; die App-Aufzeichnung begann erst am Ende der
 * Sitzung): Auf Paket-Ebene leert JEDES PAUSED/STOPPED derselben App den
 * Vordergrund — auch wenn es nur der Activity-WECHSEL innerhalb der App ist
 * (Splash → Haupt-Activity: PAUSED(Splash) … RESUMED(Haupt) …
 * STOPPED(Splash)). Steht das abschließende STOPPED als letztes Event im
 * Fenster, endete die Faltung auf `null` — obwohl die App offen ist. Ohne
 * weitere Übergangs-Events (der Nutzer schaut einfach zu) blieb der Zustand
 * dauerhaft „nichts im Vordergrund": Der Service hielt die App für
 * verlassen, „Digital" bekam den Vortritt.
 *
 * MIT Activity-Ebene räumt ein PAUSED/STOPPED den Zustand nur noch, wenn es
 * DIESELBE Activity betrifft, die zuletzt in den Vordergrund kam:
 *
 *  - Splash→Main (beliebige Event-Reihenfolge): Ergebnis = (App, Main) ✓
 *  - App schließen (STOPPED der aktuellen Activity): Ergebnis = null ✓
 *  - Klassen fehlen auf einer Seite (OEM-Event ohne className): Vergleich
 *    fällt auf Paket-Ebene zurück (= altes Verhalten, kein Rückschritt).
 *
 * Kein Android-Import — bewusst rein (JVM-Test).
 */
data class Foreground(val packageName: String, val className: String?)

object ForegroundTracker {

    fun next(
        current: Foreground?,
        packageName: String,
        className: String?,
        isForegroundEvent: Boolean,
        isBackgroundEvent: Boolean
    ): Foreground? {
        // Events ohne Paketnamen tragen nichts zur Faltung bei (Sonderfälle
        // einzelner OEM-Event-Typen) — Zustand bleibt unangetastet.
        if (packageName.isBlank()) return current
        val normalizedClass = normalizeClass(packageName, className)
        return when {
            isForegroundEvent -> Foreground(packageName, normalizedClass)
            isBackgroundEvent && matches(current, packageName, normalizedClass) -> null
            else -> current
        }
    }

    /**
     * Kurzform und Langform derselben Activity normalisieren: MOVE_TO_FOREGROUND
     * liefert auf manchen Geräten "com.pkg.MainActivity", ACTIVITY_RESUMED
     * dagegen ".MainActivity" — ohne Normalisierung würden die Familien sich
     * gegenseitig nicht erkennen und Background-Events die falsche Activity
     * leeren.
     */
    fun normalizeClass(packageName: String, className: String?): String? {
        val cls = className?.takeIf { it.isNotBlank() } ?: return null
        return when {
            cls.startsWith("$packageName.") -> cls.removePrefix("$packageName.")
            cls.startsWith(".") -> cls.removePrefix(".")
            else -> cls
        }
    }

    /**
     * Betrifft das Background-Event die aktuell gemerkte Activity?
     *  - anderes Paket → nein (alte Regel: fremde App ändert nichts),
     *  - gleiches Paket, Klasse auf einer Seite unbekannt → ja (Paket-Ebene
     *    als Rückfall — identisch zum M18.67-Verhalten),
     *  - sonst: Klassen-Gleichheit.
     */
    private fun matches(current: Foreground?, packageName: String, className: String?): Boolean {
        if (current == null || current.packageName != packageName) return false
        if (current.className == null || className == null) return true
        return current.className == className
    }
}
