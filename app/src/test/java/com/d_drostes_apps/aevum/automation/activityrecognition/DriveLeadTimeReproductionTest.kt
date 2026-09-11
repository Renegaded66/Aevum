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
 * FIX-VERIFIKATION (Kanban t_9e0527a6, M18.120): Der 1,5-h-Vorlauf-Bug
 * („Rückfahrt startet mit der Vorlaufzeit der Gym-Pause“) ist behoben.
 *
 * Szenario aus dem User-Report (t_4c902cf6):
 *   1. Hinfahrt (Autofahren, ACTIVITY_RECOGNITION_AUTO) endet im Gym
 *      („beendet nach ein paar Sekunden Stillstand“).
 *   2. 1,5 h Gym.
 *   3. Rückfahrt beginnt — die neue Auto-Session muss bei JETZT starten
 *      (0 ms Vorlauf), nicht bei der Gym-Ankunftszeit.
 *
 * Ursache (diagnostiziert in t_2edf98d8, verifiziert per Reproduktion):
 *   F-1  KEIN Stop-Pfad leerte den IN_VEHICLE-Cluster-Buffer (pending):
 *        DriveStopWorker, DriveWatchdogWorker und der AR-EXIT-Pfad riefen
 *        nur drainDriveProbes() — nie drainVehicleCluster(). Der
 *        Hinfahrt-Cluster überlebte die Gym-Pause unangetastet.
 *   F-2  Der M18.80-Guard (DriveWorkers.kt:220) hob einen rückwärts
 *        liegenden Cluster-Start OHNE Frischegrenze auf das Ende der
 *        letzten beendeten Auto-Session an — exakt die Gym-Ankunft (T0).
 *        leadTime = T1 − T0 = Gym-Dauer.
 *   F-3  toVehicleCluster nutzte Stillstands-Probes aus der Pause als
 *        Start-Anker (bis 15 min zurück).
 *   F-4  addSample-Backfill mit älterem Zeitstempel regredierte
 *        endMs/lastMs (durationMs negativ).
 *
 * Fix (in diesem Commit):
 *   F-1  Alle Stop-Pfade leeren jetzt auch drainVehicleCluster().
 *   F-2  Start-Anker über DriveDetectionEngine.resolveDriveStart():
 *        Cluster-Start älter als MAX_PROBE_AGE_MS (15 Min) ist STALE →
 *        Start bei `now`; die M18.80-Nicht-Überlappung greift nur noch
 *        für frische Cluster.
 *   F-3  toVehicleCluster filtert Stillstands-Probes (Bewegungs-Anker).
 *   F-4  addSample mit max-Semantik für endMs/lastMs.
 *
 * Die Tests unten spiegeln den PRODUKTIONS-Pfad 1:1 wider (echte
 * Production-Klassen: ActivityRecognitionBridge, DriveDetectionEngine,
 * LiveActivityManager); der Worker-Kern ist als resolveDriveStart
 * nachgebaut (DriveWorkers.kt ruft genau diese Funktion).
 */
class DriveLeadTimeReproductionTest {

    // ── Zeitachsen-Konstanten ────────────────────────────────────────
    /** Gym-Ankunft = Ende der Hinfahrt-Session (T0). */
    private val t0 = 1_000_000_000L
    /** Rückfahrt-Beginn = T0 + 1,5 h Gym-Pause. */
    private val t1 = t0 + 90L * 60 * 1000

    // ── Fakes (Muster: LiveActivityManagerOverlapTest) ──────────────

    private class FakeActivityRepository : ActivityRepository {
        val live = MutableStateFlow<ActivitySession?>(null)
        val inserted = mutableListOf<ActivitySession>()
        var lastFinishedAuto: ActivitySession? = null

        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override suspend fun getLastFinishedBySourceType(sourceType: String): ActivitySession? =
            lastFinishedAuto?.takeIf { it.sourceType == sourceType }
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
        override fun getById(id: String): Flow<ActivityType?> = flowOf(if (id == "driving") driving else null)
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(listOf(driving))
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

    /** Echte Bridge, Settings-Repo gestubbt (Muster: BridgeHealTest). */
    private fun bridge() = ActivityRecognitionBridge(
        object : AutomationSettingsRepository {
            override fun get() = flowOf(AutomationSettings(drivingDetectionEnabled = true))
            override suspend fun upsert(s: AutomationSettings) {}
        }
    )

