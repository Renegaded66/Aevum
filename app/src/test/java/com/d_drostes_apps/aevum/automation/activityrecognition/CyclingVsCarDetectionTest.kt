package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test

/**
 * M18.134-TESTMATRIX (Kanban t_88146697, Root t_099f1911): „Radfahren
 * vs. Autofahrt" — die im Task geforderte Aktivitäts-/Geschwindigkeits-
 * Matrix (ON_BICYCLE, IN_VEHICLE, STILL) gegen die ECHTEN Produktions-
 * klassen ([ActivityRecognitionBridge], [DriveDetectionEngine],
 * [LiveActivityManager]).
 *
 * ABGRENZUNG zu den bestehenden Suiten (bewusst keine Duplikate):
 *  • [DriveBicycleGateTest] prüft die ENGINE-Gates (classify/
 *    detectBikeRide als reine Funktionen) mit Geschwindigkeits-Profilen.
 *  • [BicycleRideIntegrationTest]/[BicycleRideReproductionTest] prüfen
 *    den Bridge-/Session-Pfad für Rad-Profile.
 *  • DIESE Suite prüft die Aufgaben-Matrix des Kanban-Tasks: 25 km/h
 *    konstant (Akzeptanzkriterium), Antritte, STILL-Phasen MITTEN in der
 *    Fahrt, fehlende AR-Permission, null-Daten und mehrdeutige Signale
 *    (schwache Confidence, AR-Flapping, Einzelsample) — jeweils über den
 *    Produktions-Entscheidungsweg (Rad-Start-Worker-Gates + Auto-Start-
 *    Gates von handleFix/DriveStartWorker) bis zur entstehenden Session.
 *
 * Der Auto-Start-Spiegel ist der Kern der Aussage: Ein Fix, der als
 * `classify = Driving` durchgeht, legt in der Produktion über
 * `markDriveConfirmed` + DriveStartWorker eine „Autofahren"-Session an.
 * Genau das darf in keiner Rad-Konstellation passieren.
 *
 * Die AR-Samples kommen wie in der Produktion alle 30 s
 * (`ActivityContinuousSamplesRequester.DETECTION_INTERVAL_MS`), die
 * GPS-Fixes alle 15 s (CONFIRM-/TRACK_DRIVE-Stream).
 */
class CyclingVsCarDetectionTest {

    /** Simulierte Fahrt-Epoche in der VERGANGENHEIT (Wall-Clock der
     *  LiveActivityManager-Start-Anker ist die reale Zeit — ein
     *  Zukunfts-`t0` würde von `startedAt.coerceAtMost(now)` gekappt). */
    private val t0 = 1_000_000_000L

    private fun kmh(v: Double) = (v / 3.6).toFloat()

    private fun bridge(settings: AutomationSettings = AutomationSettings()) =
        ActivityRecognitionBridge(
            object : AutomationSettingsRepository {
                override fun get() = flowOf(settings)
                override suspend fun upsert(s: AutomationSettings) {}
            }
        )

