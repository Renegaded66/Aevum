package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * M18.128-INTEGRATION (Kanban t_1c95af07): Fast-Start-Gate auf BRIDGE-
 * Ebene — der Receiver (Android-/GMS-gebunden) füttert
 * `ActivityRecognitionBridge.onVehicleSample()`/`onBicycleSample()`, der
 * DriveDetectionService liest `vehicleEvidence()` + `currentDriveProbes()`
 * und entscheidet mit `DriveDetectionEngine.shouldFastStart()` (V7-Cooldown
 * prüft der Aufrufer vorab). Hier wird genau dieser Pfad mit realistischen
 * GPS-Probe-Puffern simuliert — das JVM-Pendant zur handleFix-Logik.
 *
 * Szenarien der Task-Spec + Design §8/§11:
 *  1. Joggen 20 km/h (5,6 m/s), Egal was GPS meldet — ohne IN_VEHICLE-
 *     Sample ist die Evidence null → der Fast-Pfad ist tot (V1).
 *  2. Gehen (1,5 m/s) → nie.
 *  3. Fahrrad 25-30 km/h: ON_BICYCLE-Sample resetet die Evidence →
 *     selbst 8,3-m/s-Fixes starten nicht (V1-Konkurrenz).
 *  4. Fahrt 30er-Zone: IN_VEHICLE-Sample (conf 80) + 2× 8,3 m/s / 15 s /
 *     Netto 120 m → SOFORT-Start (Gate true).
 *  5. Stillstand mit Speed-Noise (Netto 0) → nie.
 *  6. Evidence-Lebenszyklus: Confidence < 60 zählt nicht; Evidence altert
 *     (90-s-Frische); Session-Grenzen reseten die Evidence (Start + Stop).
 *  7. V7: Restart-Cooldown blockt den Aufrufer-Pfad (3 Min nach Stopp).
 */
