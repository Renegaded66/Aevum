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
 * M18.134-REPRODUKTION (Kanban t_a860c07f): die gemeldete Radfahrt
 * minutiös nachgespielt — mit dem Produktions-Pfad, nicht mit einem
 * Modell.
 *
 * User-Report (Root t_099f1911): „Ich war gerade Fahrradfahren. dabei
 * habe ich natürlich auch so 25kmh drauf gehabt. und dann wurde
 * Autofahrt aufgezeichnet."
 *
 * Aufbau wie in der Produktion:
 *  • AR-Continuous-Samples alle 30 s (das ist der 30-s-Stream aus
 *    ActivityContinuousSamplesRequester) — hier ON_BICYCLE mit
 *    Confidence 75-90, wie Google es auf einem Fahrrad liefert.
 *  • GPS-Fixes alle 15 s (CONFIRM-Burst, HIGH_ACCURACY) mit realer
 *    Bewegung und leichtem Rauschen.
 *  • Entscheidungspfad = handleFix des DriveDetectionService
 *    (Fast-Start → classify → Rad-Prüfung), Start über den echten
 *    LiveActivityManager.
 *
 * Ergebnis-Erwartung: KEINE „Autofahren"-Session, dafür eine
 * „radfahren"-Session mit Rückdatierung.
 */
class BicycleRideReproductionTest {

    private val t0 = 3_000_000_000L

    private fun kmh(v: Double) = (v / 3.6).toFloat()

    private fun bridge(settings: AutomationSettings = AutomationSettings()) =
        ActivityRecognitionBridge(
            object : AutomationSettingsRepository {
                override fun get() = flowOf(settings)
                override suspend fun upsert(s: AutomationSettings) {}
            }
        )

    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 12f,
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

    private class RideResult(
        val driveSession: ActivitySession?,
        val bikeSession: ActivitySession?,
        val log: String
    )

    /**
     * Spielt die Radfahrt durch. [startStop] ist der Zeitpunkt, an dem
     * der User losfährt (der Report beschreibt eine Fahrt aus dem
     * Stand — Zuhause → Strecke).
     */
    private suspend fun runBicycleRide(
        minutes: Int,
        baseSpeedKmh: Double,
        sprintSpeedKmh: Double,
        sprintEveryS: Long = 60L,
        sprintLengthS: Long = 30L,
        bicycleConfidence: Int = 85
    ): RideResult {
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        val log = StringBuilder()

        var driveSession: ActivitySession? = null
        var bikeSession: ActivitySession? = null
        var positionM = 0.0

        for (t in 0L..minutes * 60_000L step 15_000L) {
            val now = t0 + t
            val inSprint = sprintEveryS > 0 && ((t / 1000L) / sprintEveryS) % 2L == 1L &&
                ((t / 1000L) % sprintEveryS) < sprintLengthS
            val speed = kmh(if (inSprint) sprintSpeedKmh else baseSpeedKmh)
            positionM += speed * 15.0
            bridge.addDriveProbe(
                probe(now, speed, 10f, speed * 15.0, positionM), refreshHeartbeat = false
            )

            // AR-Sample alle 30 s (Continuous-Receiver-Pfad).
            if (t % 30_000L == 0L) {
                bridge.onBicycleSample()
                bridge.onBicycleSampleWithConfidence(bicycleConfidence, now)
                bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
                bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
                log.append("t=${t / 1000}s AR=ON_BICYCLE conf=$bicycleConfidence -> ctx=${bridge.currentMotionContext()}\n")
            }

            if (driveSession != null || bikeSession != null) continue

            // ── Produktions-Spiegel handleFix ──────────────────────
            if (bridge.isWithinDriveRestartCooldown(now)) continue

            if (DriveDetectionEngine.shouldFastStart(
                    bridge.currentDriveProbes(), bridge.vehicleEvidence(), now,
                    bridge.currentGeofenceContext(), bridge.currentCadenceHz(),
                    bridge.currentCadenceValidFraction()
                )
            ) {
                log.append("t=${t / 1000}s FAST-START -> Autofahrt\n")
                driveSession = manager.start(
                    activityTypeId = "driving", title = "Autofahren",
                    sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = now
                )
                continue
            }

            val classification = DriveDetectionEngine.classify(
                bridge.currentDriveProbes(), now, bridge.currentGeofenceContext(),
                bridge.currentMotionContext(), bridge.currentCadenceHz(),
                bridge.currentCadenceValidFraction()
            )
            if (classification is DriveDetectionEngine.Classification.Driving) {
                log.append("t=${t / 1000}s classify=Driving -> Autofahrt\n")
                driveSession = manager.start(
                    activityTypeId = "driving", title = "Autofahren",
                    sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = now
                )
                continue
            }

            if (classification is DriveDetectionEngine.Classification.NotDriving &&
                bridge.isBicycleEnabled() &&
                DriveDetectionEngine.isReliableBicycleSignal(bridge.bicycleEvidence(), now)
            ) {
                val ride = DriveDetectionEngine.detectBikeRide(
                    bridge.currentDriveProbes(), now, bridge.currentGeofenceContext()
                )
                if (ride != null) {
                    log.append(
                        "t=${t / 1000}s classify=NotDriving + Radfahrt belegt " +
                            "(avg=${"%.1f".format(ride.avgSpeedMps)} m/s, ${ride.sampleCount} Probes) " +
                            "-> Rad-Session (start=${(ride.startMs - t0) / 1000}s)\n"
                    )
                    bikeSession = manager.start(
                        activityTypeId = "radfahren", title = "Radfahren",
                        sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = ride.startMs
                    )
                }
            }
        }
        return RideResult(driveSession, bikeSession, log.toString())
    }