    // ── Worker-Kern (Spiegel von DriveWorkers.kt 202–224) ────────────

    /**
     * Start-Anker-Logik des DriveStartWorker — 1:1 aus
     * app/.../automation/activityrecognition/DriveWorkers.kt:
     *   Zeile 202: val cluster = bridge.drainVehicleCluster()
     *   Zeile 211-218: startedAt = DriveDetectionEngine.resolveDriveStart(
     *       clusterStartMs = cluster?.startMs, nowMs = now,
     *       lastAutoSessionEndMs = live.lastAutoSessionEndMs())
     * Inklusive Gate (confirmedFresh ODER gpsOk) — hier über den echten
     * classify() auf den echten (gedrainten) Probes.
     */
    private fun driveStartCore(
        bridge: ActivityRecognitionBridge,
        lastAutoSessionEndMs: Long?,
        now: Long
    ): Long {
        val confirmed = bridge.isDriveConfirmed()
        val confirmedFresh = confirmed &&
            bridge.driveConfirmedAgeMs(now) < DriveDetectionEngine.MAX_PROBE_AGE_MS
        val gpsOk = DriveDetectionEngine.classify(
            bridge.currentDriveProbes(), now, emptyList(),
            DriveDetectionEngine.MotionContext.UNKNOWN
        ) is DriveDetectionEngine.Classification.Driving
        check(confirmedFresh || gpsOk) { "Start-Gate nicht bestanden (Produktionslogik)" }

        return DriveDetectionEngine.resolveDriveStart(
            clusterStartMs = bridge.drainVehicleCluster()?.startMs,
            nowMs = now,
            lastAutoSessionEndMs = lastAutoSessionEndMs
        )
    }

    /** Echte Rückfahrt-Probe-Serie (CONFIRM-Burst-Geometrie, 15-s-Takt):
     *  ≥ 8 m/s, Spread ≥ 30 s, Netto-Displacement ≥ 150 m, Kette ≥ 2. */
    private fun returnTripBurstProbes(): List<DriveDetectionEngine.DriveProbe> {
        val latStep = 8.5 * 15 / 111_320.0 // 127,5 m/Fix ≈ 8,5 m/s @ 15 s
        return (0 until 5).map { i ->
            DriveDetectionEngine.DriveProbe(
                timestampMs = t1 + i * 15_000L,
                speedMps = 8.5f,
                accuracyMeters = 12f,
                distanceFromLastM = null,
                latitude = 50.0 + i * latStep,
                longitude = 8.0
            )
        }
    }

    /** Hinfahrt-Samples in die Bridge (2 Samples, 1 Min auseinander —
     *  gleicher Cluster, startMs < T0). */
    private fun outboundDriveSamples(bridge: ActivityRecognitionBridge) {
        bridge.addSample(t0 - 10L * 60 * 1000, 75)
        bridge.addSample(t0 - 9L * 60 * 1000, 75)
    }

    /** Stop-Paket wie DriveStopWorker/Watchdog (DriveWorkers.kt:362-380
     *  bzw. 554-577) — M18.120: JETZT INKLUSIVE drainVehicleCluster(). */
    private fun stopPathOps(bridge: ActivityRecognitionBridge) {
        bridge.clearDriveActive()
        bridge.markDriveStopped(t0)
        bridge.drainDriveProbes()
        bridge.drainVehicleCluster()
        bridge.clearWalkingSignal()
    }

    // ════════════════════════════════════════════════════════════════
    // TEST 1: FIX VERIFIZIERT — voller Gym-Szenario-Pfad, 0 Vorlauf
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `Rueckfahrt startet bei JETZT — kein 1,5h-Vorlauf nach Gym-Pause`() = runTest {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        // Hinfahrt endet im Gym um T0; die Session ist beendet (endAt = T0).
        repo.lastFinishedAuto = ActivitySession(
            id = "outbound", title = "Autofahren", categoryId = null, activityTypeId = "driving",
            startAt = t0 - 20L * 60 * 1000, endAt = t0, timezoneId = "UTC",
            sourceType = "ACTIVITY_RECOGNITION_AUTO", sessionStatus = "FINISHED"
        )

        // 1) Hinfahrt: AR/Probe-Samples sammeln sich im Cluster-Buffer.
        outboundDriveSamples(bridge)
        assertThat(bridge.drainVehicleCluster()).isNotNull()

        // 2) Stop-Paket (Produktion, M18.120): leert JETZT den Vehicle-Cluster.
        stopPathOps(bridge)
        assertThat(bridge.drainVehicleCluster()).isNull()

        // 3) 1,5 h Gym: Standstill-Probes (DriveProbeWorker-Takt) — sie
        //    klassifizieren nicht als Fahrt und berühren den Cluster nicht.
        val gymProbes = (0 until 30).map { i ->
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + 3L * 60 * 1000 + i * 120_000L,
                speedMps = 0f, accuracyMeters = 15f,
                distanceFromLastM = 0.0, latitude = 50.0, longitude = 8.0
            )
        }
        assertThat(
            DriveDetectionEngine.classify(gymProbes, t1 - 60_000L, emptyList())
        ).isNotInstanceOf(DriveDetectionEngine.Classification.Driving::class.javaObjectType)

