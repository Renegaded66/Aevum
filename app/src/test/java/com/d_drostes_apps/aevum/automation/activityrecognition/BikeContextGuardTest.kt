package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * M18.135 (Kanban t_8e2889cd): Das Zweirad-Kontext-Gate
 * ([BikeContextGuard]) und seine Verdrahtung in der
 * [ActivityRecognitionBridge] — pure JVM-Tests gegen den echten
 * Produktionscode.
 *
 * Hintergrund (gemessen in t_88146697): Ein transienter ON_BICYCLE-EXIT
 * nahm den Motion-Kontext auf UNKNOWN zurück (2 Samples, M18.117-Hysterese)
 * und öffnete damit ~60 s lang die 8-m/s-Schwelle. Bei 29 km/h
 * klassifizierte die Engine `Driving` 15 s nach dem EXIT → eine
 * „Autofahren"-Session entstand über/neben der laufenden Rad-Session.
 *
 * Der Guard schließt das Fenster: Das 12-m/s-Gate hängt zusätzlich an der
 * FRISCHE eines bestätigten Rad-Samples (90 s = drei 30-s-AR-Takte), und
 * nur ein bestätigtes IN_VEHICLE-Sample oder das Ablaufen der Frische
 * beendet es.
 *
 * Abgrenzung: [CyclingVsCarDetectionTest] fährt den vollständigen
 * Entscheidungsweg (Session-Ebene); diese Suite prüft die Zustandsmaschine
 * und die Verdrahtung (Bridge-Methoden, Quelltext-Pfade) isoliert.
 */
class BikeContextGuardTest {

    private val t0 = 1_000_000_000L
    private val unknown = DriveDetectionEngine.MotionContext.UNKNOWN
    private val onBike = DriveDetectionEngine.MotionContext.ON_BICYCLE

    // ──────────────────────────────────────────────────────────────
    // 1) Die pure Zustandsmaschine
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `ohne Rad-Sample ist das Gate zu und der rohe Kontext gilt`() {
        val g = BikeContextGuard()
        assertThat(g.hasFreshConfirmedBikeSample(t0)).isFalse()
        assertThat(g.effectiveContext(t0, unknown)).isEqualTo(unknown)
        assertThat(g.effectiveContext(t0, DriveDetectionEngine.MotionContext.IN_VEHICLE))
            .isEqualTo(DriveDetectionEngine.MotionContext.IN_VEHICLE)
    }

    @Test
    fun `bestätigtes Rad-Sample hält das Gate über ein EXIT-Artefakt hinweg offen`() {
        val g = BikeContextGuard()
        g.onBicycleSample(t0, confidence = 85)
        g.onBicycleExit(t0 + 30_000L)
        // Der rohe Kontext ist nach dem EXIT UNKNOWN (M18.117-Hysterese) —
        // das Gate liefert trotzdem ON_BICYCLE (12-m/s-Gates).
        assertThat(g.effectiveContext(t0 + 30_000L, unknown)).isEqualTo(onBike)
        assertThat(g.effectiveContext(t0 + 60_000L, unknown)).isEqualTo(onBike)
        assertThat(g.isBikeGateActive(t0 + 60_000L, unknown)).isTrue()
    }

    @Test
    fun `das Gate faellt exakt nach 90 s ohne bestätigtes Rad-Sample`() {
        val g = BikeContextGuard()
        g.onBicycleSample(t0, confidence = 85)
        val hold = BikeContextGuard.BIKE_GATE_HOLD_MS
        assertWithMessage("Haltedauer ist an die Rad-Evidence gekoppelt (90 s)")
            .that(hold).isEqualTo(DriveDetectionEngine.BICYCLE_EVIDENCE_MAX_AGE_MS)
        assertThat(g.effectiveContext(t0 + hold, unknown)).isEqualTo(onBike)
        assertThat(g.effectiveContext(t0 + hold + 1, unknown)).isEqualTo(unknown)
    }

