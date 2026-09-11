package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * M18.120: Unit-Tests für den Session-Lebenszyklus der Auto-Fahr-
 * erkennung (Kanban t_9e0527a6).
 *
 * Deckt die vier Diagnose-Befunde aus t_2edf98d8 ab:
 *   F-1  Stop-Pfad leert den IN_VEHICLE-Cluster-Buffer
 *        (drainVehicleCluster) — der Hinfahrt-Cluster darf die Pause
 *        nicht überleben.
 *   F-2  resolveDriveStart: Frischegrenze (MAX_PROBE_AGE_MS) + M18.80-
 *        Nicht-Überlappungs-Guard. Ein alter Cluster startet bei `now`,
 *        der Guard hebt NIE auf ein beliebig altes Auto-Session-Ende an.
 *   F-3  toVehicleCluster: Stillstands-Probes sind kein Start-Anker.
 *   F-4  addSample mit älterem Backfill-Zeitstempel regrediert weder
 *        endMs/lastMs noch den Herzschlag (durationMs nie negativ).
 *
 * Reine JVM-Tests (kein Robolectric) — die Bridge braucht nur das
 * Settings-Repository-Interface (Muster: ActivityRecognitionBridgeHealTest).
 */
class ActivitySessionLifecycleTest {

    private fun bridge(settings: AutomationSettings = AutomationSettings()) =
        ActivityRecognitionBridge(
            object : AutomationSettingsRepository {
                override fun get() = flowOf(settings)
                override suspend fun upsert(s: AutomationSettings) {}
            }
        )

    private fun motionProbe(
        tsMs: Long,
        speedMps: Float?,
        distanceFromLastM: Double? = null
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = tsMs,
        speedMps = speedMps,
        accuracyMeters = 12f,
        distanceFromLastM = distanceFromLastM,
        latitude = 50.0,
        longitude = 8.0
    )