    // ──────────────────────────────────────────────────────────────
    // DIE GEMELDETE FAHRT
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `User-Fall - 20 Min Radfahren mit 25 kmh Schnitt ergibt eine Rad-Session`() = runTest {
        // 22 km/h Grundtempo + alle 60 s ein 30-s-Antritt auf 32 km/h
        // → Schnitt ~27 km/h, Spitzen über 28,8 km/h. Genau das Muster,
        // das vorher als „Autofahrt" endete (gemessen: Driving 300/300).
        val result = runBicycleRide(
            minutes = 20, baseSpeedKmh = 22.0, sprintSpeedKmh = 32.0
        )
        println("=== USER-FALL (22 km/h + Antritte 32 km/h, 20 Min) ===")
        print(result.log)

        assertThat(result.driveSession).isNull()
        assertThat(result.bikeSession).isNotNull()
        assertThat(result.bikeSession!!.activityTypeId).isEqualTo("radfahren")
        assertThat(result.bikeSession!!.startAt).isLessThan(t0 + 20 * 60_000L)
        // Die Rückdatierung greift: Start liegt beim ersten bewegten Fix.
        assertThat(result.bikeSession!!.startAt).isEqualTo(t0)
    }

    @Test
    fun `Rad 29 kmh konstant - Rad-Session statt Autofahrt`() = runTest {
        // Der gemessene Extremfall: 29 km/h = 8,06 m/s > 8 m/s-Schwelle
        // → vorher Driving 500/500.
        val result = runBicycleRide(
            minutes = 10, baseSpeedKmh = 29.0, sprintSpeedKmh = 29.0, sprintEveryS = 0
        )
        println("=== 29 km/h konstant, 10 Min ===")
        print(result.log)
        assertThat(result.driveSession).isNull()
        assertThat(result.bikeSession).isNotNull()
    }

    @Test
    fun `Pedelec 27 kmh wellig - Rad-Session statt Autofahrt`() = runTest {
        // Pedelec-Profil: 25-31 km/h wellig (Schnitt 27,4 — gemessen
        // vorher Driving 176/300 = 58 %).
        val result = runBicycleRide(
            minutes = 15, baseSpeedKmh = 25.0, sprintSpeedKmh = 31.0,
            sprintEveryS = 45, sprintLengthS = 20
        )
        assertThat(result.driveSession).isNull()
        assertThat(result.bikeSession).isNotNull()
    }

    @Test
    fun `Radfahrt OHNE AR-Permission bleibt beim heutigen Verhalten`() = runTest {
        // Ohne AR-Signal gibt es kein ON_BICYCLE → kein Rad-Kontext und
        // keine Rad-Evidence. Das Verhalten ist UNVERÄNDERT (der Fix
        // braucht das AR-Signal; ohne Permission greift weiter die
        // 8-m/s-Schwelle). Ehrlich dokumentiert: das ist der bewusste
        // Trade-off (kein neues False-Negative-Risiko).
        val bridge = bridge()
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        var driveSession: ActivitySession? = null
        var positionM = 0.0
        for (t in 0L..5 * 60_000L step 15_000L) {
            val now = t0 + t
            val speed = kmh(32.0)
            positionM += speed * 15.0
            bridge.addDriveProbe(probe(now, speed, 10f, speed * 15.0, positionM), false)
            if (driveSession == null && DriveDetectionEngine.classify(
                    bridge.currentDriveProbes(), now, emptyList(),
                    bridge.currentMotionContext(), null, 0f
                ) is DriveDetectionEngine.Classification.Driving
            ) {
                driveSession = manager.start(
                    activityTypeId = "driving", title = "Autofahren",
                    sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = now
                )
            }
        }
        // 32 km/h ohne AR-Signal → weiterhin als Fahrt erkannt (8 m/s).
        // Bewusst so: der Fix darf nur mit belastbarem AR-Signal wirken.
        assertThat(bridge.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.UNKNOWN)
        assertThat(bridge.bicycleEvidence()).isNull()
        assertThat(driveSession).isNotNull()
    }

    // ── Fakes ─────────────────────────────────────────────────────

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
        val bicycle = ActivityType(
            id = "radfahren", name = "Radfahren", defaultCategoryId = "sport", isSystem = true,
            propertiesJson = null, positivityScore = 85, icon = "🚴", color = 0L
        )
        val driving = ActivityType(
            id = "driving", name = "Autofahren", defaultCategoryId = null, isSystem = false,
            propertiesJson = null, positivityScore = 50, icon = "•", color = 0L
        )
        val other = ActivityType(
            id = "other", name = "Sonstiges", defaultCategoryId = null, isSystem = true,
            propertiesJson = null, positivityScore = 50, icon = "•", color = 0L
        )
        override fun getById(id: String): Flow<ActivityType?> =
            flowOf(when (id) { "radfahren" -> bicycle; "driving" -> driving; "other" -> other; else -> null })
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(listOf(bicycle, driving, other))
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