    @Test
    fun `nachlaufende Rad-Samples erneuern die Frische`() {
        // Der Continuous-Stream liefert im 30-s-Takt weiter — jedes
        // bestätigte Sample schiebt das Fenster nach vorn (eine Radfahrt
        // kann Stunden dauern).
        val g = BikeContextGuard()
        var now = t0
        repeat(10) {
            g.onBicycleSample(now, confidence = 85)
            g.onBicycleExit(now + 15_000L)
            now += 30_000L
        }
        assertThat(g.effectiveContext(now, unknown)).isEqualTo(onBike)
    }

    @Test
    fun `schwache Rad-Samples setzen die Frische nicht`() {
        val g = BikeContextGuard()
        g.onBicycleSample(t0, confidence = BikeContextGuard.MIN_CONFIRM_CONFIDENCE - 1)
        assertThat(g.hasFreshConfirmedBikeSample(t0)).isFalse()
        // Genau auf der Schwelle zählt es (≥, wie überall in der Engine).
        g.onBicycleSample(t0, confidence = BikeContextGuard.MIN_CONFIRM_CONFIDENCE)
        assertThat(g.hasFreshConfirmedBikeSample(t0)).isTrue()
    }

    @Test
    fun `bestätigtes Fahrzeug-Sample widerlegt das Gate sofort`() {
        val g = BikeContextGuard()
        g.onBicycleSample(t0, confidence = 85)
        g.onVehicleSample(t0 + 1_000L, confidence = BikeContextGuard.MIN_CONTRADICT_CONFIDENCE)
        assertThat(g.hasFreshConfirmedBikeSample(t0 + 1_000L)).isFalse()
        assertThat(g.effectiveContext(t0 + 1_000L, unknown)).isEqualTo(unknown)
        assertThat(g.contradictedAtMs).isEqualTo(t0 + 1_000L)
    }

    @Test
    fun `schwaches Fahrzeug-Sample widerlegt nichts`() {
        val g = BikeContextGuard()
        g.onBicycleSample(t0, confidence = 85)
        g.onVehicleSample(t0 + 1_000L, confidence = BikeContextGuard.MIN_CONTRADICT_CONFIDENCE - 1)
        assertThat(g.hasFreshConfirmedBikeSample(t0 + 1_000L)).isTrue()
        assertThat(g.contradictedAtMs).isEqualTo(0L)
    }

    @Test
    fun `der rohe Kontext ON_BICYCLE haelt das Gate auch ohne Frische`() {
        // Solange Google selbst Rad meldet (roher Kontext), ist das Gate
        // ohnehin offen — die Frische ist nur der Schutz NACH einem EXIT.
        val g = BikeContextGuard()
        assertThat(g.effectiveContext(t0, onBike)).isEqualTo(onBike)
        assertThat(g.isBikeGateActive(t0, onBike)).isTrue()
    }

