package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * M18.127-INTEGRATION: Walk-Stop-Erkennung auf BRIDGE-Ebene
 * (Kanban t_ad14b740) — der Receiver (Android-/GMS-gebunden) füttert
 * `ActivityRecognitionBridge.onWalkStopSample()`; hier wird genau dieser
 * Pfad mit realistischen GPS-Probe-Puffern simuliert.
 *
 * Szenarien der Task-Spec:
 *  1. Gehen erkannt WÄHREND der Fahrt an einer roten Ampel:
 *     - kurze Ampelphase (< 75 s Gnadenfrist) → KEIN Stop (M18.84)
 *     - Ampelphase mit durchgehendem WALKING ≥ 75 s + 0-m/s-Probes
 *       (asymmetrisches Veto: Stillstand widerlegt Gehen nicht) → Stop
 *  2. Gehen erkannt NACH dem Parken → Stop nach Gnadenfrist
 *     (auch wenn der Ausstiegs-GPS 0 m/s liefert — Parkhaus-Canyon)
 *  3. KEINE Geh-Erkennung + 5 Min Stillstand → der Watchdog-Fallback
 *     stoppt (hier testbar: Stillstands-Probes refresh den Herzschlag
 *     NICHT — der 5-Min-Zähler kann also nie verlängert werden; die
 *     Watchdog-Konstante selbst sichert der Regressionstest ab).
 *
 * Zusätzlich die Vehicle-Protection-Fälle auf Bridge-Ebene:
 *  - frischer 9-m/s-Probe → Veto + Evidenz-Reset
 *  - IN_VEHICLE-Sample dazwischen → Evidenz-Reset (Fahrt lebt)
 *  - Session-Grenzen: resetWalkStopEvidence() nach einem Stop →
 *    die Kette der alten Fahrt zündet nicht in die nächste hinein.
 *
 * Reine JVM-Tests (kein Robolectric) — Muster ActivitySessionLifecycleTest.
 */
class WalkStopIntegrationTest {

    private fun bridge() = ActivityRecognitionBridge(
        object : AutomationSettingsRepository {
            override fun get() = flowOf(AutomationSettings())
            override suspend fun upsert(s: AutomationSettings) {}
        }
    )

