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
 * M18.134-INTEGRATION (Kanban t_a860c07f): Radfahrt gegen die ECHTEN
 * Produktionsklassen — Bridge (AR-Sample-Verarbeitung), Engine (Gates),
 * LiveActivityManager (Session) — genau der Pfad, den der
 * BicycleStartWorker in der Produktion geht.
 *
 * Der User-Fall (Root t_099f1911): 20 Minuten Radfahren mit ~25 km/h
 * Schnitt. Vorher wurde daraus eine „Autofahren"-Session. Jetzt:
 *  1. ON_BICYCLE-Samples (30-s-Takt, wie der Continuous-Receiver sie
 *     liefert) setzen den Kontext → die 12-m/s-Gates blockieren den
 *     Auto-Start (classify = NotDriving, shouldFastStart = false).
 *  2. Dieselbe Probe-Serie erfüllt detectBikeRide → es entsteht eine
 *     `radfahren`-Session mit Rückdatierung, NICHT „nichts".
 *
 * Zusätzlich geprüft: ON_BICYCLE hebt die Fahrzeug-Evidence auf
 * (M18.128-V1), die Rad-Evidence verfällt/verbraucht sich korrekt, und
 * ein Motorrad-Muster (ON_BICYCLE + 70 km/h) startet weiterhin eine
 * Fahrt — der M18.130-Bestandsschutz auf Bridge-Ebene.
 */
class BicycleRideIntegrationTest {

    private val t0 = 2_000_000_000L

    private fun bridge(settings: AutomationSettings = AutomationSettings()) =
        ActivityRecognitionBridge(
            object : AutomationSettingsRepository {
                override fun get() = flowOf(settings)
                override suspend fun upsert(s: AutomationSettings) {}
            }
        )

    private fun kmh(v: Double) = (v / 3.6).toFloat()

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

    /** AR-Sample wie der Continuous-Receiver: widerlegt die Fahrzeug-
     *  Evidence, registriert die Rad-Evidence, setzt den Kontext. */
    private fun feedBicycleSample(
        bridge: ActivityRecognitionBridge,
        confidence: Int = 85,
        nowMs: Long
    ) {
        bridge.onBicycleSample()
        bridge.onBicycleSampleWithConfidence(confidence, nowMs)
        bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
    }

    /** Spiegel des Produktions-Starts (BicycleStartWorker): dieselben
     *  drei Gates in derselben Reihenfolge. */
    private fun bicycleStartDecision(
        bridge: ActivityRecognitionBridge,
        nowMs: Long
    ): Boolean {
        if (!bridge.isBicycleEnabled()) return false
        if (bridge.isDriveActive()) return false
        if (!DriveDetectionEngine.isReliableBicycleSignal(bridge.bicycleEvidence(), nowMs)) return false
        return DriveDetectionEngine.detectBikeRide(
            bridge.currentDriveProbes(), nowMs, bridge.currentGeofenceContext()
        ) != null
    }