class FastStartBridgeIntegrationTest {

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
        accuracy: Float = 12f,
        displacementFromStartM: Double = 0.0,
        moving: Boolean = false
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        latitude = if (moving) 50.0 + displacementFromStartM / 111_229.0 else 50.0,
        longitude = 8.0
    )

    /**
     * Spiegel der handleFix-Entscheidung (DriveDetectionService M18.128):
     * Cooldown-Gate zuerst (V7), dann shouldFastStart mit den BRIDGE-
     * Daten — exakt die Reihenfolge des Produktionscodes.
     */
    private fun fastStartDecision(
        b: ActivityRecognitionBridge,
        nowMs: Long
    ): Boolean {
        if (b.isWithinDriveRestartCooldown(nowMs)) return false
        return DriveDetectionEngine.shouldFastStart(
            b.currentDriveProbes(),
            b.vehicleEvidence(),
            nowMs,
            b.currentGeofenceContext(),
            b.currentCadenceHz(),
            b.currentCadenceValidFraction()
        )
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 1+2: Joggen / Gehen — der Fast-Pfad feuert NIE
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Joggen 20kmh - keine IN_VEHICLE-Evidence im Receiver-Pfad - nie Fast-Start`() {
        // Der Sensor-Hub meldet beim Joggen RUNNING/ON_FOOT — der
        // Receiver registriert NIE onVehicleSample → vehicleEvidence()
        // bleibt null → V1 killt, egal welche GPS-Spikes der Puffer hat.
        val b = bridge()
        // Selbst 2 Multipath-Spikes ≥ 8 m/s über 15 s mit 120 m Netto:
        b.addDriveProbe(probe(t0, 8.5f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 8.5f, moving = true, displacementFromStartM = 120.0), false)

        assertThat(b.vehicleEvidence()).isNull()
        assertThat(fastStartDecision(b, t0 + 15_000L)).isFalse()
    }

    @Test
    fun `Joggen 20kmh - Tempo 5,6 m-s selbst mit Evidence unter der Schwelle`() {
        val b = bridge()
        b.onVehicleSample(confidence = 80, nowMs = t0 - 5_000L)
        b.addDriveProbe(probe(t0, 5.6f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 5.6f, moving = true, displacementFromStartM = 84.0), false)

        assertThat(fastStartDecision(b, t0 + 15_000L)).isFalse()
    }

    @Test
    fun `Gehen 1,5 m-s - nie Fast-Start`() {
        val b = bridge()
        b.onVehicleSample(confidence = 80, nowMs = t0 - 5_000L)
        b.addDriveProbe(probe(t0, 1.5f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 1.5f, moving = true, displacementFromStartM = 22.0), false)

        assertThat(fastStartDecision(b, t0 + 15_000L)).isFalse()
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 3: Fahrrad — ON_BICYCLE widerlegt die Evidence
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Fahrrad 25-30kmh - ON_BICYCLE-Sample resetet die Evidence - nie Fast-Start`() {
        // Reihenfolge wie im Receiver: erst IN_VEHICLE-Sample (Evidence),
        // dann ON_BICYCLE (Sensor-Hub meldet den Aktivitäts-Wechsel) →
        // die Fahrzeug-Evidence ist widerlegt, der Fast-Pfad tot — auch
        // wenn die 8,3-m/s-Fixes eines Rennrad-Sprints im Puffer liegen.
        val b = bridge()
        b.onVehicleSample(confidence = 90, nowMs = t0 - 10_000L)
        assertThat(b.vehicleEvidence()).isNotNull()
        b.onBicycleSample()
        assertThat(b.vehicleEvidence()).isNull()
        b.addDriveProbe(probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0), false)

        assertThat(fastStartDecision(b, t0 + 15_000L)).isFalse()
        // Gegenprobe: frisches IN_VEHICLE-Sample danach heilt die Evidence
        // (nächster Sample-Takt) → dann startet die Fahrt.
        b.onVehicleSample(confidence = 90, nowMs = t0 + 20_000L)
        assertThat(fastStartDecision(b, t0 + 20_000L)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 4: Fahrt 30er-Zone — SOFORT-Start (Akzeptanz)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Fahrt 30er-Zone mit frischer Evidence - Fast-Start TRUE ohne 30s-Spread`() {
        // Der Design-Positivfall: 2 konsekutive 8,3-m/s-Fixes (15 s
        // Stream-Takt) + 120 m Netto + frisches IN_VEHICLE-Sample — die
        // Session startet beim ZWEITEN Fix, der Normalpfad hätte auf den
        // 30-s-Spread gewartet.
        val b = bridge()
        b.onVehicleSample(confidence = 80, nowMs = t0 - 5_000L)
        b.addDriveProbe(probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0), false)

        assertThat(fastStartDecision(b, t0 + 15_000L)).isTrue()
    }

    @Test
    fun `Fahrt nach Standphase - Prime-Fix plus erster Stream-Fix mit 15s Abstand`() {
        // Realer Best-Case aus Design §6.3: PRIME-Fix (Burst-Eintritt)
        // + erster Stream-Fix 15 s später — Hysterese erfüllt, Start.
        val b = bridge()
        b.onVehicleSample(confidence = 85, nowMs = t0)
        b.addDriveProbe(probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 125.0), false)

        assertThat(fastStartDecision(b, t0 + 15_000L)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 5: Stillstand + Speed-Noise
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Stillstand mit hochfrequentem Speed-Noise - Netto 0 - nie Fast-Start`() {
        // „Stationary with high speed noise" — GPS-Drift behauptet Speed,
        // die Position bleibt stehen (beide Fixes am selben Punkt).
        val b = bridge()
        b.onVehicleSample(confidence = 80, nowMs = t0 - 5_000L)
        b.addDriveProbe(probe(t0, 11.2f), false)
        b.addDriveProbe(probe(t0 + 15_000L, 9.8f), false)

        assertThat(fastStartDecision(b, t0 + 15_000L)).isFalse()
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 6: Evidence-Lebenszyklus an der Bridge
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Confidence unter 60 - Evidence vorhanden, aber zu schwach - false`() {
        val b = bridge()
        b.onVehicleSample(confidence = 40, nowMs = t0 - 5_000L)
        b.addDriveProbe(probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0), false)

        val ev = b.vehicleEvidence()
        assertThat(ev).isNotNull()
        assertThat(ev!!.confidence).isEqualTo(40)
        assertThat(fastStartDecision(b, t0 + 15_000L)).isFalse()
    }

    @Test
    fun `Evidence altert ueber 90s - gehoert zur alten Fahrt - false`() {
        val b = bridge()
        b.onVehicleSample(confidence = 80, nowMs = t0 - 100_000L)
        b.addDriveProbe(probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0), false)

        assertThat(fastStartDecision(b, t0 + 15_000L)).isFalse()
        // Frisches Sample heilt (nächster 30-s-Takt) — Design §8 „WALKING
        // heilt nicht, IN_VEHICLE-Sample heilt".
        b.onVehicleSample(confidence = 80, nowMs = t0 + 15_000L)
        assertThat(fastStartDecision(b, t0 + 15_000L)).isTrue()
    }

    @Test
    fun `Session-Grenze - resetVehicleEvidence beim Start und Stopp`() {
        val b = bridge()
        b.onVehicleSample(confidence = 90, nowMs = t0)
        assertThat(b.vehicleEvidence()).isNotNull()

        // DriveStartWorker (Start) + DriveStopWorker/Watchdog (Stop)
        // rufen resetVehicleEvidence() — die Evidence überlebt keine
        // Session-Grenze (M18.127-Muster).
        b.resetVehicleEvidence()
        assertThat(b.vehicleEvidence()).isNull()

        b.onVehicleSample(confidence = 90, nowMs = t0 + 60_000L)
        assertThat(b.vehicleEvidence()).isNotNull()
        b.resetVehicleEvidence()
        assertThat(b.vehicleEvidence()).isNull()
    }

    @Test
    fun `Neues IN_VEHICLE-Sample ueberschreibt die alte Evidence`() {
        val b = bridge()
        b.onVehicleSample(confidence = 90, nowMs = t0)
        b.onVehicleSample(confidence = 55, nowMs = t0 + 30_000L)
        val ev = b.vehicleEvidence()
        assertThat(ev).isNotNull()
        assertThat(ev!!.atMs).isEqualTo(t0 + 30_000L)
        assertThat(ev.confidence).isEqualTo(55)
    }

    // ──────────────────────────────────────────────────────────────
    // SZENARIO 7: V7 Restart-Cooldown (Aufrufer-Gate)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Restart-Cooldown 3 Min nach Session-Ende blockt den Fast-Pfad`() {
        val b = bridge()
        b.markDriveStopped(t0)
        b.onVehicleSample(confidence = 90, nowMs = t0 + 60_000L)
        b.addDriveProbe(probe(t0 + 60_000L, 8.3f, moving = true, displacementFromStartM = 0.0), false)
        b.addDriveProbe(probe(t0 + 75_000L, 8.3f, moving = true, displacementFromStartM = 120.0), false)

        // Innerhalb der 3 Min: Cooldown blockt (V7), obwohl das Gate in
        // der puren Funktion stehen würde — der Aufrufer prüft VORAB.
        assertThat(b.isWithinDriveRestartCooldown(t0 + 75_000L)).isTrue()
        assertThat(fastStartDecision(b, t0 + 75_000L)).isFalse()

        // Nach Ablauf des Cooldowns (≥ 3 Min): dieselbe Fahrt-Serie startet.
        assertThat(b.isWithinDriveRestartCooldown(t0 + 181_000L)).isFalse()
        b.addDriveProbe(probe(t0 + 181_000L, 8.3f, moving = true, displacementFromStartM = 240.0), false)
        b.onVehicleSample(confidence = 90, nowMs = t0 + 181_000L)
        assertThat(fastStartDecision(b, t0 + 196_000L)).isTrue()
    }

    @Test
    fun `markDriveStopped mit 0 - kein Cooldown nach Prozessstart`() {
        // driveStoppedAtMs == 0 (Prozessstart) → isWithinCooldown false,
        // wie im DriveStartWorker dokumentiert (M18.84).
        val b = bridge()
        assertThat(b.isWithinDriveRestartCooldown(t0)).isFalse()
    }
}
