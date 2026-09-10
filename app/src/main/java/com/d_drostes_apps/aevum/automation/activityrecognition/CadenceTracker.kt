package com.d_drostes_apps.aevum.automation.activityrecognition

import java.util.ArrayDeque

/**
 * M18.118: Schrittfrequenz-Schätzer (Cadence) aus dem Beschleunigungssensor.
 *
 * WARUM (User-Bug Kanban t_50a4847b, „Joggen 16 km/h wird als Autofahren
 * aufgezeichnet"): Der M18.117-Cadence-Veto in DriveDetectionEngine
 * (isJoggingCadence) war toter Code — kein Aufrufer hat je eine
 * Cadence-Messung geliefert (classify() wurde an allen 3 Call-Sites ohne
 * cadenceHz aufgerufen). Dieser Tracker ist die fehlende Messquelle.
 * Bewusst Android-frei (JVM-Unit-Tests mit synthetischen Sensor-Signalen,
 * gleiches Muster wie DriveDetectionEngine).
 *
 * Verfahren (Audit docs/activity-detection.md §4.2.2):
 *  - High-Pass: langsame EMA der Schwerkraft (τ = 2 s) abziehen.
 *  - Schritt = Nulldurchgang des hochpassgefilterten Betrags mit
 *    Mindest-Amplitude [minAmplitudeMps2]. Fahrzeug-Vibration ist
 *    hochfrequent (> 10 Hz) UND niederamplitudig (< 0,3 m/s²) — sie
 *    erzeugt keine Nulldurchgänge über der Schwelle.
 *  - Cadence = Schritte im letzten [windowMs]-Fenster / Fensterdauer.
 *  - [validFraction] = Anteil der letzten [historyWindows] Fenster mit
 *    messbarer Schrittfrequenz (≥ [minCadenceHz]).
 *
 * Akku: Der Tracker selbst ist zustandslos-teuer nur bei Fütterung. Die
 * Aufrufer (DriveDetectionService im CONFIRM-Burst, DriveProbeWorker bei
 * Fahrt-Verdacht) sampeln den Sensor NUR in ohnehin aktiven Fenstern —
 * kein 24/7-Sensor-Stream (M18.104-Akku-Prinzip bleibt unangetastet).
 */
class CadenceTracker(
    private val windowMs: Long = 10_000L,
    private val historyWindows: Int = 5,
    private val minAmplitudeMps2: Float = 0.4f,
    private val minCadenceHz: Float = 1.0f
) {

    private val stepTimes = ArrayDeque<Long>()
    private val windowCadences = ArrayDeque<Float>()
    private var gravityEma = 9.81f
    private var lastSign = 0
    private var lastSampleTsMs: Long? = null
    private var windowStartMs: Long? = null

    /** Beschleunigungs-Betrag (sqrt(x²+y²+z²)) in m/s² füttern. */
    fun addSample(timestampMs: Long, magnitude: Float) {
        val prevTs = lastSampleTsMs
        lastSampleTsMs = timestampMs
        val dtS = if (prevTs == null) 0.0 else (timestampMs - prevTs) / 1000.0
        val alpha = if (dtS <= 0.0) 0.1 else 1.0 - Math.exp(-dtS / 2.0)
        gravityEma += (alpha * (magnitude - gravityEma)).toFloat()

        val hp = magnitude - gravityEma
        val sign = when {
            hp > minAmplitudeMps2 -> 1
            hp < -minAmplitudeMps2 -> -1
            else -> 0
        }
        // NUR steigende Flanke zählt (Übergang -1 → +1): ein Schrittzyklus
        // hat genau EINEN solchen Übergang. Beide Flanken zu zählen würde
        // die Cadence verdoppeln (2 Zählungen pro Zyklus).
        if (sign == 1 && lastSign == -1) {
            stepTimes.addLast(timestampMs)
        }
        if (sign != 0) lastSign = sign

        while (stepTimes.isNotEmpty() && timestampMs - stepTimes.first() > windowMs) {
            stepTimes.removeFirst()
        }

        val ws = windowStartMs
        if (ws == null) {
            windowStartMs = timestampMs
        } else if (timestampMs - ws >= windowMs) {
            val cadence = stepTimes.size / (windowMs / 1000.0)
            windowCadences.addLast(cadence.toFloat())
            while (windowCadences.size > historyWindows) windowCadences.removeFirst()
            windowStartMs = timestampMs
        }
    }

    /** Aktuelle Schrittfrequenz (Hz) — null, wenn noch kein Fenster
     *  abgeschlossen wurde (Messung zu kurz) ODER kein Rhythmus messbar
     *  ist (Stillstand, Auto-Vibration: 0 Schritte → 0 Hz ist keine
     *  Messung, sondern das Fehlen einer Messung). */
    fun currentCadenceHz(): Float? {
        if (windowCadences.isEmpty()) return null
        val avg = windowCadences.average().toFloat()
        return if (avg < 0.5f) null else avg
    }

    /** Anteil der letzten Fenster mit messbarer Schrittfrequenz (0..1). */
    fun validFraction(): Float {
        if (windowCadences.isEmpty()) return 0f
        return windowCadences.count { it >= minCadenceHz } / windowCadences.size.toFloat()
    }
}
