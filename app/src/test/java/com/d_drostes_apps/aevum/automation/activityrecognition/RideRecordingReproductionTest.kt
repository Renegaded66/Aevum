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
 * REPRODUKTION (Kanban t_eff2f301): „Motorrad-Fahrt, 10 Minuten, gar
 * nichts aufgezeichnet" (Root t_bc68941a).
 *
 * Szenario aus dem User-Report: Fahrtbeginn 30 km/h (8,33 m/s) für die
 * ersten Minuten, dann 70 km/h (19,4 m/s), gesamt 10 Minuten. Erwartung
 * laut Verhalten vor den letzten Updates: Aufzeichnung startete „zu
 * spät" — jetzt: überhaupt keine Aufzeichnung.
 *
 * Die Reproduktion läuft gegen die ECHTEN Produktionsklassen
 * (ActivityRecognitionBridge, DriveDetectionEngine, DriveProbeWorker-
 * Kern, DriveStartWorker-Kern, LiveActivityManager) — Android-/GMS-
 * Schichten werden durch Welt-Modelle ersetzt, die dokumentiert, was
 * Google-AR + GPS auf einer realen Motorradfahrt liefern (Doku:
 * Sensor-Hub klassifiziert Zweiräder/Wohnort-Vibration regelmäßig als
 * WALKING/ON_BICYCLE; BALANCED-Hintergrund-Fixes haben oft accuracy
 * > 50 m oder kein Speed-Feld).
 *
 * Geprüft wird für JEDE Welt, ob am Ende eine Auto-Session existiert —
 * und falls nicht, WELCHES Gate sie blockiert („Logs").
 */
class RideRecordingReproductionTest {

    /** Fahrt-Epoche: Minute 0 = Fahrtbeginn. */
    private val t0 = 1_000_000_000L

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
     * Spiegel der handleFix-Entscheidung (DriveDetectionService M18.128):
     * Cooldown-Gate zuerst (V7), dann shouldFastStart, dann Normalpfad
     * classify — exakt die Reihenfolge des Produktionscodes.
     *
     * @return 0 = kein Start; 1 = FAST-START; 2 = NORMAL (classify)
     */
    private fun handleFixDecision(
        bridge: ActivityRecognitionBridge,
        nowMs: Long
    ): Pair<Int, String> {
        val logs = StringBuilder()
        val withinCooldown = bridge.isWithinDriveRestartCooldown(nowMs)
        if (withinCooldown) {
            return 0 to "COOLDOWN: Restart-Cooldown aktiv"
        }
        if (DriveDetectionEngine.shouldFastStart(
                bridge.currentDriveProbes(),
                bridge.vehicleEvidence(),
                nowMs,
                bridge.currentGeofenceContext(),
                bridge.currentCadenceHz(),
                bridge.currentCadenceValidFraction()
            )
        ) {
            return 1 to "FAST-START (frische IN_VEHICLE-Evidence + 2x >= 8 m/s, Netto >= 100 m)"
        }
        val cls = DriveDetectionEngine.classify(
            bridge.currentDriveProbes(), nowMs, bridge.currentGeofenceContext(),
            bridge.currentMotionContext(),
            bridge.currentCadenceHz(), bridge.currentCadenceValidFraction()
        )
        return when (cls) {
            is DriveDetectionEngine.Classification.Driving ->
                2 to "NORMAL: classify -> Driving(conf=${cls.confidence})"
            is DriveDetectionEngine.Classification.NotDriving ->
                0 to "NORMAL: classify -> NotDriving"
            DriveDetectionEngine.Classification.InsufficientData ->
                0 to "NORMAL: classify -> InsufficientData"
        }.let { it.first to (logs.toString() + it.second) }
    }

    /** DriveStartWorker-Kern (DriveWorkers.kt 154-224): Start-Gate +
     *  Cluster-Anker + Session-Start über den echten LiveActivityManager. */
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
        if (!confirmedFresh && !gpsOk) {
            return null // AR-Start-Gate: keine Bestätigung
        }
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

    /** Bestätigung + Drain + Start-Worker — wie handleFix (M18.128-
     *  Erfolgsblock) und DriveProbeWorker (4). */
    private suspend fun commitStart(
        bridge: ActivityRecognitionBridge,
        manager: LiveActivityManager,
        nowMs: Long,
        kind: String
    ): ActivitySession {
        bridge.markDriveConfirmed()
        bridge.drainDriveProbes()
        bridge.resetVehicleEvidence()
        return driveStartCore(bridge, manager, nowMs)!!
    }

    // ════════════════════════════════════════════════════════════════
    // WELT A: IDEAL-AR — Google meldet durchgehend IN_VEHICLE (conf 85),
    // CONFIRM-Burst (HIGH, 15s) läuft ab dem ersten Sample.
    // Erwartung: Fast-Start bereits in der 30-km/h-Phase.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Welt A - ideales AR (IN_VEHICLE) liefert Fast-Start in der 30er-Phase`() = runTest {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        var session: ActivitySession? = null
        val log = StringBuilder()
        var fixNo = 0
        var positionM = 0.0

        // AR-Continuous-Sample (30s-Takt) + CONFIRM-Burst-Fixes (15s).
        // Erster Fix des Bursts nach ~20s GPS-Kaltstart-Warmup + Fix-
        // Latenz; dann alle 15s. Probe-Geometrie der 30-km/h-Phase.
        for (t in 0L..600_000L step 15_000L) {
            val now = t0 + t
            // AR-Sample alle 30s (Receiver-Zweig IN_VEHICLE).
            if (t % 30_000L == 0L) {
                bridge.onVehicleSample(confidence = 85, nowMs = now)
            }
            // GPS-Fix: 30 km/h bis Minute 3, dann 70 km/h.
            val speed = if (t < 3 * 60_000L) KMH_30.toFloat() else KMH_70.toFloat()
            fixNo++
            positionM += speed * 15.0
            bridge.addDriveProbe(
                probe(
                    timestampMs = now,
                    speedMps = speed,
                    accuracy = 10f,
                    distanceFromLastM = speed * 15.0,
                    latitude = latFor(positionM)
                ), false
            )
            val (decision, reason) = handleFixDecision(bridge, now)
            if (decision > 0) {
                log.append("t=${t / 1000}s ${if (decision == 1) "FAST-START" else "NORMAL"} -> $reason\n")
                session = commitStart(bridge, manager, now, "fast")
                break
            }
        }
        println("=== WELT A (ideales AR): ${if (session != null) "AUFGEZEICHNET" else "NICHTS"} ===")
        print(log)
        assertThat(session).isNotNull()
    }

    // ════════════════════════════════════════════════════════════════
    // WELT B: MOTORRAD-AR — Google klassifiziert die Fahrt die GANZE
    // Zeit als WALKING/RUNNING (Sensor-Hub-Muster bei Zweirädern:
    // Motor-Vibration/Neigung = „Gehen"; IN_VEHICLE kommt beim
    // Motorrad real selten). Folgen im Produktionscode:
    //   • WALKING-Samples → Motion-Kontext ON_FOOT (nach 2 Samples)
    //     → Auto-Schwelle 12 m/s: 30-km/h-Phase (8,3 m/s) stirbt.
    //   • WALKING/RUNNING-Zweig des Receivers startet einen
    //     WALKING_CHECK-Burst (BALANCED, 60s-Fixes) — KEIN CONFIRM-
    //     Burst. Der Burst ist nach 8 Min + 1 Verlängerung vorbei;
    //     ergebnislos → WALKING_Cooldown 10 Min → für den Rest der
    //     10-Min-Fahrt kommt NIE wieder ein GPS-Fenster.
    //   • Die Walking-Phase selbst wird durch das Fahrzeug-Tempo-Veto
    //     (M18.110/113) sofort verworfen → auch keine Spazieren-
    //     Aufzeichnung.
    // Fixes im REALEN Hintergrund-Burst (BALANCED, 60s): oft accuracy
    // > 50 m oder ohne Speed-Feld (Doze/OEM, Stadt-Canyon).
    // Erwartung: NICHTS — Totalausfall wie gemeldet. „Früher spät"
    // entstand aus demselben Muster mit besseren Fixes (Welt E).
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Welt B - Motorrad-AR (WALKING-dominant) mit 60s-BALANCED-Fixes - Totalausfall`() = runTest {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        var session: ActivitySession? = null
        val log = StringBuilder()
        var positionM = 0.0

        // 60s-Fixes (WALKING_CHECK-Burst-Geometrie) über 10 min:
        // 30 km/h bis min 3, dann 70 km/h. Fix-Qualität alterniert —
        // realer BALANCED-Hintergrund (accuracy 60-120m ODER kein Speed).
        var fixIndex = 0
        for (t in 0L..600_000L step 60_000L) {
            val now = t0 + t
            // AR-Sample alle 30s: IMMER WALKING (conf 75) — Motorrad-Muster.
            bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
            val speed = if (t < 3 * 60_000L) KMH_30.toFloat() else KMH_70.toFloat()
            positionM += speed * 60.0
            val accuracy = if (fixIndex % 3 == 0) 80f else 30f
            val withSpeed = fixIndex % 3 != 1
            fixIndex++
            bridge.addDriveProbe(
                probe(
                    timestampMs = now,
                    speedMps = if (withSpeed) speed else null,
                    accuracy = accuracy,
                    distanceFromLastM = if (withSpeed) null else speed * 60.0,
                    latitude = latFor(positionM)
                ), false
            )
            log.append("Fix@${t / 1000}s: ${"%.1f".format(speed)} km/h-Ziel, acc=${accuracy.toInt()}m, speedFeld=${if (withSpeed) "ja" else "nein"}, ctx=${bridge.currentMotionContext()}\n")
            val (decision, reason) = handleFixDecision(bridge, now)
            if (decision > 0) {
                log.append("  -> START: $reason\n")
                session = commitStart(bridge, manager, now, "b2")
                break
            } else {
                log.append("  -> kein Start: $reason\n")
            }
        }
        println("=== WELT B (Motorrad-AR, WALKING-dominant, 60s-Fixes): ${if (session != null) "AUFGEZEICHNET @ ${(session!!.startAt - t0) / 1000}s" else "NICHTS - 10 Min ohne Aufzeichnung (User-Fall reproduziert)"} ===")
        print(log)
        assertThat(session).isNull()
    }

    // ════════════════════════════════════════════════════════════════
    // WELT C: KEIN AR (Permission weg / GMS liefert nichts) — nur der
    // DriveProbeWorker (2-Min-Takt, BALANCED-Fix, ggf. ohne Speed-Feld).
    // Fixes sauber (accuracy 20m). Erwartung: 70-km/h-Phase erkennt.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Welt C - kein AR, DriveProbeWorker 2-Min-Takt - 70er-Phase startet spaet oder gar nicht`() = runTest {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        var session: ActivitySession? = null
        val log = StringBuilder()
        var lastPos = 0.0
        var lastFixT: Long? = null

        // DriveProbeWorker-Takt: Fix bei ~2, 4, 6, 8, 10 Min.
        for (probeMin in listOf(2L, 4L, 6L, 8L, 10L)) {
            val now = t0 + probeMin * 60_000L
            val speed = if (probeMin < 3) KMH_30.toFloat() else KMH_70.toFloat()
            val distSinceLast = speed * (probeMin - (lastFixT?.let { (probeMin * 60_000L - it) / 1000.0 } ?: 120.0))
            lastPos += speed * 60.0 * 2.0
            // BALANCED-Fix: hat teils KEIN Speed-Feld (Hintergrund;
            // M18.77) — hier abwechselnd Speed / nur Distanz.
            val withSpeed = probeMin % 2L == 0L
            bridge.addDriveProbe(
                probe(
                    timestampMs = now,
                    speedMps = if (withSpeed) speed else null,
                    accuracy = 20f,
                    distanceFromLastM = speed * 120.0,
                    latitude = latFor(lastPos)
                ), false
            )
            lastFixT = now
            log.append("Probe@Min$probeMin: speed=${if (withSpeed) "%.1f".format(speed) else "null"} m/s, ctx=${bridge.currentMotionContext()}\n")
            val (decision, reason) = handleFixDecision(bridge, now)
            if (decision > 0) {
                log.append("  -> START (${if (decision == 1) "FAST" else "NORMAL"}): $reason\n")
                session = commitStart(bridge, manager, now, "probe")
                break
            } else {
                log.append("  -> kein Start: $reason\n")
            }
        }
        println("=== WELT C (kein AR, 2-Min-Probes): ${if (session != null) "AUFGEZEICHNET (Start @ Min ${(session!!.startAt - t0) / 60_000})" else "NICHTS"} ===")
        print(log)
    }

    // ════════════════════════════════════════════════════════════════
    // WELT D: KEIN AR + BALANCED-Fixes unbrauchbar (accuracy > 50m,
    // kein Speed, GPS-Chip im Schlaf) — der reale „tote" Fallback.
    // Erwartung: NICHTS — Totalausfall wie gemeldet.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Welt D - kein AR + unbrauchbare BALANCED-Fixes - Totalausfall wie gemeldet`() = runTest {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        var session: ActivitySession? = null
        val log = StringBuilder()

        for (probeMin in listOf(2L, 4L, 6L, 8L, 10L)) {
            val now = t0 + probeMin * 60_000L
            // Hintergrund-BALANCED: GPS-Chip wach nur kurz, Fix oft
            // inakkurat (Stadt-Canyon 60-120m) oder ohne Speed.
            val accuracy = if (probeMin % 2L == 0L) 80f else 12f
            val withSpeed = probeMin % 3L != 0L
            bridge.addDriveProbe(
                probe(
                    timestampMs = now,
                    speedMps = if (withSpeed) KMH_70.toFloat() else null,
                    accuracy = accuracy,
                    distanceFromLastM = if (withSpeed) null else KMH_70 * 120.0,
                    latitude = latFor(KMH_70 * 120.0 * probeMin)
                ), false
            )
            log.append("Probe@Min$probeMin: acc=${accuracy.toInt()}m speed=${if (withSpeed) "ja" else "null"}\n")
            val (decision, reason) = handleFixDecision(bridge, now)
            if (decision > 0) {
                log.append("  -> START: $reason\n")
                session = commitStart(bridge, manager, now, "probe")
                break
            } else {
                log.append("  -> kein Start: $reason\n")
            }
        }
        println("=== WELT D (kein AR + kaputte Fixes): ${if (session != null) "AUFGEZEICHNET" else "NICHTS - Totalausfall reproduziert"} ===")
        print(log)
        assertThat(session).isNull()
    }

    // ════════════════════════════════════════════════════════════════
    // WELT E: MOTORRAD-AR (WALKING-dominant, wie Welt B) ABER der
    // WALKING_CHECK-Burst liefert dichte 15s-Fixes ohne Speed-Feld —
    // der M18.77-Distanz-Fallback (speed = dist/dt, ≥ 5,5 m/s) rettet
    // die 70-km/h-Phase (19,4 m/s): 2 konsekutive ≥ 12-m/s-Ableitungen
    // (ON_FOOT-Gate erfüllt) → „zu spät"-Start nach ~3,5 Min. Das ist
    // das HISTORISCHE Verhalten („Aufzeichnung startet zu spät").
    // Erwartung: Start nach der 30er-Phase — ABER NUR bei Fix-Dichte
    // ≤ 30s (BALANCED-Walking-Burst liefert real 60s → Welt B).
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Welt E - Motorrad-AR + 15s-Fixes ohne Speed - Distanz-Fallback startet erst in der 70er-Phase`() = runTest {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        var session: ActivitySession? = null
        val log = StringBuilder()
        var positionM = 0.0
        var lastPos: Double? = null

        for (t in 0L..600_000L step 15_000L) {
            val now = t0 + t
            // AR: IMMER WALKING (Motorrad-Muster) → ON_FOOT-Kontext.
            bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
            val speed = if (t < 3 * 60_000L) KMH_30.toFloat() else KMH_70.toFloat()
            positionM += speed * 15.0
            val dist = lastPos?.let { positionM - it } ?: (speed * 15.0)
            lastPos = positionM
            bridge.addDriveProbe(
                probe(now, null, 10f, dist, latFor(positionM)), false
            )
            val (decision, reason) = handleFixDecision(bridge, now)
            if (decision > 0) {
                log.append("t=${t / 1000}s START (${if (decision == 1) "FAST" else "NORMAL"}): $reason\n")
                session = commitStart(bridge, manager, now, "dist")
                break
            }
        }
        println("=== WELT E (Motorrad-AR + 15s-Fixes ohne Speed): ${if (session != null) "AUFGEZEICHNET @ ${(session!!.startAt - t0) / 1000}s" else "NICHTS"} ===")
        print(log)
        // „Früher spät": Start frühestens nach der 30er-Phase (t>180s).
        assertThat(session).isNotNull()
        assertThat(session!!.startAt - t0).isGreaterThan(3L * 60_000L)
    }

    // ── Fakes (Muster: DriveLeadTimeReproductionTest) ────────────────

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