    // ──────────────────────────────────────────────────────────────
    // F-1: Stop-Pfad leert den Vehicle-Cluster-Buffer
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Stop-Paket leert den IN_VEHICLE-Cluster-Buffer — Cluster ueberlebt die Pause nicht`() {
        val b = bridge()
        // Hinfahrt: zwei Samples → ein Cluster im Buffer.
        b.addSample(1_000_000L, 75)
        b.addSample(1_060_000L, 75)
        assertThat(b.drainVehicleCluster()).isNotNull()

        // Stop-Paket exakt wie DriveStopWorker/DriveWatchdogWorker
        // (DriveWorkers.kt M18.120): markDriveStopped + drainDriveProbes
        // + drainVehicleCluster + clearWalkingSignal.
        b.markDriveStopped(1_060_000L)
        b.drainDriveProbes()
        b.drainVehicleCluster()
        b.clearWalkingSignal()

        // Der nächste Start findet keinen Anker aus der alten Fahrt.
        assertThat(b.drainVehicleCluster()).isNull()
    }

    @Test
    fun `Frische Hinfahrt-Samples nach dem Stop bauen einen NEUEN Cluster auf`() {
        val b = bridge()
        b.addSample(1_000_000L, 75)
        b.addSample(1_060_000L, 75)
        // Alter Stop-Pfad (VOR M18.120): ohne drainVehicleCluster —
        // Lücke reproduzieren, die die Frischegrenze unten abfängt.
        b.drainDriveProbes()
        b.clearWalkingSignal()

        // Rückfahrt-Samples (frisch) kommen zum überlebenden Cluster dazu.
        b.addSample(1_000_060_000L, 75)
        b.addSample(1_000_120_000L, 75)
        val cluster = b.drainVehicleCluster()
        assertThat(cluster).isNotNull()
        assertThat(cluster!!.startMs).isEqualTo(1_000_060_000L)
        assertThat(cluster.endMs).isEqualTo(1_000_120_000L)
    }

    // ──────────────────────────────────────────────────────────────
    // F-2: resolveDriveStart — Frischegrenze + Nicht-Überlappungs-Guard
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `ohne Cluster startet die Session bei now`() {
        assertThat(
            DriveDetectionEngine.resolveDriveStart(null, nowMs = 5_000L, lastAutoSessionEndMs = null)
        ).isEqualTo(5_000L)
    }

    @Test
    fun `frischer Cluster behaelt seinen Start als Anker — Rueckdatierung bleibt`() {
        // M18.66-Motiv: "Fahrt begann vor der Erkennung" — ein frischer
        // Cluster-Start (innerhalb des 15-Min-Fensters) datiert die
        // Session zurück.
        val clusterStart = 1_000L
        assertThat(
            DriveDetectionEngine.resolveDriveStart(
                clusterStartMs = clusterStart, nowMs = 60_000L, lastAutoSessionEndMs = null
            )
        ).isEqualTo(clusterStart)
    }

    @Test
    fun `Stau-Muster - frischer Cluster vor dem letzten Auto-Ende wird angehoben (M18-80-Guard)`() {
        // Watchdog-Stop 13:00 (lastEnd), neuer Cluster-Start 12:55 →
        // Anheben auf 13:00 verhindert die Timeline-Überlappung
        // (Stau, Ampel — die Fahrt wurde nie wirklich beendet).
        val lastEnd = 13_000L
        val freshClusterStart = 12_000L // 1 min vor lastEnd
        assertThat(
            DriveDetectionEngine.resolveDriveStart(
                clusterStartMs = freshClusterStart, nowMs = 14_000L, lastAutoSessionEndMs = lastEnd
            )
        ).isEqualTo(lastEnd)
    }

    @Test
    fun `STALE Cluster startet bei now — kein 1,5h-Vorlauf (Gym-Fall)`() {
        // Rückfahrt-Beginn T1 = 90 min nach Gym-Ankunft T0. Der
        // Hinfahrt-Cluster (start = T0 − 10 min) ist da schon 100 min
        // alt → > 15-Min-Frischegrenze → Start = now.
        val t0 = 1_000_000L
        val t1 = t0 + 90L * 60 * 1000
        val staleClusterStart = t0 - 10L * 60 * 1000
        assertThat(
            DriveDetectionEngine.resolveDriveStart(
                clusterStartMs = staleClusterStart, nowMs = t1, lastAutoSessionEndMs = t0
            )
        ).isEqualTo(t1)
    }

    @Test
    fun `STALE Cluster wird NICHT auf das alte Auto-Session-Ende angehoben`() {
        // Der alte M18.80-Guard hob OHNE Frischegrenze auf
        // lastAutoSessionEndMs (= Gym-Ankunft T0) an → 1,5-h-Vorlauf.
        // Jetzt: alter Cluster → now, egal wie alt das letzte Ende ist.
        val t0 = 1_000_000L
        val t1 = t0 + 90L * 60 * 1000
        val staleClusterStart = 500_000L // 8,3 min vor T0, aber 98 min vor T1
        assertThat(
            DriveDetectionEngine.resolveDriveStart(
                clusterStartMs = staleClusterStart, nowMs = t1, lastAutoSessionEndMs = t0
            )
        ).isEqualTo(t1)
    }

    @Test
    fun `Cluster exakt an der Frischegrenze gilt noch als frisch`() {
        val now = 1_000_000L
        val boundary = now - DriveDetectionEngine.MAX_PROBE_AGE_MS
        assertThat(
            DriveDetectionEngine.resolveDriveStart(boundary, now, lastAutoSessionEndMs = null)
        ).isEqualTo(boundary)
    }

    @Test
    fun `Cluster 1ms ueber der Frischegrenze ist stale`() {
        val now = 1_000_000L
        val old = now - DriveDetectionEngine.MAX_PROBE_AGE_MS - 1L
        assertThat(
            DriveDetectionEngine.resolveDriveStart(old, now, lastAutoSessionEndMs = null)
        ).isEqualTo(now)
    }

    // ──────────────────────────────────────────────────────────────
    // F-3: toVehicleCluster — Stillstands-Probes sind kein Start-Anker
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Standstill-Probes aus der Pause liefern keinen Start-Anker`() {
        // Gym-Fall: 2 Standstill-Probes (0 m/s, keine Distanz) — mit der
        // alten Logik wurden sie zum Cluster-Start-Anker (bis 15 min
        // zurück). Jetzt: kein Bewegungs-Anker → kein Cluster.
        val t1 = 1_000_000L + 90L * 60 * 1000
        val probes = listOf(
            motionProbe(t1 - 10L * 60 * 1000, speedMps = 0f, distanceFromLastM = 0.0),
            motionProbe(t1 - 60_000L, speedMps = 0f, distanceFromLastM = 0.0)
        )
        assertThat(DriveDetectionEngine.toVehicleCluster(probes, nowMs = t1)).isNull()
    }