    private val t0 = 1_000_000L

    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 12f
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = null,
        latitude = 50.0,
        longitude = 8.0
    )

    private fun walking(
        b: ActivityRecognitionBridge,
        now: Long,
        confidence: Int = 80
    ) = b.onWalkStopSample(
        type = WalkStopDetector.TYPE_WALKING,
        confidence = confidence,
        nowMs = now
    )

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 1: Gehen erkannt an der roten Ampel
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `rote Ampel kurz - WALKING unter 75s - KEIN Stop`() {
        // M18.84: Google meldet WALKING auch in Stop&Go-Fahrten (Anfahren/
        // Kriechen). Eine kurze Ampelphase mit 0-m/s-Probes darf die
        // Fahrt nicht beenden.
        val b = bridge()
        val redLight = probe(t0, 0.0f)
        b.addDriveProbe(redLight, refreshHeartbeat = false)

        // Sample 1 (t0) startet die Evidenz; +30s und +60s sind noch
        // innerhalb der 75s-Gnadenfrist. Der 0-m/s-Probe (frisch, Alter
        // 0/30/60s) vetoiert NICHT (asymmetrisch).
        assertThat(walking(b, t0)).isFalse()
        assertThat(walking(b, t0 + 30_000L)).isFalse()
        assertThat(walking(b, t0 + 60_000L)).isFalse()
    }

    @Test
    fun `rote Ampel lang - WALKING ueber 75s bei 0-m-s-GPS - Stop`() {
        // Die Ampel bleibt rot, Google meldet durchgehend WALKING (conf 80)
        // — nach 75 s Gnadenfrist ist das kein Stop&Go mehr, sondern
        // Stillstand mit Geh-Signal → die Fahrt endet. Der 0-m/s-Probe
        // (Alter 90s = exakt an der Frischegrenze) vetoiert NICHT.
        val b = bridge()
        b.addDriveProbe(probe(t0, 0.0f), refreshHeartbeat = false)

        assertThat(walking(b, t0)).isFalse()
        assertThat(walking(b, t0 + 30_000L)).isFalse()
        assertThat(walking(b, t0 + 60_000L)).isFalse()
        // Sample bei +90s (30s-Takt): 90s ≥ 75s Gnadenfrist → STOPP.
        assertThat(walking(b, t0 + 90_000L)).isTrue()
    }

    @Test
    fun `nach Stop-Signal reset die Kette - kein Doppel-Feuer in dieselbe Fahrt`() {
        // Der Receiver ruft nach einem Stop-Trigger resetWalkStopEvidence()
        // — die bereits gezündete Evidenz darf nicht erneut feuern.
        val b = bridge()
        assertThat(walking(b, t0)).isFalse()
        assertThat(walking(b, t0 + 90_000L)).isTrue()

        b.resetWalkStopEvidence()
        assertThat(walking(b, t0 + 120_000L)).isFalse() // frischer Start
        assertThat(walking(b, t0 + 210_000L)).isTrue()  // +90s → wieder Stop
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 2: Gehen erkannt NACH dem Parken
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `nach dem Parken ohne GPS-Fix - Stop nach Gnadenfrist`() {
        // Ausgestiegen im Parkhaus-Canyon: keine frischen Probes (kein
        // Veto-Material), Google meldet WALKING → nach 2 Samples bzw.
        // ≥ 75 s hält der Detektor den Ausstieg für wahrscheinlich.
        val b = bridge()
        assertThat(walking(b, t0)).isFalse()
        assertThat(walking(b, t0 + 30_000L)).isFalse()
        assertThat(walking(b, t0 + 90_000L)).isTrue()
    }

    @Test
    fun `nach dem Parken mit 0-m-s-Ausstiegs-GPS - Stop und kein Heartbeat-Refresh`() {
        // Der Parkplatz-GPS liefert 0 m/s (Fahrzeug steht). Das vetoiert
        // das Gehen NICHT (asymmetrisch) — der Stop feuert.
        // Zusätzlich (Szenario 3-Grundlage): ein Stillstands-Probe mit
        // refreshHeartbeat=false verlängert den 5-Min-Watchdog NICHT.
        val b = bridge()
        b.addDriveProbe(probe(t0, 0.0f), refreshHeartbeat = false)

        assertThat(b.lastVehicleSample()).isEqualTo(0L) // kein Herzschlag

        assertThat(walking(b, t0)).isFalse()
        assertThat(walking(b, t0 + 90_000L)).isTrue()
    }

    @Test
    fun `frischer 9-m-s-Probe nach dem Parken - Veto, kein Stop, Evidenz-Reset`() {
        // Das Fahrzeug ROLLT noch (9 m/s = 32 km/h — kein Fußgänger,
        // M18.117). Der Walk-Stop wird vetoiert und die Evidenz verworfen;
        // erst nachdem der Veto-Probe aus dem Puffer ist, zählt eine neue
        // Kette von vorn.
        val b = bridge()
        b.addDriveProbe(probe(t0, 9.0f), refreshHeartbeat = false)

        assertThat(walking(b, t0 + 30_000L)).isFalse()
        assertThat(walking(b, t0 + 60_000L)).isFalse() // Veto aktiv, Reset

        // Veto-Probe aus dem Puffer (Stop-Paket drainet Probes) → neue
        // Kette startet bei diesem Sample.
        b.drainDriveProbes()
        assertThat(walking(b, t0 + 90_000L)).isFalse()
        assertThat(walking(b, t0 + 180_000L)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // Vehicle-Protection auf Bridge-Ebene
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `IN_VEHICLE-Sample waehrend der Geh-Kette - Evidenz-Reset`() {
        // M18.84: Google meldet WALKING auch in Stop&Go-Fahrten. Ein
        // IN_VEHICLE-Sample dazwischen bestätigt: Fahrt lebt → die
        // Geh-Evidenz wird verworfen und die Kette beginnt neu.
        val b = bridge()
        assertThat(walking(b, t0)).isFalse()
        assertThat(
            b.onWalkStopSample(
                type = WalkStopDetector.TYPE_IN_VEHICLE,
                confidence = 90,
                nowMs = t0 + 20_000L
            )
        ).isFalse()
        // Neue Kette ab +30s → Stop erst bei +120s (+90s).
        assertThat(walking(b, t0 + 30_000L)).isFalse()
        assertThat(walking(b, t0 + 120_000L)).isTrue()
    }

    @Test
    fun `ON_BICYCLE-Sample waehrend der Geh-Kette - Evidenz-Reset`() {
        val b = bridge()
        assertThat(walking(b, t0)).isFalse()
        assertThat(
            b.onWalkStopSample(
                type = WalkStopDetector.TYPE_ON_BICYCLE,
                confidence = 90,
                nowMs = t0 + 20_000L
            )
        ).isFalse()
        assertThat(walking(b, t0 + 30_000L)).isFalse()
        assertThat(walking(b, t0 + 120_000L)).isTrue()
    }

    @Test
    fun `Session-Grenze - resetWalkStopEvidence verhindert Alt-Evidenz in der naechsten Fahrt`() {
        // DriveStartWorker ruft resetWalkStopEvidence() beim Start (und
        // jeder Stop-Pfad beim Stop): Ein Geh-Sample kurz vor dem Start
        // (Losgehen → Auto) darf den Stop-Mechanismus der NEUEN Fahrt
        // nicht sofort auslösen.
        val b = bridge()
        // Alte Fahrt: Walking-Evidenz vorhanden, aber nie ge-featured.
        assertThat(walking(b, t0)).isFalse()
        // Neue Fahrt beginnt → Bridge-Reset wie DriveStartWorker.
        b.resetWalkStopEvidence()
        // Auch > 75 s später zündet die Alt-Evidenz nicht — die Kette
        // startet erst mit einem NEUEN Geh-Sample.
        assertThat(walking(b, t0 + 120_000L)).isFalse()
        assertThat(walking(b, t0 + 210_000L)).isTrue()
    }

    @Test
    fun `Confidence unter 60 - kein Stop ueber die volle Gnadenfrist`() {
        // Verrauschte Einzel-Samples (Google-Doku: „individual predictions
        // may be noisy“) zählen nicht — auch nicht nach 90 s Dauer.
        val b = bridge()
        assertThat(walking(b, t0, confidence = 40)).isFalse()
        assertThat(walking(b, t0 + 30_000L, confidence = 40)).isFalse()
        assertThat(walking(b, t0 + 90_000L, confidence = 55)).isFalse()
        // Erst ab Confidence ≥ 60 startet echte Evidenz.
        assertThat(walking(b, t0 + 120_000L, confidence = 80)).isFalse()
        assertThat(walking(b, t0 + 210_000L, confidence = 80)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 3: KEINE Geh-Erkennung + 5 Min Stillstand
    // (Watchdog-Fallback — der Test beweist, dass der Walk-Stop-Pfad
    // hier NICHT feuert und der Stillstand den 5-Min-Zähler nicht
    // verlängert; die Watchdog-Konstante selbst + die Watchdog-Schedule
    // sichert WalkStopStopPathRegressionTest ab.)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `ohne Geh-Samples feuert der Walk-Stop nie - nur der Watchdog kann stoppen`() {
        val b = bridge()
        // Simulierte Ampel-/Park-Phase: 3 Stillstands-Probes über 6 Min.
        b.addDriveProbe(probe(t0, 0.0f), refreshHeartbeat = false)
        b.addDriveProbe(probe(t0 + 60_000L, 0.0f), refreshHeartbeat = false)
        b.addDriveProbe(probe(t0 + 360_000L, 0.0f), refreshHeartbeat = false)

        // Kein WALKING/RUNNING/ON_FOOT-Sample → kein Stop-Signal.
        assertThat(
            b.onWalkStopSample(type = 3, confidence = 100, nowMs = t0 + 360_000L) // STILL
        ).isFalse()
        // Herzschlag bleibt 0: Der DriveWatchdog (5 Min ohne Signal)
        // läuft unverlängert ab → die Session endet über den Fallback.
        assertThat(b.lastVehicleSample()).isEqualTo(0L)
    }

    @Test
    fun `bestaetigtes Fahrt-Signal refresht den Herzschlag - Watchdog lebt`() {
        // Gegenprobe: Ein als Fahrt klassifizierter Probe (refreshHeartbeat
        // = true, wie DriveProbeWorker bei Driving-Klassifikation) verlängert
        // den 5-Min-Watchdog — die Fahrt wird nicht beendet.
        val b = bridge()
        b.addDriveProbe(probe(t0, 12.0f), refreshHeartbeat = true)
        assertThat(b.lastVehicleSample()).isEqualTo(t0)
        assertThat(walking(b, t0 + 30_000L)).isFalse()
    }
}
