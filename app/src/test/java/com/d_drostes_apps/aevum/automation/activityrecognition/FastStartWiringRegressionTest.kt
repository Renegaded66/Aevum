package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.128-REGRESSION (Kanban t_1c95af07): Struktur-Checks für die
 * Android-/GMS-gebundenen Pfade des Fast-Start-Gates, die als reine
 * JVM-Unit-Tests nicht instanziierbar sind (BroadcastReceiver, GMS-Client,
 * FGS). Gleiche Datei-Scan-Technik wie WalkStopStopPathRegressionTest
 * (M18.127) und DriveStopNotificationRegressionTest (M18.124b).
 *
 * Gesichert wird (Design t_bea94587 §7/§11):
 *  1. RECEIVER-VERDRAHTUNG: Der IN_VEHICLE-Zweig registriert die
 *     Vehicle-Evidence (onVehicleSample), der ON_BICYCLE-Zweig widerlegt
 *     sie (onBicycleSample) — und WALKING/RUNNING registriert GAR KEINE
 *     Evidence (Joggen hat nie einen Fast-Pfad-Qualifikator).
 *  2. handleFix-FAST-START-BLOCK: Cooldown-Gate VORAB (V7), dann
 *     shouldFastStart mit genau den Bridge-Daten (Evidence, Probes,
 *     Geofences, Cadence); im Erfolgsfall Rückdatierung per addSample-
 *     Backfill (beide Fix-Timestamps), markDriveConfirmed VOR dem Drain,
 *     resetVehicleEvidence + Schedule von Start- und Watchdog-Worker.
 *  3. SESSION-RESETS: resetVehicleEvidence() in DriveStartWorker (Start)
 *     UND in beiden Stop-Pfaden (DriveStopWorker + DriveWatchdogWorker) —
 *     die Evidence überlebt keine Session-Grenze.
 */
class FastStartWiringRegressionTest {

    private val base =
        File("src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")

    private fun source(name: String): String {
        val f = File(base, name)
        assertWithMessage("Quelldatei fehlt: ${f.absolutePath}").that(f.exists()).isTrue()
        return f.readText()
    }

    // ── 1) Receiver-Verdrahtung ──────────────────────────────────

    @Test
    fun `IN_VEHICLE-Zweig registriert die Vehicle-Evidence mit Confidence`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("DetectedActivity.IN_VEHICLE ->")
        assertWithMessage("IN_VEHICLE-Zweig nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("DetectedActivity.WALKING, DetectedActivity.RUNNING ->", idx)
        val block = src.substring(idx, end)
        // Evidence + Confidence (die reine Funktion prüft die Schwelle).
        assertThat(block.contains("bridge.onVehicleSample(top.confidence)")).isTrue()
    }

    @Test
    fun `ON_BICYCLE-Zweig widerlegt die Evidence`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("DetectedActivity.ON_BICYCLE ->")
        assertWithMessage("ON_BICYCLE-Zweig nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("else -> Unit", idx)
        val block = src.substring(idx, end)
        assertThat(block.contains("bridge.onBicycleSample()")).isTrue()
    }