    @Test
    fun `Standstill-Probe mit Distanz-Evidenz zaehlt als Bewegungs-Anker`() {
        // Ein Probe ohne Speed, aber mit echter Distanz zum Vorgänger:
        // Bewegungs-Evidenz über die Distanz-Schwelle (M18.77-Fallback).
        val t1 = 1_000_000L + 90L * 60 * 1000
        val probes = listOf(
            motionProbe(t1 - 60_000L, speedMps = null, distanceFromLastM = 150.0),
            motionProbe(t1, speedMps = 8.5f, distanceFromLastM = null)
        )
        val cluster = DriveDetectionEngine.toVehicleCluster(probes, nowMs = t1 + 1_000L)
        assertThat(cluster).isNotNull()
        assertThat(cluster!!.startMs).isEqualTo(t1 - 60_000L)
    }

    @Test
    fun `frische Bewegungs-Probes liefern weiterhin den Rueckdatierungs-Anker`() {
        val t1 = 1_000_000L + 90L * 60 * 1000
        val probes = listOf(
            motionProbe(t1 - 60_000L, speedMps = 8.5f),
            motionProbe(t1, speedMps = 8.5f)
        )
        val cluster = DriveDetectionEngine.toVehicleCluster(probes, nowMs = t1 + 1_000L)
        assertThat(cluster).isNotNull()
        assertThat(cluster!!.startMs).isEqualTo(t1 - 60_000L)
        assertThat(cluster.endMs).isEqualTo(t1)
    }

    // ──────────────────────────────────────────────────────────────
    // F-4: addSample mit älterem Backfill — max-Semantik
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Backfill addSample mit aelterem Zeitstempel regrediert endMs und lastMs nicht`() {
        val b = bridge()
        // Receiver-Muster (ActivityRecognitionWorker.kt:1174+1197):
        // erst addSample(now), dann addSample(burstCluster.startMs) mit
        // älterem Zeitstempel.
        b.addSample(1_000_000L, 75)
        b.addSample(990_000L, 75)

        val cluster = b.drainVehicleCluster()
        // endMs/lastMs bleiben beim Maximum (1.000.000) — VORHER
        // regredierten sie auf 990.000 (durationMs = -10.000).
        assertThat(cluster!!.endMs).isEqualTo(1_000_000L)
        assertThat(cluster.lastMs).isEqualTo(1_000_000L)
        assertThat(cluster.startMs).isEqualTo(990_000L)
        assertThat(cluster.durationMs).isEqualTo(10_000L)
        assertThat(cluster.durationMs).isAtLeast(0L)
    }

    @Test
    fun `Backfill mit aelterem Zeitstempel regrediert den Watchdog-Herzschlag nicht`() {
        val b = bridge()
        b.addSample(1_000_000L, 75)
        // Backfill älter → Herzschlag (lastVehicleSampleMs) bleibt frisch.
        b.addSample(990_000L, 75)
        assertThat(b.lastVehicleSample()).isEqualTo(1_000_000L)
    }

    @Test
    fun `normaler Vorwaerts-Sample erweitert den Cluster weiterhin`() {
        val b = bridge()
        b.addSample(1_000_000L, 75)
        b.addSample(1_010_000L, 75)
        b.addSample(1_020_000L, 75)
        val cluster = b.drainVehicleCluster()
        assertThat(cluster!!.startMs).isEqualTo(1_000_000L)
        assertThat(cluster.endMs).isEqualTo(1_020_000L)
        assertThat(cluster.sampleCount).isEqualTo(3)
    }
}
