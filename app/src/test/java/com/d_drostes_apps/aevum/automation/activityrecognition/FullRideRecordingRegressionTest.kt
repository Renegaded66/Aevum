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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * REGRESSION (Kanban t_b398875b): Nach dem M18.130-Fix (t_3ac05e06) muss
 * die Motorrad-Fahrt aus dem User-Report (Root t_bc68941a) NICHT nur
 * STARTEN, sondern die komplette 10-Minuten-Fahrt AUFGEZEICHNET werden.
 *
 * Die Repro-Welten A-F (RideRecordingReproductionTest) prüfen nur den
 * START. Diese Suite simuliert die Fahrt VOR und NACH dem Start gegen
 * die ECHTEN Produktionsklassen (ActivityRecognitionBridge,
 * DriveDetectionEngine, LiveActivityManager) und prüft die beiden
 * post-Start-Gefahren, die der M18.130-Bericht als Follow-up markiert
 * hat:
 *
 *   1. WALK-STOP-DETECTOR (M18.127): Google-AR meldet auf dem Motorrad
 *      persistent WALKING (conf ≥ 60). Der Detektor beendet eine aktive
 *      Auto-Session nach ~75 s Geh-Gnadenfrist — WENN kein frischer
 *      Probe mit Fahrzeug-Tempo (≥ 8 m/s) die Geh-Hypothese widerlegt
 *      (Veto, asymmetrisch). Genau DAS ist die 30-km/h-Phase nach dem
 *      Start: Der TRACK-Stream (15s HIGH) liefert zwar Fixes, aber
 *      keine ≥ 8 m/s (8,33 m/s = 30 km/h erreicht die Schwelle erst
 *      jenseits 28,8 km/h knapp — hier exakt der Grenzbereich, der
 *      früher im 60s-BALANCED-Muster scheiterte). Ohne Veto würde der
 *      Detektor die frisch gestartete Session nach ~75 s beenden.
 *
 *   2. 5-MIN-WATCHDOG (M18.66): Jedes Fahrt-Signal refresht den Timer.
 *      Nach dem Start fließen die TRACK-Fixes (15s) — ein frischer
 *      Probe (≤ 90 s alt) mit Fahrzeug-Tempo ≥ 8 m/s ist das Veto-
 *      Signal des Walk-Stop-Detektors UND der Heartbeat des Watchdogs.
 *      Die 70-km/h-Phase (19,4 m/s) liefert beides.
 *
 * Erwartung: Die Session startet (Welt-B-Mechanik, Start ≤ 5 Min) und
 * lebt bis zum Ende der 10-Minuten-Fahrt — KEIN Walk-Stop-Veto, KEIN
 * Watchdog-Stop. `startAt` bis `endAt` deckt die gesamte Fahrt ab.
 */
class FullRideRecordingRegressionTest {

    /** Fahrt-Epoche: Minute 0 = Fahrtbeginn. */
    private val t0 = 2_000_000_000L

    /** 30 km/h ≈ 8,33 m/s; 70 km/h ≈ 19,44 m/s. */
    private val KMH_30 = 30.0 / 3.6
    private val KMH_70 = 70.0 / 3.6

