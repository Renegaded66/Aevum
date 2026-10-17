package com.d_drostes_apps.aevum.automation.apptracking

/**
 * M20: Reine Auflösungs-Regel „Welche App ist gerade im Vordergrund?" für den
 * [com.d_drostes_apps.aevum.automation.screen.ScreenRecordingWorker] (Prüfung,
 * ob „Digital" starten darf). Bewusst ohne Android-Abhängigkeiten — dadurch
 * JVM-getestet (die einzelnen Zulieferer sind es schon; hier wird nur die
 * Kombinations-Regel festgenagelt).
 *
 * Nutzer-Report (Ausgangsfall): Frisch geöffnete App nach Handy-Neustart —
 * „Nach 2 Minuten hat die Aufzeichnung digital gestartet." Obwohl eine
 * GETRACKTE App offen war. Genau hier entscheidet diese Regel:
 *
 *  1. Frischer Service-Schnappschuss (der Service pollt alle 2 s im
 *     Vordergrund-Takt): AUTORITATIV — auch ein `null` (Homescreen) ist
 *     eine belastbare Aussage, eine Einmal-Abfrage kann es nicht besser
 *     wissen.
 *  2. Sonst: kurze Faltung (2 min) — erkennt gerade geöffnete ODER gerade
 *     verlassene Apps sofort.
 *  3. Ist AUCH die leer (App liegt „ruhig" im Vordergrund, keine
 *     Übergangs-Events im Fenster — der M18.151-Praxis-Fall), entscheidet
 *     die WEITE Faltung (12 h Baseline). VORHER fiel diese Situation auf
 *     „nichts im Vordergrund" zurück und „Digital" startete fälschlich.
 */
object AppForegroundResolution {

    fun pick(
        snapshotFresh: Boolean,
        snapshotPackage: String?,
        shortFold: String?,
        wideFold: String?
    ): String? = if (snapshotFresh) snapshotPackage else (shortFold ?: wideFold)
}
