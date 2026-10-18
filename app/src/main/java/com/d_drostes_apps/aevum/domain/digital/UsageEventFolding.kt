package com.d_drostes_apps.aevum.domain.digital

/**
 * M21: Faltung von UsageStats-Events zu Vordergrund-Phasen — auf
 * **Activity-Ebene** mit **Paket-Vereinigung**.
 *
 * ── WARUM (Nutzer-Report: „Duolingo fehlt komplett in Digital Balance,
 *     Aevum sagt 5 Minuten, das System 10 Minuten") ──────────────────────
 *
 * Die bisherige Faltung war auf PAKET-Ebene: `MOVE_TO_BACKGROUND` /
 * `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED` **jedes** Events hat die Phase des
 * ganzen Pakets beendet. Beim App-Start ist aber kurz eine Splash-Activity
 * aktiv, die beim Wechsel zur Haupt-Activity ein `ACTIVITY_PAUSED` schickt:
 *
 *     RESUMED(Splash) t0 → RESUMED(Main) t1 → PAUSED(Splash) t2 …
 *
 * Mit Paket-Schlüsseln überschreibt t1 den Start (t0–t1 verloren) und t2
 * beendet die Phase KOMPLETT — obwohl Main noch offen ist. Ergebnis: Die
 * ganze App-Sitzung zählt ~0 ms; in der App-Liste fällt sie unter den
 * 1-s-Filter und „taucht gar nicht auf", die Tagessumme ist zu klein.
 * Betroffen sind alle Apps mit Splash-Screen — also fast alle.
 *
 * ── DIE REGELN ────────────────────────────────────────────────────────
 *
 *  - Jedes Foreground-Event öffnet ein Fenster für (Paket, Activity).
 *  - Ein Background-Event schließt NUR das Fenster DERSELBEN Activity.
 *  - Passt die Activity-Kennung nicht (OEM liefert gekürzte/fehlende
 *    Klassennamen), wird das ÄLTESTE offene Fenster des Pakets geschlossen —
 *    das ist bei Splash→Main die Splash-Activity, also genau die richtige.
 *  - Am Ende werden die Activity-Fenster je Paket zu **disjunkten** Phasen
 *    vereinigt (Überlappungen zählen nie doppelt).
 *  - Offene Fenster (App noch im Vordergrund) laufen bis zum Fenster-Ende.
 *
 * Bewusst reine Kotlin-Logik ohne Android-Importe — JVM-testbar
 * ([UsageEventFoldingTest]).
 */
object UsageEventFolding {

    /** Eine zusammengefasste Vordergrund-Phase eines Pakets. */
    data class Phase(
        val packageName: String,
        val start: Long,
        val end: Long
    ) {
        val durationMs: Long get() = (end - start).coerceAtLeast(0L)
    }

    /**
     * Zustandsbehaftete Faltung für EINEN Query-Zeitraum.
     *
     * @param clipStart Untergrenze für Startzeitpunkte. Manche OEMs liefern
     *        Events mit Timestamps VOR dem Query-Fenster (z. B. ein
     *        MOVE_TO_FOREGROUND von gestern 23:50 bei einem Query ab heute
     *        00:00). Ohne Clipping würde die Phase ab gestern zählen.
     */
    class Folder(private val clipStart: Long) {

        /** Offene Fenster: "paket/activity" → Startzeitpunkt. */
        private val open = LinkedHashMap<String, Long>()
        private val finished = ArrayList<Phase>()

        /** Anzahl Foreground-Events (Unlock-Zähler der Heute-Statistik). */
        var foregroundEvents: Int = 0
            private set

        fun onForeground(packageName: String, className: String?, timestamp: Long) {
            if (packageName.isBlank()) return
            foregroundEvents++
            val key = key(packageName, className)
            // Doppeltes RESUMED derselben Activity ohne PAUSED (z. B.
            // Screen-Zyklus): dieselbe durchgehende Sitzung — der frühere
            // Start bleibt stehen, nichts wird doppelt geöffnet.
            if (open.containsKey(key)) return
            open[key] = maxOf(timestamp, clipStart)
        }

        fun onBackground(packageName: String, className: String?, timestamp: Long) {
            if (packageName.isBlank()) return
            val key = key(packageName, className)
            var start = open.remove(key)
            if (start == null) {
                // OEM-Fallback: Kennung passt nicht (fehlender oder
                // abweichender Klassenname). Das ÄLTESTE offene Fenster
                // dieses Pakets ist gemeint (Splash zuerst geöffnet).
                val candidates = open.entries
                    .filter { it.key.startsWith("$packageName/") }
                    .sortedBy { it.value }
                if (candidates.isNotEmpty()) {
                    start = open.remove(candidates.first().key)
                }
            }
            if (start != null && timestamp > start) {
                finished += Phase(packageName, start, timestamp)
            }
        }

        /**
         * Schließt offene Fenster zum Zeitpunkt [end] (App noch im
         * Vordergrund) und liefert die je Paket VEREINIGTEN Phasen
         * (disjunkt, aufsteigend).
         */
        fun finish(end: Long): List<Phase> {
            open.entries.toList().forEach { (k, start) ->
                if (end > start) {
                    finished += Phase(k.substringBefore('/'), start, end)
                }
            }
            open.clear()
            return mergePerPackage(finished)
        }

        private fun mergePerPackage(phases: List<Phase>): List<Phase> {
            val result = ArrayList<Phase>()
            phases.groupBy { it.packageName }.forEach { (pkg, list) ->
                val sorted = list.sortedBy { it.start }
                var start = sorted.first().start
                var end = sorted.first().end
                for (phase in sorted.drop(1)) {
                    if (phase.start <= end) {
                        if (phase.end > end) end = phase.end
                    } else {
                        result += Phase(pkg, start, end)
                        start = phase.start
                        end = phase.end
                    }
                }
                result += Phase(pkg, start, end)
            }
            return result.sortedWith(compareBy({ it.packageName }, { it.start }))
        }

        private fun key(packageName: String, className: String?): String =
            "$packageName/${normalizeClass(packageName, className)}"

        /**
         * Kurz- und Langform derselben Activity vereinheitlichen:
         * MOVE_TO_FOREGROUND liefert teils "com.pkg.MainActivity",
         * ACTIVITY_RESUMED teils ".MainActivity" — ohne Normalisierung
         * würden sich die Familien gegenseitig nicht erkennen.
         */
        private fun normalizeClass(packageName: String, className: String?): String {
            val cls = className?.takeIf { it.isNotBlank() } ?: return ""
            return when {
                cls.startsWith("$packageName.") -> cls.removePrefix("$packageName.")
                cls.startsWith(".") -> cls.removePrefix(".")
                else -> cls
            }
        }
    }
}