    @Test
    fun `WALKING-RUNNING-Zweig registriert KEINE Vehicle-Evidence - Joggen-Schutz`() {
        // Der Kern der Root-Akzeptanz: Beim Joggen meldet der Sensor-Hub
        // RUNNING/ON_FOOT — würde dieser Zweig die Evidence setzen,
        // bekäme der Fast-Pfad (fälschlich) seinen Qualifikator.
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("DetectedActivity.WALKING, DetectedActivity.RUNNING ->")
        assertWithMessage("WALKING/RUNNING-Zweig nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("DetectedActivity.ON_BICYCLE ->", idx)
        val block = src.substring(idx, end)
        assertWithMessage("Geh-Zweig darf die Vehicle-Evidence NIE setzen (Joggen!)")
            .that(block.contains("onVehicleSample")).isFalse()
    }

    @Test
    fun `ON_FOOT fassen die Evidence nicht an`() {
        // M18.84-Lektion: Ein einzelnes WALKING im Stop&Go darf die
        // schnelle Erkennung nicht dauerhaft blockieren → kein Reset.
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("DetectedActivity.WALKING, DetectedActivity.RUNNING ->")
        val end = src.indexOf("DetectedActivity.ON_BICYCLE ->", idx)
        val block = src.substring(idx, end)
        assertWithMessage("Geh-Zweig darf die Evidence NICHT reseten")
            .that(block.contains("onBicycleSample")).isFalse()
    }

    // ── 2) handleFix Fast-Start-Block ─────────────────────────────

    @Test
    fun `handleFix enthaelt den Fast-Start-Block vor dem Normalpfad`() {
        val src = source("DriveDetectionService.kt")
        assertThat(src.contains("// ── M18.128: FAST-START-GATE (Design t_bea94587 §6/§7) ────")).isTrue()
        // Der Block steht VOR dem bestehenden classify-Normalpfad
        // (M18.128-Kommentar „Vor dem Normalpfad") und NUR im
        // Nicht-aktiv-Bereich (isDriveActive/isDrivingEnabled-Gate).
        val fastIdx = src.indexOf("M18.128: FAST-START-GATE")
        val classifyIdx = src.indexOf("when (val result = DriveDetectionEngine.classify(")
        assertThat(fastIdx).isAtLeast(0)
        assertThat(classifyIdx).isGreaterThan(fastIdx)
    }

    @Test
    fun `handleFix prueft den Cooldown VORAB - V7 liegt beim Aufrufer`() {
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("val withinCooldown = bridge.isWithinDriveRestartCooldown(now)")
        assertWithMessage("Cooldown-Vorabprüfung nicht gefunden").that(idx).isAtLeast(0)
        val fastIdx = src.indexOf("DriveDetectionEngine.shouldFastStart(")
        // V7-Check erscheint VOR dem Gate-Aufruf im selben Block.
        val block = src.substring(idx, fastIdx)
        assertThat(block.contains("if (!withinCooldown &&")).isTrue()
    }

    @Test
    fun `handleFix ruft shouldFastStart mit allen Bridge-Daten`() {
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("DriveDetectionEngine.shouldFastStart(")
        assertWithMessage("shouldFastStart-Aufruf nicht gefunden").that(idx).isAtLeast(0)
        // Der Aufruf ist kompakt (6 Argumentzeilen) — 400 Zeichen reichen
        // sicher bis zur schließenden Klammer.
        val block = src.substring(idx, (idx + 400).coerceAtMost(src.length))
        assertThat(block.contains("bridge.currentDriveProbes()")).isTrue()
        assertThat(block.contains("bridge.vehicleEvidence()")).isTrue()
        // Die Geofences kommen als vorab gelesene lokale Variable
        // (`val circles = bridge.currentGeofenceContext()` unmittelbar vor
        // dem Block) — gleiche Datenquelle, eine Bridge-Lese pro Fix.
        assertThat(block.contains("circles,")).isTrue()
        assertThat(src.contains("val circles = bridge.currentGeofenceContext()")).isTrue()
        assertThat(block.contains("bridge.currentCadenceHz()")).isTrue()
        assertThat(block.contains("bridge.currentCadenceValidFraction()")).isTrue()
    }

    @Test
    fun `Fast-Start-Erfolgspfad - Rueckdatierung, Reset und beide Worker-Schedules`() {
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("M18.128: Fast-Start-Gate erfüllt")
        assertWithMessage("Erfolgs-Logzeile nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("M18.117: Motion-Kontext", idx)
        val block = src.substring(idx, end)

        // Rückdatierung OHNE toVehicleCluster (30-s-Spread-Anforderung
        // erfüllt das 15-s-Fast-Paar nie): addSample-Backfill der zwei
        // Fix-Timestamps (älterer zuerst — M18.121-F-4-Backfill-Semantik).
        assertThat(block.contains("val older = fastPair.first()")).isTrue()
        assertThat(block.contains("val newer = fastPair.last()")).isTrue()
        val olderIdx = block.indexOf("bridge.addSample(older.timestampMs, 80)")
        val newerIdx = block.indexOf("bridge.addSample(newer.timestampMs, 80)")
        assertThat(olderIdx).isAtLeast(0)
        assertThat(newerIdx).isGreaterThan(olderIdx)

        // Bestätigung VOR dem Drain (M18.66-FIX15-Gate des Workers) —
        // die Reihenfolge ist der Race-Schutz.
        val markIdx = block.indexOf("bridge.markDriveConfirmed()")
        val drainIdx = block.indexOf("bridge.drainDriveProbes()")
        assertThat(markIdx).isAtLeast(0)
        assertThat(drainIdx).isGreaterThan(markIdx)

        // Evidence-Verbrauch + Start-Orchestrierung.
        assertThat(block.contains("bridge.resetVehicleEvidence()")).isTrue()
        assertThat(block.contains("DriveStartWorker.schedule(this)")).isTrue()
        assertThat(block.contains("DriveWatchdogWorker.schedule(this)")).isTrue()
        // Der Fast-Pfad endet mit return — der Normalpfad läuft NIE
        // doppelt für dieselbe Fix-Serie.
        assertThat(block.contains("return\n")).isTrue()
    }

    @Test
    fun `Fast-Start-Pfad startet nur ausserhalb einer laufenden Fahrt`() {
        val src = source("DriveDetectionService.kt")
        // Der Fast-Start-Block liegt im `!bridge.isDriveActive() &&
        // bridge.isDrivingEnabled()`-Bereich (dieselben Gates wie der
        // Normalpfad — ein laufender Track hat Vorrang).
        val gateIdx = src.indexOf("if (!bridge.isDriveActive() && bridge.isDrivingEnabled()) {")
        assertWithMessage("Aktivitäts-Gate nicht gefunden").that(gateIdx).isAtLeast(0)
        val fastIdx = src.indexOf("M18.128: FAST-START-GATE", gateIdx)
        assertThat(fastIdx).isGreaterThan(gateIdx)
    }

    // ── 3) Session-Grenzen: Evidence-Reset in den Workern ─────────

    @Test
    fun `DriveStartWorker reseted die Evidence beim Session-Start`() {
        val src = source("DriveWorkers.kt")
        val idx = src.indexOf("class DriveStartWorker(")
        assertWithMessage("DriveStartWorker nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("class DriveStopWorker(", idx)
        val block = src.substring(idx, end)
        // Neben dem M18.127-Walk-Stop-Reset (Existenz bereits durch
        // WalkStopStopPathRegressionTest gesichert) der neue Reset:
        assertThat(block.contains("bridge.resetVehicleEvidence()")).isTrue()
    }

    @Test
    fun `DriveStopWorker reseted die Evidence beim sauberen Stop`() {
        val src = source("DriveWorkers.kt")
        val idx = src.indexOf("class DriveStopWorker(")
        assertWithMessage("DriveStopWorker nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("class DriveWatchdogWorker(", idx)
        val block = src.substring(idx, end)
        assertThat(block.contains("bridge.resetVehicleEvidence()")).isTrue()
    }

    @Test
    fun `DriveWatchdogWorker reseted die Evidence auf dem Haupt-Stop-Pfad`() {
        val src = source("DriveWorkers.kt")
        val idx = src.indexOf("class DriveWatchdogWorker(")
        assertWithMessage("DriveWatchdogWorker nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("class DriveProbeWorker(", idx)
        val block = src.substring(idx, end)
        assertThat(block.contains("bridge.resetVehicleEvidence()")).isTrue()
    }

    @Test
    fun `Engine bietet die pure Funktion und die Evidence als Value-Objekt`() {
        val src = source("DriveDetectionEngine.kt")
        assertThat(src.contains("fun shouldFastStart(")).isTrue()
        assertThat(src.contains("data class VehicleEvidence(")).isTrue()
        // Die 4 Design-Konstanten (§6.2).
        assertThat(src.contains("const val FAST_START_CONFIDENCE = 60")).isTrue()
        assertThat(src.contains("const val FAST_START_EVIDENCE_MAX_AGE_MS = 90_000L")).isTrue()
        assertThat(src.contains("const val FAST_START_MIN_HYSTERESIS_MS = 10_000L")).isTrue()
        assertThat(src.contains("const val FAST_START_MIN_NET_DISPLACEMENT_M = 100.0")).isTrue()
    }

    @Test
    fun `Bridge bietet die Evidence-Methoden zustandsarm und synchronisiert`() {
        val src = source("ActivityRecognitionWorker.kt")
        assertThat(src.contains("fun onVehicleSample(confidence: Int, nowMs: Long = System.currentTimeMillis())")).isTrue()
        assertThat(src.contains("fun onBicycleSample()")).isTrue()
        assertThat(src.contains("fun resetVehicleEvidence()")).isTrue()
        assertThat(src.contains("fun vehicleEvidence(): DriveDetectionEngine.VehicleEvidence?")).isTrue()
        // Alle vier sind @Synchronized (Muster der übrigen Bridge-Felder).
        val count = Regex("@Synchronized\\n    fun (onVehicleSample|onBicycleSample|resetVehicleEvidence|vehicleEvidence)\\(")
            .findAll(src).count()
        assertWithMessage("Alle 4 Evidence-Methoden müssen @Synchronized sein")
            .that(count).isEqualTo(4)
    }
}