    @Test
    fun `UNKNOWN und STILL widerlegen das Gate nicht - sie sind das Artefakt selbst`() {
        // Bewusst KEINE Widerlegung über den Kontext: Der EXIT-Artefakt-
        // Pfad erzeugt genau UNKNOWN. Der Guard prüft deshalb nur die
        // Frische — kein Kontext-Zustand kann das Fenster vorzeitig
        // schließen.
        val g = BikeContextGuard()
        g.onBicycleSample(t0, confidence = 85)
        g.onBicycleExit(t0 + 10_000L)
        assertThat(g.effectiveContext(t0 + 10_000L, unknown)).isEqualTo(onBike)
        // Diagnose-Zeitstempel werden geführt, beeinflussen das Gate aber nicht.
        assertThat(g.lastExitAtMs).isEqualTo(t0 + 10_000L)
        assertThat(g.isBikeGateActive(t0 + 10_000L, unknown)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // 2) Verdrahtung in der Bridge (echte Produktionsklasse)
    // ──────────────────────────────────────────────────────────────

    private fun bridge() = ActivityRecognitionBridge(
        object : com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository {
            override fun get() = kotlinx.coroutines.flow.flowOf(
                com.d_drostes_apps.aevum.data.model.AutomationSettings()
            )
            override suspend fun upsert(s: com.d_drostes_apps.aevum.data.model.AutomationSettings) {}
        }
    )

    @Test
    fun `die Bridge fuehrt die Frische aus onBicycleSampleWithConfidence`() {
        val b = bridge()
        assertThat(b.lastConfirmedBikeSampleMs()).isEqualTo(0L)
        b.onBicycleSampleWithConfidence(85)
        assertWithMessage("Ein bestätigtes Rad-Sample muss die Gate-Frische setzen")
            .that(b.lastConfirmedBikeSampleMs()).isGreaterThan(0L)
    }

    @Test
    fun `ein schwaches Sample setzt die Frische in der Bridge nicht`() {
        val b = bridge()
        b.onBicycleSampleWithConfidence(59)
        assertThat(b.lastConfirmedBikeSampleMs()).isEqualTo(0L)
    }

    @Test
    fun `currentMotionContext liefert das Gate - rawMotionContext den Rohwert`() {
        val b = bridge()
        b.onBicycleSampleWithConfidence(85)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        // EXIT-Artefakt: 2x UNKNOWN (Hysterese) — der ROH-Kontext fällt …
        b.onBicycleExit()
        b.updateMotionContext(DriveDetectionEngine.MotionContext.UNKNOWN)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.UNKNOWN)
        assertThat(b.rawMotionContext()).isEqualTo(DriveDetectionEngine.MotionContext.UNKNOWN)
        // … der EFFEKTIVE (den classify liest) bleibt ON_BICYCLE.
        assertThat(b.currentMotionContext()).isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(b.isBikeGateActive()).isTrue()
    }

    @Test
    fun `die Bridge widerlegt das Gate bei einem bestätigten Fahrzeug-Sample`() {
        val b = bridge()
        b.onBicycleSampleWithConfidence(85)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)

        // Der Produktions-Receiver meldet bei einem IN_VEHICLE-Sample
        // BEIDES: die Evidence (onVehicleSample) und den Kontext
        // (updateMotionContext, 2-Sample-Hysterese).
        b.onVehicleSample(confidence = 80)
        assertWithMessage("Ein bestätigtes Fahrzeug-Sample muss die Rad-Frische verwerfen")
            .that(b.lastConfirmedBikeSampleMs()).isEqualTo(0L)

        // Nur die Frische ist weg — der ROH-Kontext hält noch das letzte
        // Rad-Sample (M18.117-Hysterese: 2 Samples für den Wechsel).
        assertThat(b.rawMotionContext()).isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(b.isBikeGateActive()).isTrue()

        // Nach dem nächsten Kontext-Sample (Produktions-Takt 30 s), das die
        // Streak auf 2 bringt, ist der Übergang vollständig: 8-m/s-Gate,
        // Auto-Start frei (M18.130).
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        assertThat(b.isBikeGateActive()).isFalse()
        assertThat(b.currentMotionContext()).isEqualTo(DriveDetectionEngine.MotionContext.IN_VEHICLE)
    }

    @Test
    fun `verrauschte Fahrzeug-Samples widerlegen das Gate nicht`() {
        val b = bridge()
        b.onBicycleSampleWithConfidence(85)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        b.onVehicleSample(confidence = BikeContextGuard.MIN_CONTRADICT_CONFIDENCE - 1)
        assertThat(b.lastConfirmedBikeSampleMs()).isGreaterThan(0L)
        assertThat(b.isBikeGateActive()).isTrue()
    }

    @Test
    fun `die Zweirad-Session endet weiterhin ueber den Watchdog-Match`() {
        // Bestandsschutz: Der Stop-Pfad der Rad-Session (M18.134) ist
        // unverändert — der Match umfasst driving UND radfahren.
        val src = java.io.File(
            "src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition/DriveWorkers.kt"
        ).takeIf { it.exists() }
            ?: java.io.File(
                "app/src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition/DriveWorkers.kt"
            )
        val text = src.readText()
        val idx = text.indexOf("private fun isAutoTrackedSession(")
        assertThat(idx).isAtLeast(0)
        val block = text.substring(idx, (idx + 400).coerceAtMost(text.length))
        assertThat(block.contains("\"driving\"")).isTrue()
        assertThat(block.contains("\"radfahren\"")).isTrue()
        // Und die öffentliche Fassung für die Trigger-Pfade existiert.
        assertThat(text.contains("fun isLiveAutoTrackedSession(")).isTrue()
    }
}
