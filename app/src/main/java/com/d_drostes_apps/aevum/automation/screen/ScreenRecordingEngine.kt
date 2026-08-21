package com.d_drostes_apps.aevum.automation.screen

/**
 * M18.70: Bildschirm-Aufzeichnung — pure Entscheidungslogik.
 *
 * Regel (User-Spec):
 *  - Jedes Mal, wenn das Handy mindestens x Minuten am Stück an ist
 *    UND gerade nichts anderes aufzeichnet → „Digital"-Session starten
 *    mit x Minuten Vorlaufzeit (startedAt = now − x min).
 *  - x = 0 → sofort bei Screen-ON starten (ohne Vorlauf).
 *  - x = -1 (DEACTIVATED) → nie automatisch starten.
 *  - Screen-OFF → Aufzeichnung IMMER stoppen (unabhängig von x).
 *
 * Bewusst als pure Funktionen — unit-testbar ohne Android.
 */
object ScreenRecordingEngine {

    /** Slider-Endwert: ganz rechts = deaktiviert. */
    const val DEACTIVATED = -1

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

    /** Screen-OFF stoppt die Aufzeichnung IMMER (unabhängig von x). */
    fun shouldStopOnScreenOff(): Boolean = true
}