    // ──────────────────────────────────────────────────────────────
    // 1) DER USER-FALL: 25 km/h Radfahrt
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Radfahrt 25 kmh - kein Auto-Start, aber Radfahrt erkannt`() = runTest {
        val b = bridge()

        // 20 Minuten Radfahrt: 25 km/h Grundtempo, alle 60 s ein
        // 30-s-Antritt auf 32 km/h (der reale Fall: „so 25 km/h drauf").
        var positionM = 0.0
        var session: ActivitySession? = null
        var autoStartAttempts = 0

        for (t in 0L..20 * 60_000L step 15_000L) {
            val now = t0 + t
            val speed = if ((t / 30_000L) % 2L == 1L) kmh(32.0) else kmh(22.0)
            positionM += speed * 15.0
            b.addDriveProbe(
                probe(now, speed, 10f, speed * 15.0, positionM),
                refreshHeartbeat = false
            )
            if (t % 30_000L == 0L) feedBicycleSample(b, nowMs = now)

            // Auto-Pfad (classify): darf NIE starten.
            if (DriveDetectionEngine.classify(
                    b.currentDriveProbes(), now, b.currentGeofenceContext(),
                    b.currentMotionContext(), b.currentCadenceHz(),
                    b.currentCadenceValidFraction()
                ) is DriveDetectionEngine.Classification.Driving
            ) {
                autoStartAttempts++
            }
            // Rad-Pfad: startet die Session.
            if (session == null && bicycleStartDecision(b, now)) {
                session = b.let {
                    val ride = DriveDetectionEngine.detectBikeRide(
                        it.currentDriveProbes(), now, it.currentGeofenceContext()
                    )!!
                    LiveActivityManager(
                        FakeActivityRepository(), FakeTypeRepository(), FakeTriggerRepository()
                    ).start(
                        activityTypeId = "radfahren",
                        title = "Radfahren",
                        sourceType = "ACTIVITY_RECOGNITION_AUTO",
                        startedAt = ride.startMs
                    )
                }
            }
        }

        assertThat(autoStartAttempts).isEqualTo(0)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(session).isNotNull()
        assertThat(session!!.activityTypeId).isEqualTo("radfahren")
        assertThat(session!!.sourceType).isEqualTo("ACTIVITY_RECOGNITION_AUTO")
        // Rückdatierung: die Session beginnt VOR dem Erkennungszeitpunkt.
        assertThat(session!!.startAt).isLessThan(t0 + 20 * 60_000L)
    }

    @Test
    fun `Radfahrt 29 kmh konstant - kein Auto-Start auch ohne Antritte`() = runTest {
        val b = bridge()
        var positionM = 0.0
        var autoStart = false
        for (t in 0L..3 * 60_000L step 15_000L) {
            val now = t0 + t
            positionM += kmh(29.0) * 15.0
            b.addDriveProbe(probe(now, kmh(29.0), 10f, kmh(29.0) * 15.0, positionM), false)
            if (t % 30_000L == 0L) feedBicycleSample(b, nowMs = now)
            if (DriveDetectionEngine.classify(
                    b.currentDriveProbes(), now, emptyList(),
                    b.currentMotionContext(), null, 0f
                ) is DriveDetectionEngine.Classification.Driving
            ) autoStart = true
        }
        assertThat(autoStart).isFalse()
        assertThat(bicycleStartDecision(b, t0 + 3 * 60_000L)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // 2) BESTANDSSCHUTZ: Motorrad in ON_BICYCLE startet weiter
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Motorrad 70 kmh als ON_BICYCLE - Auto-Start funktioniert weiterhin`() = runTest {
        val b = bridge()
        var positionM = 0.0
        var driving = false
        for (t in 0L..3 * 60_000L step 15_000L) {
            val now = t0 + t
            positionM += kmh(70.0) * 15.0
            b.addDriveProbe(probe(now, kmh(70.0), 10f, kmh(70.0) * 15.0, positionM), false)
            if (t % 30_000L == 0L) feedBicycleSample(b, nowMs = now)
            if (DriveDetectionEngine.classify(
                    b.currentDriveProbes(), now, emptyList(),
                    b.currentMotionContext(), null, 0f
                ) is DriveDetectionEngine.Classification.Driving
            ) driving = true
        }
        assertThat(driving).isTrue()
        // Und es entsteht KEINE Rad-Session (der Motorrad-Pfad gewinnt).
        assertThat(bicycleStartDecision(b, t0 + 3 * 60_000L)).isFalse()
    }

    @Test
    fun `Auto mit IN_VEHICLE startet normal - ON_BICYCLE-Signal widerlegt die Evidence`() = runTest {
        val b = bridge()
        // IN_VEHICLE-Sample setzt die Fahrzeug-Evidence (Fast-Start-Pfad).
        b.onVehicleSample(confidence = 90, nowMs = t0 - 10_000L)
        assertThat(b.vehicleEvidence()).isNotNull()
        // Ein ON_BICYCLE-Sample widerlegt sie (ein Fahrrad ist kein Auto).
        feedBicycleSample(b, nowMs = t0)
        assertThat(b.vehicleEvidence()).isNull()
    }