        // 4) Rückfahrt über den kontinuierlichen-AR/GPS-Pfad (kein ENTER —
        //    Google-Transition im Hintergrund unzuverlässig, M18.112).
        //    CONFIRM-Burst klassifiziert die echte Rückfahrt-Serie:
        val classifyNow = t1 + 5 * 15_000L + 15_000L
        assertThat(
            DriveDetectionEngine.classify(returnTripBurstProbes(), classifyNow, emptyList())
        ).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.javaObjectType)
        //    Bestätigung + Drain wie handleFix (DriveDetectionService.kt:1025-1026).
        bridge.markDriveConfirmed()
        bridge.drainDriveProbes()

        // 5) DriveStartWorker-Kern: Cluster-Anchor + Frischegrenze + Guard.
        val startedAt = driveStartCore(bridge, repo.lastFinishedAuto?.endAt, now = classifyNow)

        // 6) Session-Start über den echten Manager.
        val session = manager.start(
            activityTypeId = "driving", title = "Autofahren",
            sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = startedAt
        )

        // FIX: startAt = Rückfahrt-Beginn (T1), NICHT Gym-Ankunft (T0).
        assertThat(session.startAt).isEqualTo(classifyNow)
        assertThat(classifyNow - session.startAt).isEqualTo(0L)
        println("FIX VERIFIZIERT: Rueckfahrt-Start = $classifyNow (jetzt), Vorlauf = 0 ms")
    }

    // ════════════════════════════════════════════════════════════════
    // TEST 2: Defense-in-Depth — Alt-Cluster überlebt den Stop (alter
    // Pfad ohne Drain): die Frischegrenze allein verhindert den Vorlauf.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `ueberlebender Hinfahrt-Cluster startet dank Frischegrenze trotzdem bei JETZT`() = runTest {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        repo.lastFinishedAuto = ActivitySession(
            id = "outbound", title = "Autofahren", categoryId = null, activityTypeId = "driving",
            startAt = t0 - 20L * 60 * 1000, endAt = t0, timezoneId = "UTC",
            sourceType = "ACTIVITY_RECOGNITION_AUTO", sessionStatus = "FINISHED"
        )

        outboundDriveSamples(bridge)
        // ALTER Stop-Pfad (VOR M18.120): OHNE drainVehicleCluster() —
        // der Hinfahrt-Cluster überlebt die Pause (F-1-Lücke).
        bridge.clearDriveActive()
        bridge.markDriveStopped(t0)
        bridge.drainDriveProbes()
        bridge.clearWalkingSignal()
        assertThat(bridge.drainVehicleCluster()).isNotNull()

        // Rückfahrt-Erkennung wie in Test 1.
        val classifyNow = t1 + 5 * 15_000L + 15_000L
        bridge.markDriveConfirmed()
        bridge.drainDriveProbes()

        val startedAt = driveStartCore(bridge, repo.lastFinishedAuto?.endAt, now = classifyNow)

        // F-2-Frischegrenze: Cluster-Start (T0 − 10 Min) ist > 15 Min alt
        // → resolveDriveStart startet bei `now`, der Guard hebt NICHT an.
        assertThat(startedAt).isEqualTo(classifyNow)
        assertThat(manager.start(
            "driving", "Autofahren", sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = startedAt
        ).startAt).isEqualTo(classifyNow)
        println("FRISCHEGRENZE OK: Alt-Cluster (start=${t0 - 10L * 60 * 1000}) ignoriert, Start=$classifyNow")
    }
}