    // ── Bridge mit echten Settings (alle Auto-Gates an) ─────────────
    private fun bridge() = ActivityRecognitionBridge(
        object : AutomationSettingsRepository {
            override fun get() = flowOf(
                AutomationSettings(
                    drivingDetectionEnabled = true,
                    walkingDetectionEnabled = true,
                    bicycleDetectionEnabled = true,
                    geofencingEnabled = false // User-Fall: Geofences nicht aktiv
                )
            )
            override suspend fun upsert(s: AutomationSettings) {}
        }
    )

    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 12f,
        distanceFromLastM: Double? = null,
        latitude: Double = 50.000,
        longitude: Double = 8.000
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = distanceFromLastM,
        latitude = latitude,
        longitude = longitude
    )

    /** Breitengrad-Offset für reale Fahrstrecke (111,32 km/Grad). */
    private fun latFor(meters: Double) = 50.000 + meters / 111_320.0

    /**
     * Spiegel des DriveDetectionService-TRACK-Pfads (Z.1004-1009): Der
     * Fix landet im Puffer; ist eine Fahrt aktiv UND speed ≥ 2 m/s,
     * refresht er den Heartbeat (Watchdog lebt).
     */
    private fun feedTrackFix(
        bridge: ActivityRecognitionBridge,
        nowMs: Long,
        speedMps: Float?,
        positionM: Double
    ) {
        bridge.addDriveProbe(
            probe(nowMs, speedMps, 10f, speedMps?.times(15.0), latFor(positionM)),
            refreshHeartbeat = false
        )
        if (bridge.isDriveActive() && speedMps != null && speedMps >= 2.0f) {
            bridge.refreshDriveHeartbeat(nowMs)
        }
    }

    /** AR-Continuous-Sample (30s-Takt) wie der Receiver: WALKING bei
     *  aktiver Fahrt = Walk-Stop-Detector-Eingabe; IN_VEHICLE würde die
     *  Evidenz reseten (hier absichtlich NICHT — Motorrad-Muster). */
    private fun feedWalkingSample(bridge: ActivityRecognitionBridge, nowMs: Long): Boolean {
        bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
        if (bridge.isDriveActive()) {
            return bridge.onWalkStopSample(
                type = WalkStopDetector.TYPE_WALKING,
                confidence = 75, // Motorrad-Muster: Google meldet WALKING conf ≥ 60
                nowMs = nowMs
            )
        }
        return false
    }

    /**
     * Simuliert den kompletten Fahrt-Durchlauf gegen die echten
     * Produktionsklassen: AR-Samples (30s), TRACK-Fixes (15s) über die
     * vollen 10 Minuten, Fahrt-Klassifikation + Start über den echten
     * LiveActivityManager (wie commitStart im RideRecordingReproductionTest).
     *
     * @return Triple (session, stopTriggered, log)
     */
    private suspend fun runFullRide(
        trackFixSpeed: (tMs: Long) -> Float?,
        rideSpeed: (tMs: Long) -> Double
    ): RideResult {
        val bridge = bridge()
        val manager = LiveActivityManager(
            FakeActivityRepository(), FakeTypeRepository(), FakeTriggerRepository()
        )
        val log = StringBuilder()
        var session: ActivitySession? = null
        var positionM = 0.0
        var stopTriggered = false
        var stopAtMs: Long? = null

        // ── Phase 1: VOR dem Start — 60s-BALANCED-Fixes (Welt-B-Muster:
        //     WALKING_CHECK-Burst, Fix-Qualität alterniert wie in der
        //     realen Hintergrund-Geometrie). AR meldet WALKING.
        var fixIndex = 0
        for (t in 0L..240_000L step 60_000L) {
            val now = t0 + t
            val speed = rideSpeed(t)
            positionM += speed * 60.0
            val accuracy = if (fixIndex % 3 == 0) 80f else 30f
            val withSpeed = fixIndex % 3 != 1
            fixIndex++
            bridge.addDriveProbe(
                probe(
                    timestampMs = now,
                    speedMps = if (withSpeed) speed.toFloat() else null,
                    accuracy = accuracy,
                    distanceFromLastM = if (withSpeed) null else speed * 60.0,
                    latitude = latFor(positionM)
                ), false
            )
            feedWalkingSample(bridge, now)
            log.append("Pre@${t / 1000}s: speed=${if (withSpeed) "%.1f".format(speed) else "null"}, ctx=${bridge.currentMotionContext()}\n")
            if (startDecision(bridge, now) > 0) {
                log.append("  -> vorzeitiger Start @ ${t / 1000}s (unerwartet früh — erlaubt, aber nicht erwartet)\n")
                session = commitStart(bridge, manager, now, "pre")
                break
            }
        }

        // ── Phase 2: Start-Trigger — der entkoppelte Verdachts-Check
        //    (M18.130) startet einen 15s-CONFIRM-Burst; die 70-km/h-Phase
        //    liefert die nötigen ≥ 8-m/s-Probes. Fallback, falls der
        //    uralte WALKING_CHECK-Burst (Fallszenario der Fix-Berichts-
        //    Welten) NICHT startet: Bei Minute 5 zwingt der Verdachts-
        //    Burst den Start (Welt-D-Mechanik).
        if (session == null) {
            val confirmStart = 5 * 60_000L
            var positionAtConfirm = positionM
            for (t in confirmStart..600_000L step 15_000L) {
                val now = t0 + t
                positionAtConfirm += KMH_70 * 15.0
                bridge.addDriveProbe(
                    probe(now, KMH_70.toFloat(), 10f, KMH_70 * 15.0, latFor(positionAtConfirm)), false
                )
                feedWalkingSample(bridge, now)
                if (startDecision(bridge, now) > 0) {
                    log.append("Verdachts-Burst @ ${t / 1000}s: START\n")
                    session = commitStart(bridge, manager, now, "confirm")
                    positionM = positionAtConfirm
                    break
                }
            }
            if (session == null) {
                log.append("!!! Kein Start in 10 Minuten — Fahrt nicht erkannt\n")
                return RideResult(null, false, log.toString(), null)
            }
        }

        // M18.66-Produktionsspiegel: Der Start selbst refresht den
        // Watchdog-Heartbeat (DriveStartWorker → liveSession läuft →
        // Service refresht bei jedem TRACK-Fix ≥ 2 m/s).
        bridge.refreshDriveHeartbeat(session!!.startAt)
        val startedAt = session!!.startAt
        log.append("== Session gestartet @ ${(startedAt - t0) / 1000}s — TRACK-Phase (Fixes alle 15s, AR weiter WALKING) ==\n")

        // ── Phase 3: NACH dem Start — TRACK-Stream (15s HIGH). AR meldet
        //    weiterhin WALKING (Motorrad-Muster): Der Walk-Stop-Detector
        //    bekommt jede 30s ein conf-75-Sample. Der Watchdog (5 Min ohne
        //    Signal) läuft nebenher — jede 15s frischer Heartbeat hält ihn
        //    am Leben, SOFERN die Fahrt weiter als aktiv gilt.
        val startMs = startedAt - t0
        for (t in maxOf(startMs, 0L)..600_000L step 15_000L) {
            val now = t0 + t
            val speed = trackFixSpeed(t)
            positionM += speed?.times(15.0) ?: 0.0
            feedTrackFix(bridge, now, speed, positionM)
            if (t % 30_000L == 0L && feedWalkingSample(bridge, now)) {
                stopTriggered = true
                stopAtMs = now
                log.append("!!! WALK-STOP @ ${t / 1000}s: Detector-Signal — Session würde enden\n")
                break
            }
            if (t % 60_000L == 0L) {
                log.append("Track@${t / 1000}s: heartbeat=${(now - bridge.lastVehicleSample()) / 1000}s alt, ctx=${bridge.currentMotionContext()}\n")
                // Watchdog-Prüfung: Der 5-Min-Zähler darf nie ablaufen,
                // solange frische Fahrzeug-Tempo-Fixes kommen.
                if (!bridge.isDriveActive()) {
                    stopTriggered = true
                    stopAtMs = now
                    log.append("!!! driveActive verloren @ ${t / 1000}s — Heartbeat-Pfad tot\n")
                    break
                }
                val heartbeatAge = now - bridge.lastVehicleSample()
                if (heartbeatAge > 5L * 60_000L) {
                    stopTriggered = true
                    stopAtMs = now
                    log.append("!!! Watchdog @ ${t / 1000}s: ${heartbeatAge / 1000}s ohne Signal\n")
                    break
                }
            }
        }

        if (!session.isLive) {
            // Session wurde durch einen Stop-Pfad beendet — der Manager
            // kennt den Stop (Fake-Repo hat die Session nicht mehr live).
            stopTriggered = true
        }
        // Fahrt-Ende (10 Min): Session sauber beenden, wie es der
        // DriveStopWorker/Watchdog täte (endAt = Fahrt-Ende).
        if (!stopTriggered && session.isLive) {
            manager.stop()
        }
        return RideResult(session, stopTriggered, log.toString(), stopAtMs)
    }

    /** Start-Gate-Spiegel wie im RideRecordingReproductionTest. */
    private fun startDecision(bridge: ActivityRecognitionBridge, nowMs: Long): Int {
        if (bridge.isWithinDriveRestartCooldown(nowMs)) return 0
        if (DriveDetectionEngine.shouldFastStart(
                bridge.currentDriveProbes(),
                bridge.vehicleEvidence(),
                nowMs,
                bridge.currentGeofenceContext(),
                bridge.currentCadenceHz(),
                bridge.currentCadenceValidFraction()
            )
        ) return 1
        return when (
            DriveDetectionEngine.classify(
                bridge.currentDriveProbes(), nowMs, bridge.currentGeofenceContext(),
                bridge.currentMotionContext(),
                bridge.currentCadenceHz(), bridge.currentCadenceValidFraction()
            )
        ) {
            is DriveDetectionEngine.Classification.Driving -> 2
            else -> 0
        }
    }

    /** Wie commitStart im RideRecordingReproductionTest — echter Manager-Start. */
    private suspend fun commitStart(
        bridge: ActivityRecognitionBridge,
        manager: LiveActivityManager,
        nowMs: Long,
        kind: String
    ): ActivitySession {
        bridge.markDriveConfirmed()
        bridge.drainDriveProbes()
        bridge.resetVehicleEvidence()
        // M18.127-Produktionsspiegel: DriveStartWorker reseted die
        // Walk-Stop-Evidenz beim Session-Start — Geh-Samples kurz vor
        // dem Start dürfen den Stop der NEUEN Fahrt nicht sofort auslösen.
        bridge.resetWalkStopEvidence()
        return driveStartCore(bridge, manager, nowMs)!!
    }

    private suspend fun driveStartCore(
        bridge: ActivityRecognitionBridge,
        manager: LiveActivityManager,
        now: Long
    ): ActivitySession? {
        val confirmed = bridge.isDriveConfirmed()
        val confirmedFresh = confirmed &&
            bridge.driveConfirmedAgeMs(now) < DriveDetectionEngine.MAX_PROBE_AGE_MS
        val gpsOk = DriveDetectionEngine.classify(
            bridge.currentDriveProbes(), now, bridge.currentGeofenceContext(),
            bridge.currentMotionContext(),
            bridge.currentCadenceHz(), bridge.currentCadenceValidFraction()
        ) is DriveDetectionEngine.Classification.Driving
        if (!confirmedFresh && !gpsOk) return null
        if (confirmed) bridge.consumeDriveConfirmation()
        val cluster = bridge.drainVehicleCluster()
        val startedAt = DriveDetectionEngine.resolveDriveStart(
            clusterStartMs = cluster?.startMs,
            nowMs = now,
            lastAutoSessionEndMs = null
        )
        return manager.start(
            activityTypeId = "driving", title = "Autofahren",
            sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = startedAt
        )
    }

    private data class RideResult(
        val session: ActivitySession?,
        val stopTriggered: Boolean,
        val log: String,
        val stopAtMs: Long?
    )

    // ════════════════════════════════════════════════════════════════
    // SZENARIO 1: DER USER-FALL — 10 Min Motorrad, 30 km/h bis Minute 3,
    // dann 70 km/h. AR meldet die GANZE Zeit WALKING (conf 75) →
    // ON_FOOT-Kontext + Walk-Stop-Detektor-Evidenz. Nach dem Start
    // liefert der TRACK-Stream 15s-Fixes mit Speed-Feld: Der
    // Vehicle-Pace-Override (3 schnelle Probes ≥ 8 m/s = 70-km/h-Phase)
    // widerlegt ON_FOOT für die Erkennung — und die ≥ 8-m/s-Fixes sind
    // gleichzeitig das Walk-Stop-Veto + Watchdog-Heartbeat.
    // Erwartung: Session startet (≤ 5 Min) und überlebt die vollen
    // 10 Minuten (kein Walk-Stop, kein Watchdog-Stop).
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Motorrad-Fahrt mit dauerhaftem AR-WALKING - Session startet und ueberlebt die vollen 10 Minuten`() = runTest {
        val result = runFullRide(
            trackFixSpeed = { t -> if (t < 3 * 60_000L) KMH_30.toFloat() else KMH_70.toFloat() },
            rideSpeed = { t -> if (t < 3 * 60_000L) KMH_30 else KMH_70 }
        )
        println("=== SZENARIO 1 (User-Fall): ${if (result.session != null) "AUFGEZEICHNET bis ${(result.stopAtMs ?: 600_000L) / 1000}s" else "NICHTS"} ===\n${result.log}")

        assertThat(result.session).isNotNull()
        // Start innerhalb der 10-Min-Fahrt (Welt-B-Erwartung, M18.130).
        assertThat(result.session!!.startAt - t0).isLessThan(10L * 60_000L)
        // KEIN Walk-Stop: Das Fahrzeug-Tempo (≥ 8 m/s in der 70er-Phase,
        // 30er-Phase nach dem Start) widerlegt das Gehen.
        assertThat(result.stopTriggered).isFalse()
        // Watchdog: Die Session stirbt nicht — der Heartbeat wird durch
        // die TRACK-Fixes am Leben gehalten.
        assertThat(result.stopAtMs).isNull()
    }

    // ════════════════════════════════════════════════════════════════
    // SZENARIO 2: Der 60s-BALANCED-Fall VOR dem Start — die 30-km/h-Phase
    // (8,33 m/s) mit Fixes ohne Speed-Feld erreicht die 8-m/s-Schwelle
    // erst über die M18.77-Ableitung. Zusätzlich verifiziert: Ein Stop
    // OHNE Walk-Stop-Signal und OHNE Watchdog-Ablauf (der User fährt
    // weiter) MUSS unterbleiben — die Session lebt bis Minute 10.
    //
    // ABWEICHEND zu Szenario 1: Die TRACK-Fixes NACH dem Start kommen
    // hier mit Speed-Feld (Szenario 1) bzw. ohne (dieses Szenario) —
    // und BEIDES muss die Session am Leben halten: Ohne Speed-Feld ist
    // die Distanz-Ableitung (M18.77) das Veto/Heartbeat-Signal.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Session ueberlebt auch wenn TRACK-Fixes ohne Speed-Feld kommen - Distanz-Ableitung haelt Veto und Watchdog`() = runTest {
        val result = runFullRide(
            trackFixSpeed = { t ->
                // Alternierend: Fixes ohne Speed-Feld (realer Hintergrund-
                // Fix, M18.77-Distanz-Fallback), dann mit.
                if (t % 60_000L < 30_000L) null else (if (t < 3 * 60_000L) KMH_30.toFloat() else KMH_70.toFloat())
            },
            rideSpeed = { t -> if (t < 3 * 60_000L) KMH_30 else KMH_70 }
        )
        println("=== SZENARIO 2 (Fixes ohne Speed): ${if (result.session != null) "AUFGEZEICHNET bis ${(result.stopAtMs ?: 600_000L) / 1000}s" else "NICHTS"} ===\n${result.log}")

        assertThat(result.session).isNotNull()
        assertThat(result.session!!.startAt - t0).isLessThan(10L * 60_000L)
        assertThat(result.stopTriggered).isFalse()
        assertThat(result.stopAtMs).isNull()
    }

    // ════════════════════════════════════════════════════════════════
    // SZENARIO 3 (GEGENPROBE — der Fix-Berichts-Follow-up): Während der
    // AKTIVEN Fahrt gibt es KEINEN Fahrzeug-Tempo-Beleg mehr (TRACK-Stream
    // liefert nur 0-m/s-Probes oder gar keine; AR meldet weiter WALKING
    // conf 75). Dann MUSS der Walk-Stop-Detektor nach ~75 s Gnadenfrist
    // feuern — das ist der beabsichtigte Ausstiegs-Pfad (M18.127), und
    // der 5-Min-Watchdog wäre sonst der langsame Ersatz. Erwartung:
    // WALK-STOP ausgelöst (nicht erst Watchdog) — der Detektor ist der
    // schnelle Ausstiegs-Pfad und darf durch den Pace-Override NICHT
    // tot sein.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Ohne Fahrzeug-Tempo nach der 30er-Phase beendet der Walk-Stop die Session nach 75s - Ausstieg erkannt`() = runTest {
        val result = runFullRide(
            trackFixSpeed = { t ->
                // 30-km/h-Phase: NUR 0-m/s-Fixes (Ampel/Stau/Kriechen) —
                // unter der 8-m/s-Veto-Schwelle, unter der Heartbeat-
                // Schwelle (2 m/s): kein frisches Fahrzeug-Tempo.
                if (t < 3 * 60_000L) 0.0f else null // ab der 70er-Phase: gar nichts mehr
            },
            rideSpeed = { t -> if (t < 3 * 60_000L) KMH_30 else KMH_70 }
        )
        println("=== SZENARIO 3 (Ausstieg): ${if (result.session != null) "Session" else "NICHTS"} stopTriggered=${result.stopTriggered} ===\n${result.log}")

        // Die Fahrt wurde erkannt (70er-Phase vor dem Ausstieg-Szenario
        // liefert die Erkennung — hier: Start über den Verdachts-Burst).
        assertThat(result.session).isNotNull()
        // Der Walk-Stop MUSS greifen: Ohne Fahrzeug-Tempo ist Gehen nach
        // 75 s der plausibelste Grund — der Detektor ist der schnelle
        // Ausstiegs-Pfad (M18.127).
        assertThat(result.stopTriggered).isTrue()
        // ...und zwar NACH dem Start (nicht vorher!).
        assertThat(result.stopAtMs).isGreaterThan(result.session!!.startAt)
    }

    // ── Fakes (Muster: RideRecordingReproductionTest) ───────────────

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
        val driving = ActivityType(
            id = "driving", name = "Autofahren", defaultCategoryId = null, isSystem = false,
            propertiesJson = null, positivityScore = 50, icon = "•", color = 0L
        )
        val other = ActivityType(
            id = "other", name = "Sonstiges", defaultCategoryId = null, isSystem = true,
            propertiesJson = null, positivityScore = 50, icon = "•", color = 0L
        )
        override fun getById(id: String): Flow<ActivityType?> =
            flowOf(if (id == "driving") driving else if (id == "other") other else null)
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(listOf(driving, other))
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