    // ──────────────────────────────────────────────────────────────
    // 3) RAD-EVIDENCE-LEBENSZYKLUS
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Rad-Evidence entsteht aus dem ON_BICYCLE-Sample und verfaellt`() {
        val b = bridge()
        assertThat(b.bicycleEvidence()).isNull()
        feedBicycleSample(b, confidence = 88, nowMs = t0)
        val evidence = b.bicycleEvidence()
        assertThat(evidence).isNotNull()
        assertThat(evidence!!.confidence).isEqualTo(88)
        // Frisch → belastbar; nach dem Frische-Fenster nicht mehr.
        assertThat(DriveDetectionEngine.isReliableBicycleSignal(evidence, t0 + 30_000L)).isTrue()
        assertThat(
            DriveDetectionEngine.isReliableBicycleSignal(
                evidence, t0 + DriveDetectionEngine.BICYCLE_EVIDENCE_MAX_AGE_MS + 1
            )
        ).isFalse()
    }

    @Test
    fun `Rad-Evidence wird an Session-Grenzen verworfen`() {
        val b = bridge()
        feedBicycleSample(b, confidence = 88, nowMs = t0)
        assertThat(b.bicycleEvidence()).isNotNull()
        b.resetBicycleEvidence()
        assertThat(b.bicycleEvidence()).isNull()
        assertThat(DriveDetectionEngine.isReliableBicycleSignal(b.bicycleEvidence(), t0)).isFalse()
    }

    @Test
    fun `Rad-Setting aus - kein Rad-Start, aber auch KEINE Autofahrt bei 25 kmh`() {
        // Wichtige Korrektheits-Eigenschaft: Der Toggle steuert, OB eine
        // Rad-Session entsteht — nicht, OB 25 km/h Radfahren als
        // Autofahrt fehlklassifiziert wird. Mit ausgeschalteter
        // Rad-Erkennung gilt für das AR-Signal weiterhin das
        // ON_BICYCLE-Gate (12 m/s), es entsteht nur keine Session.
        val b = bridge(AutomationSettings(bicycleDetectionEnabled = false))
        var positionM = 0.0
        var autoStart = false
        for (t in 0L..3 * 60_000L step 15_000L) {
            val now = t0 + t
            positionM += kmh(25.0) * 15.0
            b.addDriveProbe(probe(now, kmh(25.0), 10f, kmh(25.0) * 15.0, positionM), false)
            if (t % 30_000L == 0L) feedBicycleSample(b, nowMs = now)
            if (DriveDetectionEngine.classify(
                    b.currentDriveProbes(), now, emptyList(),
                    b.currentMotionContext(), null, 0f
                ) is DriveDetectionEngine.Classification.Driving
            ) autoStart = true
        }
        assertThat(b.isBicycleEnabled()).isFalse()
        // Der Kontext wirkt trotzdem → keine Fehlklassifikation.
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(autoStart).isFalse()
        // Aber: keine Rad-Session (das ist die Setting-Wirkung).
        assertThat(bicycleStartDecision(b, t0 + 3 * 60_000L)).isFalse()
    }

    // ──────────────────────────────────────────────────────────────
    // 4) KONTEXT-HYSTERESE: ON_BICYCLE braucht zwei Samples wie ON_FOOT
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `ein einzelnes ON_BICYCLE-Sample flippt den Kontext nicht`() {
        val b = bridge()
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.UNKNOWN)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
    }

    @Test
    fun `Rueckkehr zu UNKNOWN nach dem Rad-EXIT`() {
        val b = bridge()
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_BICYCLE)
        // Transition-EXIT-Pfad: zwei UNKNOWN-Samples (Hysterese).
        b.updateMotionContext(DriveDetectionEngine.MotionContext.UNKNOWN)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.UNKNOWN)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.UNKNOWN)
    }

    // ── Fakes (Muster: RideRecordingReproductionTest) ────────────────

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
