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
 *  - Screen-OFF → Aufzeichnung erst stoppen, wenn der Screen
 *    [SCREEN_OFF_STOP_DELAY_MS] (30 s) am Stück aus war — nicht sofort.
 *    (M18.71: Der User schaltet den Screen oft nur kurz aus, z. B. um
 *    das Handy in die Tasche zu stecken oder einen Anruf anzunehmen —
 *    die Digital-Aufzeichnung soll dann weiterlaufen.)
 *
 * Bewusst als pure Funktionen — unit-testbar ohne Android.
 */
object ScreenRecordingEngine {

    /** Slider-Endwert: ganz rechts = deaktiviert. */
    const val DEACTIVATED = -1

    /** M18.71: Screen-OFF muss 30 s am Stück dauern, bevor die
     *  Digital-Aufzeichnung gestoppt wird. Kurzes Ausschalten
     *  (Tasche, Anruf) unterbricht die Aufzeichnung nicht. */
    const val SCREEN_OFF_STOP_DELAY_MS = 30_000L

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
     * M18.71: Soll die Screen-Aufzeichnung wegen Screen-OFF gestoppt werden?
     *
     * Der Screen muss [SCREEN_OFF_STOP_DELAY_MS] (30 s) am Stück aus sein,
     * bevor die Digital-Aufzeichnung beendet wird. Kurzes Ausschalten
     * (Tasche, Anruf, Display-Taste) unterbricht die Aufzeichnung nicht.
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
