package com.d_drostes_apps.aevum.automation.apptracking

/** Die zuletzt sichtbare Activity eines Pakets (Klasse normalisiert). */
data class Foreground(val packageName: String, val className: String?)

/**
 * M21: Faltungszustand des Vordergrund-Trackers — **Vordergrund PLUS die
 * offenen Activities je Paket**.
 *
 * Bis M20 merkte sich der Tracker nur EINE Activity. Damit war ein
 * `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` ohne brauchbaren Klassennamen
 * (manche OEMs liefern `className == null`, andere eine abweichende
 * Schreibweise) nicht von „App verlassen" zu unterscheiden: der Fallback
 * auf Paket-Ebene leerte den Vordergrund mitten in der App — die
 * App-Aufzeichnung startete dann „Digital" statt der zugewiesenen Activity.
 * Mit der Menge offener Activities gilt: Solange noch eine Activity des
 * Pakets offen ist (Splash→Main!), bleibt das Paket im Vordergrund.
 */
data class ForegroundState(
    val current: Foreground? = null,
    /** Paket → normalisierte Klassen der offenen Activities ("" = unbekannt). */
    val openActivities: Map<String, Set<String>> = emptyMap()
) {
    companion object {
        val EMPTY = ForegroundState()
    }
}

/**
 * M18.151/M20/M21: Faltung der UsageStats-Foreground-Events auf
 * **Activity-Ebene** — pure Logik, JVM-getestet.
 *
 * Nutzer-sichtbare Semantik: „Vordergrund" ist die zuletzt
 * RESUMED/FOREGROUND-gemeldete Activity; sie bleibt es, bis keine Activity
 * ihres Pakets mehr offen ist. Events anderer Activities desselben Pakets
 * (Splash-Aufräumen) oder fremder Apps ändern nichts.
 */
object ForegroundTracker {

    fun next(
        state: ForegroundState,
        packageName: String,
        className: String?,
        isForegroundEvent: Boolean,
        isBackgroundEvent: Boolean
    ): ForegroundState {
        if (packageName.isBlank()) return state
        val normalized = normalizeClass(packageName, className)
        val key = normalized ?: ""

        return when {
            isForegroundEvent -> {
                // Fenster-Modell: nur das aktuelle Paket hält offene
                // Activities. Beim App-Wechsel werden die alten verworfen.
                val open = state.openActivities
                    .filterKeys { it == packageName }
                    .toMutableMap()
                open[packageName] = (open[packageName] ?: emptySet()) + key
                ForegroundState(
                    current = Foreground(packageName, normalized),
                    openActivities = open
                )
            }

            isBackgroundEvent -> {
                val openSet = state.openActivities[packageName] ?: return state
                val remaining = when {
                    // Exakte Activity schließt sich.
                    openSet.contains(key) -> openSet - key
                    // Kennung nicht vergleichbar (Event ohne Klasse ODER
                    // gemerkte Activity ohne Klasse) und nur EINE offen →
                    // die ist gemeint.
                    openSet.size == 1 && (key.isEmpty() || openSet.contains("")) -> emptySet()
                    // Mehrdeutig (z. B. Splash-Aufräumen bei bekanntem
                    // Splash): nichts anfassen — das Paket bleibt offen.
                    else -> openSet
                }
                // Die AKTUELLE Activity selbst endet → die App ist zu. Dann
                // zählen auch liegengebliebene Fenster (Splash-PAUSED ohne
                // Klassennamen) nicht mehr.
                val closesCurrent = state.current?.packageName == packageName &&
                    key == (state.current.className ?: "")
                if (remaining.isEmpty() || closesCurrent) {
                    val open = state.openActivities.toMutableMap()
                    open.remove(packageName)
                    val current = if (state.current?.packageName == packageName) null else state.current
                    return ForegroundState(current = current, openActivities = open)
                }
                ForegroundState(
                    current = state.current,
                    openActivities = state.openActivities + (packageName to remaining)
                )
            }

            else -> state
        }
    }

    /**
     * Kurz- und Langform derselben Activity vereinheitlichen:
     * MOVE_TO_FOREGROUND liefert teils "com.pkg.MainActivity",
     * ACTIVITY_RESUMED teils ".MainActivity" — ohne Normalisierung
     * würden sich die Familien gegenseitig nicht erkennen.
     * `null` = Klasse unbekannt.
     */
    fun normalizeClass(packageName: String, className: String?): String? {
        val cls = className?.takeIf { it.isNotBlank() } ?: return null
        return when {
            cls.startsWith("$packageName.") -> cls.removePrefix("$packageName.")
            cls.startsWith(".") -> cls.removePrefix(".")
            else -> cls
        }
    }
}
