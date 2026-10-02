package com.d_drostes_apps.aevum.automation.screen

/**
 * M18.70: Bildschirm-Aufzeichnung — pure Entscheidungslogik.
 *
 * M18.138 (User-Spec 2026-10-01) — ZWEI getrennte Rollen:
 *
 *  Die Aufzeichnung erzeugt Blöcke in der TIMELINE. Sie ist eine
 *  Darstellung, keine Messung. Für die Statistik (Dashboard, Insights,
 *  Weekly Review) zählt ausschließlich die Bildschirmzeit aus Digital
 *  Balance — siehe [com.d_drostes_apps.aevum.domain.digital.ScreenStatisticsPolicy].
 *
 * Regel (User-Spec):
 *  - Jedes Mal, wenn das Handy mindestens x Minuten am Stück an ist
 *    UND gerade nichts anderes aufzeichnet → „Digital"-Session starten
 *    mit x Minuten Vorlaufzeit (startedAt = now − x min).
 *  - x = 0 → sofort bei Screen-ON starten (ohne Vorlauf).
 *  - x = -1 (DEACTIVATED) → nie automatisch starten.
 *  - Die x Minuten beziehen sich NUR auf die Timeline: sie verhindern,
 *    dass jede 10-Sekunden-Nutzung ein eigenes Fragment erzeugt —
 *    „sodass auch nur größere Blöcke in der Timeline angezeigt werden" .
 *  - Screen-OFF → Aufzeichnung SOFORT stoppen. (M18.138: Die frühere
 *    M18.71-Regel mit 30 s Karenz ist damit aufgehoben — der User:
 *    „Allerdings soll die Aufzeichnung dann auch direkt stoppen, sobald
 *    man den Bildschirm wieder ausgemacht hat." Ein Block, der über das
 *    Weglegen des Handys hinausläuft, ist eine Falschaufzeichnung.)
 *
 * Bewusst als pure Funktionen — unit-testbar ohne Android.
 */
object ScreenRecordingEngine {

    /** Slider-Endwert: ganz rechts = deaktiviert. */
    const val DEACTIVATED = -1

    /**
     * M18.138: Kein Karenz-Delay mehr — Screen-OFF stoppt die Aufzeichnung
     * unmittelbar. Der Wert bleibt als Konstante bestehen, damit die
     * Delay-Logik im aufrufenden Worker unverändert weiterlesbar ist.
     */
    const val SCREEN_OFF_STOP_DELAY_MS = 0L

    /** Slider-Maximum (Minuten). Werte 0..MAX, MAX = deaktiviert. */
    const val SLIDER_MAX = 10

    /** DB-Wert für „deaktiviert" (Slider ganz rechts). */
    fun sliderToDb(sliderValue: Int): Int =
        if (sliderValue >= SLIDER_MAX) DEACTIVATED else sliderValue

    /** DB-Wert → Slider-Position (deaktiviert = ganz rechts). */
    fun dbToSlider(dbValue: Int): Int =
        if (dbValue == DEACTIVATED) SLIDER_MAX else dbValue.coerceIn(0, SLIDER_MAX)

    /**
     * Soll jetzt eine Screen-Aufzeichnung starten?
     *
     * @param screenOnSinceMs Zeitpunkt des letzten Screen-ON (System.currentTimeMillis)
     * @param now aktuelle Zeit
     * @param minutes konfigurierte Vorlaufzeit (0 = sofort, -1 = deaktiviert)
     * @param anythingRecording true, wenn bereits eine andere Session live ist
     */
    fun shouldStartRecording(
        screenOnSinceMs: Long,
        now: Long,
        minutes: Int,
        anythingRecording: Boolean
    ): Boolean {
        if (minutes == DEACTIVATED) return false
        if (anythingRecording) return false
        if (minutes == 0) return true
        val threshold = screenOnSinceMs + minutes * 60_000L
        return now >= threshold
    }

    /**
     * Startzeit der Session: bei Vorlaufzeit x Minuten → now − x min,
     * bei 0 → now. Die vorherigen x Minuten fallen rückwirkend in die
     * Aufzeichnung (User-Spec: „die vorherigen x Minuten im Nachhinein
     * auch in die Aufzeichnung mit reinfällt").
     */
    fun recordingStartTime(now: Long, minutes: Int): Long {
        if (minutes <= 0) return now
        return now - minutes * 60_000L
    }

    /**
     * M18.138: Soll die Screen-Aufzeichnung wegen Screen-OFF gestoppt werden?
     *
     * M18.138 (User-Spec 2026-10-01): Der Stop erfolgt SOFORT — die
     * Karenzzeit ist aufgehoben ([SCREEN_OFF_STOP_DELAY_MS] = 0). Der User
     * legt das Handy weg und erwartet, dass die Aufzeichnung endet:
     * „Allerdings soll die Aufzeichnung dann auch direkt stoppen, sobald
     * man den Bildschirm wieder ausgemacht hat."
     *
     * @param screenOffSinceMs Zeitpunkt des letzten Screen-OFF
     *        (System.currentTimeMillis), 0 wenn der Screen noch an ist
     * @param now aktuelle Zeit
     */
    fun shouldStopOnScreenOff(screenOffSinceMs: Long, now: Long): Boolean {
        if (screenOffSinceMs <= 0L) return false
        return now - screenOffSinceMs >= SCREEN_OFF_STOP_DELAY_MS
    }
}