    /** Probe mit konsistenter Position (lat-Schritt folgt der Speed). */
    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 10f,
        distanceFromLastM: Double? = null,
        positionM: Double = 0.0
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = distanceFromLastM,
        latitude = 50.000 + positionM / 111_320.0,
        longitude = 8.000
    )

    /** Lauf-Ergebnis: die TATSÄCHLICH entstandenen Sessions. */
    private class RideRun(
        val repo: FakeActivityRepository,
        val bridge: ActivityRecognitionBridge
    ) {
        val carSessions = mutableListOf<ActivitySession>()
        var bikeSession: ActivitySession? = null
        val log = StringBuilder()
    }

    /**
     * Ein AR-Sample wie der Continuous-Receiver: widerlegt die Fahrzeug-
     * Evidence (M18.128), registriert die Rad-Evidence MIT Confidence
     * (M18.134) und setzt den Motion-Kontext. Der Kontext hat eine
     * 2-Sample-Hysterese (M18.117) — deshalb schlägt er bei 30-s-Samples
     * nach ~60 s um (exakt die Produktions-Charakteristik).
     */
    private fun feedBikeSample(
        bridge: ActivityRecognitionBridge,
        nowMs: Long,
        confidence: Int = 85
    ) {
        bridge.onBicycleSample()
        bridge.onBicycleSampleWithConfidence(confidence, nowMs)
        bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
    }

    private suspend fun awaitLive(manager: LiveActivityManager, id: String?) {
        repeat(200) {
            if (manager.liveSession.value?.id == id) return
            delay(10)
        }
    }

    /**
     * Spielt eine Fahrt über den Produktions-Entscheidungsweg durch.
     *
     * @param speedAt Geschwindigkeit (km/h) je Fix-Zeit
     * @param arAt AR-Sample-Feed je Fix-Zeit (Produktions-Takt: 30 s)
     */
    private suspend fun runRide(
        minutes: Int,
        speedAt: (tMs: Long) -> Double,
        settings: AutomationSettings = AutomationSettings(),
        arAt: (bridge: ActivityRecognitionBridge, tMs: Long) -> Unit
    ): RideRun {
        val b = bridge(settings)
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        val run = RideRun(repo, b)
        var positionM = 0.0

        for (t in 0L..minutes * 60_000L step 15_000L) {
            val now = t0 + t
            val mps = kmh(speedAt(t))
            positionM += mps * 15.0
            b.addDriveProbe(probe(now, mps, 10f, mps * 15.0, positionM), false)
            arAt(b, t)

            // ── Rad-Pfad: BicycleStartWorker-Kern (drei Gates, dieselbe
            //    Reihenfolge: isBicycleEnabled → isReliableBicycleSignal →
            //    detectBikeRide) + Duplikat-Schutz des Workers.
            if (run.bikeSession == null && b.isBicycleEnabled() &&
                DriveDetectionEngine.isReliableBicycleSignal(b.bicycleEvidence(), now)
            ) {
                val ride = DriveDetectionEngine.detectBikeRide(
                    b.currentDriveProbes(), now, b.currentGeofenceContext()
                )
                if (ride != null && manager.liveSession.value?.isLive != true) {
                    val session = manager.start(
                        activityTypeId = "radfahren",
                        title = "Radfahren",
                        sourceType = "ACTIVITY_RECOGNITION_AUTO",
                        startedAt = ride.startMs
                    )
                    awaitLive(manager, session.id)
                    run.bikeSession = session
                    run.log.append("t=${t / 1000}s RAD-Session start=${ride.startMs - t0}ms\n")
                }
            }

            // ── Auto-Pfad: handleFix (Fast-Start → classify) +
            //    DriveStartWorker-Gates (Cooldown, Bestätigung, Duplikat).
            if (!b.isDriveActive() && b.isDrivingEnabled() &&
                !b.isWithinDriveRestartCooldown(now)
            ) {
                val fastStart = DriveDetectionEngine.shouldFastStart(
                    b.currentDriveProbes(), b.vehicleEvidence(), now,
                    b.currentGeofenceContext(), b.currentCadenceHz(),
                    b.currentCadenceValidFraction()
                )
                val classification = DriveDetectionEngine.classify(
                    b.currentDriveProbes(), now, b.currentGeofenceContext(),
                    b.currentMotionContext(), b.currentCadenceHz(),
                    b.currentCadenceValidFraction()
                )
                if (!fastStart && classification is DriveDetectionEngine.Classification.Driving) {
                    b.markDriveConfirmed()
                    val live = manager.liveSession.value
                    val carLive = live != null && live.isLive &&
                        live.activityTypeId == "driving" &&
                        live.sourceType == "ACTIVITY_RECOGNITION_AUTO"
                    if (!carLive) {
                        val session = manager.start(
                            activityTypeId = "driving",
                            title = "Autofahren",
                            sourceType = "ACTIVITY_RECOGNITION_AUTO",
                            startedAt = now
                        )
                        awaitLive(manager, session.id)
                        run.carSessions += session
                        run.log.append("t=${t / 1000}s AUTO-Session (classify=Driving)\n")
                    }
                }
            }
        }
        return run
    }

    /** Der gemeldete Radfahrer: 22 km/h Grundtempo, alle 60 s ein
     *  30-s-Antritt auf 32 km/h → Schnitt ~25 km/h, Spitzen 32 km/h.
     *  Dieses Profil hat VOR dem Fix „Driving 300/300" gemessen
     *  (t_fd1ec671, Research-Report §1.2). */
    private fun userRideSpeed(tMs: Long): Double =
        if ((tMs / 30_000L) % 2L == 1L) 32.0 else 22.0

    // ──────────────────────────────────────────────────────────────
    // 1) AKZEPTANZ: ON_BICYCLE mit 25 km/h ergibt eine Radfahrt
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `ON_BICYCLE mit 25 kmh ergibt eine Radfahrt - keine Autofahrt`() = runTest {
        // DAS AKZEPTANZKRITERIUM des Tasks: „at least one test verifying
        // that ON_BICYCLE at 25 km/h results in a bike trip".
        val run = runRide(minutes = 5, speedAt = { 25.0 }) { b, t ->
            if (t % 30_000L == 0L) feedBikeSample(b, t0 + t)
        }
        println("=== 25 km/h konstant + ON_BICYCLE ===\n${run.log}")

        assertThat(run.carSessions).isEmpty()
        assertThat(run.bikeSession).isNotNull()
        assertThat(run.bikeSession!!.activityTypeId).isEqualTo("radfahren")
        assertThat(run.bikeSession!!.sourceType).isEqualTo("ACTIVITY_RECOGNITION_AUTO")
        // Rückdatierung: die Session beginnt beim ersten bewegten Fix.
        assertThat(run.bikeSession!!.startAt).isEqualTo(t0)
        assertThat(run.bikeSession!!.sessionStatus).isEqualTo("RUNNING")
    }

    @Test
    fun `ON_BICYCLE mit Antritten auf 32 kmh ergibt eine Radfahrt - keine Autofahrt`() = runTest {
        // Der gemeldete User-Fall („so 25 km/h drauf"): ein 25-km/h-Schnitt
        // enthält zwangsläufig Passagen über 28,8 km/h (Antritte/Gefälle).
        val run = runRide(minutes = 10, speedAt = ::userRideSpeed) { b, t ->
            if (t % 30_000L == 0L) feedBikeSample(b, t0 + t)
        }
        println("=== 22 km/h + Antritte 32 km/h (10 Min) ===\n${run.log}")

        assertThat(run.carSessions).isEmpty()
        assertThat(run.bikeSession).isNotNull()
        assertThat(run.bikeSession!!.activityTypeId).isEqualTo("radfahren")
    }

    // ──────────────────────────────────────────────────────────────
    // 2) STILL-Phasen (Task: „simulate ... STILL")
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `STILL-Phase mitten in der Radfahrt aendert nichts an der Klassifikation`() = runTest {
        // Ampel-/Pausen-Stand (0 km/h) über 2 Minuten mitten in der
        // Radfahrt: die Radfahrt bleibt Radfahrt, auch danach. Der
        // Stillstand darf die Rad-Erkennung nicht abreißen lassen und
        // keine Autofahrt erzeugen.
        val stillFrom = 3 * 60_000L
        val stillTo = 5 * 60_000L
        val run = runRide(
            minutes = 8,
            speedAt = { t -> if (t in stillFrom until stillTo) 0.0 else userRideSpeed(t) }
        ) { b, t ->
            if (t % 30_000L == 0L) feedBikeSample(b, t0 + t)
        }
        println("=== Radfahrt mit 2-Min-STILL-Phase ===\n${run.log}")

        assertThat(run.carSessions).isEmpty()
        assertThat(run.bikeSession).isNotNull()
        assertThat(run.bikeSession!!.activityTypeId).isEqualTo("radfahren")
        // Die Serie NACH der Stillstands-Phase belegt die Radfahrt weiter
        // (der Stillstand zerstört die Evidenz nicht — die Engine filtert
        // Stillstands-Probes nur als Start-Anker, nicht als Fahrt-Beweis).
        val probesAfter = run.bridge.currentDriveProbes()
        assertThat(probesAfter).isNotEmpty()
        val rideAfter = DriveDetectionEngine.detectBikeRide(
            probesAfter, probesAfter.last().timestampMs
        )
        assertThat(rideAfter).isNotNull()
        assertThat(rideAfter!!.avgSpeedMps)
            .isLessThan(DriveDetectionEngine.BIKE_DRIVE_SPEED_MPS)
    }

    @Test
    fun `STILL-Samples loesen keinen Walk-Stop aus und verwerfen die Geh-Evidenz nicht`() {
        // Google liefert STILL (Typ 3) und UNKNOWN (Typ 4) auch während
        // einer Fahrt. Beide dürfen den Walk-Stop-Detector nicht
        // beeinflussen: kein Stop, kein Reset (Regel 4 des Detektors).
        val b = bridge()
        val now = t0
        assertThat(b.onWalkStopSample(WalkStopDetector.TYPE_ON_FOOT, 80, now)).isFalse()
        // STILL/UNKNOWN dazwischen — Evidenz bleibt stehen…
        assertThat(b.onWalkStopSample(3, 80, now + 60_000L)).isFalse()
        assertThat(b.onWalkStopSample(4, 80, now + 60_000L)).isFalse()
        // …deshalb feuerte die Gnadenfrist danach regulär (kein Reset).
        assertThat(b.onWalkStopSample(WalkStopDetector.TYPE_ON_FOOT, 80, now + 80_000L)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // 3) FEHLENDE AR-PERMISSION (Task: „missing activity recognition
    //    permission")
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `ohne AR-Permission (keine Samples) - 25 kmh bleibt ohne Autofahrt`() = runTest {
        // Ohne ACTIVITY_RECOGNITION registriert der Requester keinen
        // Stream und der Receiver bricht ab (Permission-Gates) — es kommt
        // also KEIN ON_BICYCLE-Sample. Der Kontext bleibt UNKNOWN
        // (8-m/s-Schwelle). 25 km/h = 6,94 m/s erreicht sie nicht.
        val run = runRide(minutes = 4, speedAt = { 25.0 }) { _, _ -> }
        println("=== 25 km/h ohne AR-Permission ===\n${run.log}")

        assertThat(run.bikeSession).isNull() // ohne Signal keine Rad-Session
        assertThat(run.carSessions).isEmpty() // und keine Autofahrt
    }

    @Test
    fun `ohne AR-Permission bleibt 29 kmh die heutige Autofahrt - ehrlich dokumentiert`() = runTest {
        // ABGRENZUNG (bewusster Trade-off, kein neuer Fehler): Ohne
        // AR-Signal gibt es keinen Rad-Kontext, es gilt die 8-m/s-Schwelle
        // (28,8 km/h) — ein Pedelec/Sportrad über 28,8 km/h wird weiterhin
        // als Fahrt klassifiziert. Der Fix wirkt nur mit AR-Signal
        // (Research-Report t_fd1ec671: „kein neues False-Negative-Risiko").
        val run = runRide(minutes = 4, speedAt = { 29.0 }) { _, _ -> }
        assertThat(run.carSessions).isNotEmpty()
        assertThat(run.bikeSession).isNull()
    }

    // ──────────────────────────────────────────────────────────────
    // 4) null-DATEN (Task: „null activity data")
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `null-Raddaten - keine Rad-Session, kein Crash`() = runTest {
        val b = bridge()
        val manager = LiveActivityManager(
            FakeActivityRepository(), FakeTypeRepository(), FakeTriggerRepository()
        )
        // Kein Rad-Sample → keine belastbare Evidence → kein Start.
        assertThat(b.bicycleEvidence()).isNull()
        assertThat(DriveDetectionEngine.isReliableBicycleSignal(b.bicycleEvidence(), t0)).isFalse()

        // Probes ohne Speed-Feld UND ohne Koordinaten: keine Radfahrt.
        val nullSpeed = (0L..180_000L step 15_000L).map {
            probe(t0 + it, speedMps = null, distanceFromLastM = null, positionM = 0.0)
        }
        assertThat(DriveDetectionEngine.detectBikeRide(nullSpeed, t0 + 180_000L)).isNull()

        // Probes MIT Speed, aber ohne Koordinaten (keine Netto-Distanz):
        val noCoords = (0L..180_000L step 15_000L).map {
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + it, speedMps = kmh(25.0), accuracyMeters = 10f,
                distanceFromLastM = kmh(25.0) * 15.0, latitude = null, longitude = null
            )
        }
        assertThat(DriveDetectionEngine.detectBikeRide(noCoords, t0 + 180_000L)).isNull()

        // Und die Bridge liefert für eine leere Serie überall null/leer.
        assertThat(b.currentDriveProbes()).isEmpty()
        assertThat(manager.liveSession.value).isNull()
    }

    // ──────────────────────────────────────────────────────────────
    // 5) MEHRDEUTIGE SIGNALE (Task: „ambiguous readings")
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `schwache Rad-Confidence blockiert die Autofahrt, erzeugt aber keine Rad-Session`() = runTest {
        // Confidence 59 < BIKE_CONTEXT_MIN_CONFIDENCE (60): keine
        // belastbare Rad-Evidence → keine Rad-Session. Das Kontext-Setzen
        // ist aber bewusst TOGGLE- UND CONFIDENCE-FREI (der Receiver prüft
        // die Confidence im Kontext-Zweig nicht) — deshalb bleibt die
        // Autofahrt trotzdem blockiert.
        val run = runRide(minutes = 6, speedAt = ::userRideSpeed) { b, t ->
            if (t % 30_000L == 0L) feedBikeSample(b, t0 + t, confidence = 59)
        }
        println("=== schwache Confidence (59) ===\n${run.log}")

        assertThat(run.bikeSession).isNull()
        assertThat(run.carSessions).isEmpty()
    }

    @Test
    fun `flackernde AR-Signale - Kontext bleibt unbestimmt, keine Autofahrt bei 25 kmh`() = runTest {
        // Google flackert zwischen ON_BICYCLE und IN_VEHICLE (30-s-Takt,
        // wie in der Praxis bei Zweirädern): die 2-Sample-Hysterese der
        // Bridge bricht jeden Umschlag → Kontext bleibt UNKNOWN. Bei
        // 25 km/h bleibt es trotzdem bei „keine Autofahrt".
        val run = runRide(minutes = 6, speedAt = { 25.0 }) { b, t ->
            if (t % 30_000L == 0L) {
                when ((t / 30_000L) % 2L) {
                    0L -> {
                        b.onBicycleSample()
                        b.onBicycleSampleWithConfidence(85, t0 + t)
                        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
                    }
                    else -> b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
                }
            }
        }

        assertThat(run.carSessions).isEmpty()
    }

    @Test
    fun `ein einzelnes Rad-Sample flippt den Kontext nicht - zwei gewinnen`() {
        val b = bridge()
        // 1 Sample: Hysterese (M18.117) → Kontext bleibt UNKNOWN.
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(b.currentMotionContext()).isEqualTo(DriveDetectionEngine.MotionContext.UNKNOWN)
        // 2. Sample desselben Typs → übernommen.
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(b.currentMotionContext()).isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        // Zwei Fahrzeug-Samples holen den Kontext zurück (Konkurrenzklasse).
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        assertThat(b.currentMotionContext()).isEqualTo(DriveDetectionEngine.MotionContext.IN_VEHICLE)
    }

    @Test
    fun `Rad-Setting aus - 25 kmh bleibt trotzdem ohne Autofahrt`() = runTest {
        // Der Toggle steuert nur Session/Marker, nicht die
        // Klassifikations-Korrektheit (sonst träte der gemeldete Bug genau
        // für User mit deaktivierter Rad-Erkennung wieder auf).
        val run = runRide(
            minutes = 6,
            speedAt = ::userRideSpeed,
            settings = AutomationSettings(bicycleDetectionEnabled = false)
        ) { br, t ->
            if (t % 30_000L == 0L) feedBikeSample(br, t0 + t)
        }
        assertThat(run.bridge.isBicycleEnabled()).isFalse()
        // Der Kontext wirkt trotzdem (toggle-freies Setzen im Receiver).
        assertThat(run.bridge.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(run.carSessions).isEmpty()
        assertThat(run.bikeSession).isNull() // Session ist die Toggle-Wirkung
    }

    // ──────────────────────────────────────────────────────────────
    // 6) BEKANNTE LÜCKE (Follow-up-Karte) — nicht als Bestand gepinnt
    // ──────────────────────────────────────────────────────────────

    /**
     * BEKANNTE LÜCKE (gemessen, Kanban t_88146697 → Follow-up-Karte):
     *
     * Nach einem ON_BICYCLE-EXIT nimmt der Transition-Receiver den
     * Motion-Kontext auf UNKNOWN zurück (2 Samples). Ist der EXIT ein
     * Google-Artefakt (die Lehre aus M18.93v9: „Google liefert
     * regelmäßig EXIT-Artefakte") und fährt der User weiter, gilt für
     * 60 s wieder die 8-m/s-Schwelle: Bei 29 km/h klassifiziert die
     * Engine dann `Driving`, `markDriveConfirmed` + DriveStartWorker
     * starten eine „Autofahren"-Session — der DriveStartWorker-Duplikat-
     * Guard prüft nur `driving`, nicht die laufende `radfahren`-Session,
     * und `isDriveActive` ist für eine Rad-Session false.
     *
     * Gemessen (JVM, echte Produktionsklassen): 29 km/h → Auto-Session
     * 15 s nach dem EXIT, die Rad-Session wird dabei von
     * `trimOverlappingForNewSession` beendet (inserted = [radfahren,
     * driving]); 32 km/h → Auto-Session ebenfalls, Rad-Session bleibt
     * live (Überlappung, inserted = [radfahren, driving]).
     *
     * Der Test bleibt bewusst @Ignore: er kodiert die GEWÜNSCHTE
     * Invariante (kein Auto-Start, solange eine Rad-Session lebt) statt
     * den Bug als Bestand festzuschreiben. Er wird mit dem Fix der
     * Follow-up-Karte aktiviert.
     */
    @Ignore("Bekannte Lücke — Auto-Start über eine laufende Rad-Session im ON_BICYCLE-EXIT-Fenster (siehe Follow-up-Karte)")
    @Test
    fun `Luecke - transienter ON_BICYCLE-EXIT darf keine Autofahrt ueber der Rad-Session starten`() = runTest {
        val b = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        var positionM = 0.0
        var bike: ActivitySession? = null
        val carSessions = mutableListOf<ActivitySession>()

        for (t in 0L..8 * 60_000L step 15_000L) {
            val now = t0 + t
            val mps = kmh(29.0)
            positionM += mps * 15.0
            b.addDriveProbe(probe(now, mps, 10f, mps * 15.0, positionM), false)

            // AR-Continuous-Stream: ON_BICYCLE alle 30 s — MIT dem
            // transienten EXIT-Artefakt bei Minute 3 (2× UNKNOWN, wie der
            // Transition-Receiver ihn setzt), danach läuft der Stream weiter.
            if (t == 3 * 60_000L) {
                repeat(2) { b.updateMotionContext(DriveDetectionEngine.MotionContext.UNKNOWN) }
            } else if (t % 30_000L == 0L) {
                feedBikeSample(b, now)
            }

            if (bike == null) {
                val ride = DriveDetectionEngine.detectBikeRide(b.currentDriveProbes(), now)
                if (ride != null && manager.liveSession.value?.isLive != true) {
                    bike = manager.start(
                        activityTypeId = "radfahren", title = "Radfahren",
                        sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = ride.startMs
                    )
                    awaitLive(manager, bike.id)
                }
            }

            if (!b.isDriveActive() && b.isDrivingEnabled() &&
                !b.isWithinDriveRestartCooldown(now)
            ) {
                val fastStart = DriveDetectionEngine.shouldFastStart(
                    b.currentDriveProbes(), b.vehicleEvidence(), now, b.currentGeofenceContext(),
                    b.currentCadenceHz(), b.currentCadenceValidFraction()
                )
                val classification = DriveDetectionEngine.classify(
                    b.currentDriveProbes(), now, b.currentGeofenceContext(),
                    b.currentMotionContext(), b.currentCadenceHz(), b.currentCadenceValidFraction()
                )
                if (!fastStart && classification is DriveDetectionEngine.Classification.Driving) {
                    b.markDriveConfirmed()
                    val live = manager.liveSession.value
                    val carLive = live != null && live.isLive &&
                        live.activityTypeId == "driving" &&
                        live.sourceType == "ACTIVITY_RECOGNITION_AUTO"
                    if (!carLive) {
                        val s = manager.start(
                            activityTypeId = "driving", title = "Autofahren",
                            sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = now
                        )
                        awaitLive(manager, s.id)
                        carSessions += s
                    }
                }
            }
        }

        assertWithMessage(
            "Solange eine radfahren-Session live ist, darf kein Auto-Start passieren " +
                "(inserted=${repo.inserted.map { it.activityTypeId }})"
        ).that(carSessions).isEmpty()
        assertThat(manager.liveSession.value?.activityTypeId).isEqualTo("radfahren")
    }

    // ── Fakes (Muster: BicycleRideIntegrationTest) ───────────────────

    private class FakeActivityRepository : ActivityRepository {
        val live = MutableStateFlow<ActivitySession?>(null)
        val inserted = mutableListOf<ActivitySession>()

        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override suspend fun getLastFinishedBySourceType(sourceType: String): ActivitySession? = null
        override fun getCurrentActiveSession(): Flow<ActivitySession?> = flowOf(null)
        override fun getLiveSession(): Flow<ActivitySession?> = live
        override suspend fun updateStatus(id: String, status: String) {}
        override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) {}
        override suspend fun pauseSession(id: String, endAt: Long) {}
        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override suspend fun updatePauseData(id: String, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?> = flowOf(null)
        override fun getById(id: String): Flow<ActivitySession?> = flowOf(live.value?.takeIf { it.id == id })
        override fun getByExternalId(externalId: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override suspend fun insert(session: ActivitySession) {
            inserted.add(session)
            if (session.isLive) live.value = session
        }
        override suspend fun insertWithTags(session: ActivitySession, tags: List<com.d_drostes_apps.aevum.data.model.Tag>) {}
        override suspend fun update(session: ActivitySession) {}
        override suspend fun softDelete(id: String, now: Long) {}
        override suspend fun delete(id: String) {}
        override suspend fun setManualQualityOverride(sessionId: String, score: Int?) {}
        override suspend fun setManualQualityOverrideForRange(start: Long, end: Long, score: Int?) {}
        override suspend fun insertTagMapping(mapping: com.d_drostes_apps.aevum.data.model.ActivitySessionTag) {}
        override fun getTagIdsForSession(sessionId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteTagMappings(sessionId: String) {}
        override suspend fun countSessionsByType(typeId: String): Int = 0
        override suspend fun countLiveSessionsByType(typeId: String): Int = 0
        override suspend fun reassignSessionsToType(typeId: String, fallbackTypeId: String, now: Long) {}
        override suspend fun hardDeleteSessionsByType(typeId: String) {}
    }

    private class FakeTypeRepository : ActivityTypeRepository {
        private fun type(id: String) = ActivityType(
            id = id, name = id, defaultCategoryId = null, isSystem = true,
            propertiesJson = null, isFavorite = false, positivityScore = 50,
            icon = "•", color = 0L
        )
        override fun getById(id: String): Flow<ActivityType?> = flowOf(type(id))
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getFavorites(): Flow<List<ActivityType>> = flowOf(emptyList())
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun setPositivityScore(id: String, score: Int) {}
        override suspend fun setIcon(id: String, icon: String) {}
        override suspend fun setColor(id: String, color: Long) {}
        override suspend fun setCategory(id: String, categoryId: String?) {}
        override suspend fun insert(type: ActivityType) {}
        override suspend fun insertAll(types: List<ActivityType>) {}
        override suspend fun update(type: ActivityType) {}
        override suspend fun delete(typeId: String) {}
    }

    private class FakeTriggerRepository : TriggerEventRepository {
        override fun getAll(): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getByGeofenceId(geofenceId: String): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getById(id: String): Flow<TriggerEvent?> = flowOf(null)
        override suspend fun insert(event: TriggerEvent) {}
        override suspend fun insertAll(events: List<TriggerEvent>) {}
        override suspend fun delete(id: String) {}
    }
}
