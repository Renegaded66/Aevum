package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
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
import org.junit.Test
import java.io.File

/**
 * M18.134-ABSCHLUSS (Kanban t_88146697): die im Handoff benannten
 * Lücken der Rad-Session — Stop-Pfad, Walking-Interaktion, Veto-Pfad.
 *
 * 1. STOP-PFAD DER RAD-SESSION ([DriveWatchdogWorker]/[DriveStopWorker]):
 *    `isAutoTrackedSession` ist eine PRIVATE Top-Level-Funktion in
 *    DriveWorkers.kt — ein Verhaltenstest kann sie nicht aufrufen,
 *    bisher sicherte sie nur ein Quelltext-Scan
 *    ([BicycleWiringRegressionTest]). Diese Suite ruft sie per
 *    Reflection auf den ECHTEN Produktionscode auf und prüft die ganze
 *    Match-Matrix (driving/radfahren × sourceType × Status), inklusive
 *    der Watchdog-Zählung (genau zwei Aufrufstellen).
 *
 * 2. WALK-/STEP-WALK-STOP-INTERAKTION bei laufender Rad-Session:
 *    gemessen — die Detektoren selbst schlagen an
 *    ([ActivityRecognitionBridge.onWalkStopSample]/[onStepWalkStopStep]
 *    liefern true), die TRIGGER-Gates in Receiver und Service hängen
 *    aber an `isDriveActive()`, das für eine Rad-Session false ist.
 *    Der Stop erreicht die Rad-Session deshalb nur über den
 *    Watchdog-Match (Punkt 1). Die Gates werden hier explizit als
 *    gemessene Fakten festgehalten, damit die Follow-up-Karte den
 *    Unterschied zwischen „Detektor" und „Trigger" nicht neu messen muss.
 *
 * 3. VETO-PFAD DER WALKING-PHASE gegen eine Rad-Session: ein Radfahrer
 *    legt in 5 Minuten ~2 km zurück. Alle drei Veto-Stufen
 *    ([WalkingDetectionEngine]) greifen mit ECHTEN Rad-Zahlen; der
 *    Gate-Ausdruck im Service (hasLiveBikeSession) verhindert die Phase
 *    zusätzlich. Beides wird hier mit den gemessenen Werten geprüft —
 *    die Struktur-Prüfung des Gate-Ausdrucks bleibt in
 *    [BicycleWiringRegressionTest].
 *
 * Keine Duplikate zu den bestehenden Suiten: [DriveBicycleGateTest] und
 * [BicycleWiringRegressionTest] prüfen Engine-Gates bzw. Quelltext;
 * hier läuft der echte Produktionscode per Reflection bzw. mit echten
 * Zahlen gegen die Produktionsschwellen.
 */
class BicycleStopAndWalkingInteractionTest {

    private val t0 = 1_000_000_000L
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
        positionM: Double = 0.0,
        accuracy: Float = 10f
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = speedMps?.times(15.0),
        latitude = 50.000 + positionM / 111_320.0,
        longitude = 8.000
    )

    private fun session(
        typeId: String,
        source: String = "ACTIVITY_RECOGNITION_AUTO",
        status: String = "RUNNING"
    ) = ActivitySession(
        id = "s-1", title = "t", activityTypeId = typeId, startAt = t0,
        endAt = null, sourceType = source, sessionStatus = status
    )

    // ── Reflection-Zugriff auf die privaten Produktions-Prädikate ────
    // Die Funktionen sind top-level `private` in DriveWorkers.kt; Kotlin
    // kompiliert sie als statische Methoden von DriveWorkersKt. Über
    // Reflection wird der ECHTE Code geprüft statt einer Kopie.

    private val driveWorkersKt = Class.forName(
        "com.d_drostes_apps.aevum.automation.activityrecognition.DriveWorkersKt"
    )

    private fun isAutoTrackedSession(s: ActivitySession?): Boolean {
        val m = driveWorkersKt.getDeclaredMethod("isAutoTrackedSession", ActivitySession::class.java)
        m.isAccessible = true
        return m.invoke(null, s) as Boolean
    }

    private fun hasFreshVehiclePace(
        probes: List<DriveDetectionEngine.DriveProbe>,
        nowMs: Long
    ): Boolean {
        val m = driveWorkersKt.getDeclaredMethod(
            "hasFreshVehiclePace", List::class.java, Long::class.javaPrimitiveType
        )
        m.isAccessible = true
        return m.invoke(null, probes, nowMs) as Boolean
    }

    // ──────────────────────────────────────────────────────────────
    // 1) STOP-PFAD: die Rad-Session wird vom Watchdog/Stop erfasst
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Watchdog und Sofort-Stop matchen die Rad-Session und die Autofahrt - aber nichts sonst`() {
        // Die automatisch aufgezeichneten Session-Typen:
        assertThat(isAutoTrackedSession(session("driving"))).isTrue()
        assertThat(isAutoTrackedSession(session("radfahren"))).isTrue()

        // Alles andere gehört NICHT in die Stop-Pfade: sonst würde ein
        // laufender Spaziergang oder eine manuelle Aktivität beendet.
        assertThat(isAutoTrackedSession(session("spazieren"))).isFalse()
        assertThat(isAutoTrackedSession(session("joggen"))).isFalse()
        assertThat(isAutoTrackedSession(session("driving", source = "MANUAL"))).isFalse()
        assertThat(isAutoTrackedSession(session("radfahren", source = "MANUAL"))).isFalse()
        assertThat(isAutoTrackedSession(session("radfahren", source = "GEOFENCE_AUTO"))).isFalse()
        assertThat(isAutoTrackedSession(session("radfahren", source = "WALKING_AUTO"))).isFalse()
        assertThat(isAutoTrackedSession(null)).isFalse()
    }

    @Test
    fun `beendete oder pausierte Sessions werden nicht mehr gestoppt`() {
        // isLive umfasst RUNNING und PAUSED; FINISHED darf nie mehr
        // Ziel eines Stop-Pfads sein (sonst würde eine beendete Session
        // erneut gestoppt/getriggert).
        assertThat(isAutoTrackedSession(session("radfahren", status = "RUNNING"))).isTrue()
        assertThat(isAutoTrackedSession(session("radfahren", status = "PAUSED"))).isTrue()
        assertThat(isAutoTrackedSession(session("radfahren", status = "FINISHED"))).isFalse()
        assertThat(isAutoTrackedSession(session("driving", status = "FINISHED"))).isFalse()
    }

    @Test
    fun `Watchdog und Sofort-Stop rufen den Match genau einmal auf - keine dritte Aufrufstelle`() {
        // Strukturkontrolle als Ergänzung zum Verhaltenstest: Der Helfer
        // muss in GENAU den zwei Stop-Workern hängen. Eine dritte
        // Aufrufstelle würde einen weiteren Pfad stillschweigend an die
        // Rad-Session koppeln (z. B. den Start-Pfad).
        val src = File(driveWorkersPath()).readText()
        val count = Regex("isAutoTrackedSession\\(session\\)").findAll(src).count()
        assertWithMessage("erwartet: Watchdog + DriveStopWorker, gefunden: $count")
            .that(count).isEqualTo(2)
        // Die Rad-Session bekommt ihren eigenen End-Marker und KEINEN
        // Geofence-Re-Enter (Fahrt-Feature).
        assertThat(src).contains("TRIGGER_BICYCLE_ENDED")
        assertThat(src).contains("if (!isBikeRide) {")
    }

    @Test
    fun `Fahrzeug-Tempo-Veto des Watchdogs zaehlt eine Radfahrt nicht als Fahrzeug`() {
        // Der Watchdog verlängert eine Fahrt, wenn die Bewegung
        // Fahrzeug-Charakter hat (hasFreshVehiclePace, ≥ 8 m/s). Eine
        // Radfahrt bei 25 km/h (6,9 m/s) ist KEIN Fahrzeug-Tempo —
        // sonst hielte sich eine Rad-Session über den GPS-Check selbst
        // am Leben statt nach 5 Minuten ohne Signal zu enden.
        val ride = listOf(
            probe(t0, kmh(25.0), 100.0),
            probe(t0 + 15_000L, kmh(25.0), 200.0)
        )
        assertThat(hasFreshVehiclePace(ride, t0 + 15_000L)).isFalse()

        // Auch ohne Speed-Feld (Distanz-Ableitung): 25 km/h bleiben unter
        // der 8-m/s-Schwelle.
        val derived = listOf(
            DriveDetectionEngine.DriveProbe(t0, null, 10f, kmh(25.0) * 15.0, 50.0, 8.0),
            DriveDetectionEngine.DriveProbe(
                t0 + 15_000L, null, 10f, kmh(25.0) * 15.0, 50.0015, 8.0
            )
        )
        assertThat(hasFreshVehiclePace(derived, t0 + 15_000L)).isFalse()

        // Gegenprobe Fahrzeug (50 km/h) — der Pfad darf nicht generell tot sein.
        val car = listOf(
            probe(t0, kmh(50.0), 100.0),
            probe(t0 + 15_000L, kmh(50.0), 300.0)
        )
        assertThat(hasFreshVehiclePace(car, t0 + 15_000L)).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // 2) WALK-/STEP-WALK-STOP-INTERAKTION mit der Rad-Session
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `AR-Walk-Stop feuert beim Abstellen des Rades - auch ohne Fahrzeug-Tempo`() {
        // Ende der Radfahrt: der User steigt ab und geht. Die Probes
        // zeigen Rad-Tempo (25 km/h = 6,9 m/s) — das ist KEIN
        // Fahrzeug-Tempo und widerlegt die Geh-Hypothese deshalb nicht.
        val b = bridge()
        val rideProbes = listOf(
            probe(t0, kmh(25.0), 100.0),
            probe(t0 + 15_000L, kmh(25.0), 200.0)
        )
        rideProbes.forEach { b.addDriveProbe(it, false) }

        // Zwei WALKING-Samples à 30 s mit Confidence ≥ 60: erst nach der
        // 75-s-Gnadenfrist feuert der Detektor (M18.127-Semantik).
        assertThat(b.onWalkStopSample(WalkStopDetector.TYPE_WALKING, 80, t0)).isFalse()
        assertThat(b.onWalkStopSample(WalkStopDetector.TYPE_WALKING, 80, t0 + 30_000L)).isFalse()
        assertThat(b.onWalkStopSample(WalkStopDetector.TYPE_WALKING, 80, t0 + 80_000L)).isTrue()
    }

    @Test
    fun `AR-Walk-Stop wird durch Fahrzeug-Tempo widerlegt - Motorrad-Bestandsschutz`() {
        // Gegenprobe (die M18.84/M18.130-Lehre): Google meldet auf
        // Zweirädern WALKING. Sobald ein frischer Probe Fahrzeug-Tempo
        // zeigt (Motorrad 70 km/h), wird die Geh-Evidenz verworfen —
        // die Fahrt lebt. Ohne dieses Veto würde eine Motorrad-Session
        // nach 75 s beendet, obwohl sie fährt.
        val b = bridge()
        val moto = listOf(
            probe(t0, kmh(70.0), 500.0),
            probe(t0 + 15_000L, kmh(70.0), 800.0)
        )
        moto.forEach { b.addDriveProbe(it, false) }
        assertThat(b.onWalkStopSample(WalkStopDetector.TYPE_WALKING, 80, t0)).isFalse()
        assertThat(b.onWalkStopSample(WalkStopDetector.TYPE_WALKING, 80, t0 + 80_000L)).isFalse()
    }

    @Test
    fun `Step-Walk-Stop feuert bei Schritten ohne Fahrzeug-Herzschlag - Veto schuetzt die Fahrt`() {
        // Hardware-Schritte sind das Google-unabhängige Ausstiegs-Signal
        // (M18.133). Ohne frischen Herzschlag und ohne Fahrzeug-Tempo
        // (Radfahrt steht bereits) feuert der Detektor nach 10 Schritten.
        val b = bridge()
        b.addDriveProbe(probe(t0, kmh(25.0), 100.0), false)
        b.addDriveProbe(probe(t0 + 15_000L, kmh(25.0), 200.0), false)
        var stopped = false
        for (i in 0 until 10) {
            if (b.onStepWalkStopStep(t0 + i * 1400L)) stopped = true
        }
        assertThat(stopped).isTrue()
        assertThat(b.hasStepWalkingEvidence(t0 + 9 * 1400L)).isTrue()

        // Mit frischem Fahrt-Herzschlag sind die Schritte Vibration
        // (M18.126) — es darf kein Falsch-Stop entstehen.
        val b2 = bridge()
        b2.addDriveProbe(probe(t0, kmh(25.0), 100.0), false)
        b2.refreshDriveHeartbeat(t0 + 15_000L)
        var stopped2 = false
        for (i in 0 until 10) {
            if (b2.onStepWalkStopStep(t0 + 15_000L + i * 1400L)) stopped2 = true
        }
        assertThat(stopped2).isFalse()
    }

    @Test
    fun `GEMESSEN - das Walk-Stop-Trigger-Gate haengt an isDriveActive - Rad-Session ist davon nicht erfasst`() = runTest {
        // EHRLICHE ABGRENZUNG (Follow-up-Karte): Die Detektoren feuern
        // (Tests oben), aber die Trigger-Gates im Receiver
        // (ActivityContinuousSamples.kt, `if (bridge.isDriveActive())`)
        // und im Service (DriveDetectionService.kt, onStepForWalkStop,
        // `if (!bridge.isDriveActive()) return`) prüfen isDriveActive.
        // Für eine automatisch gestartete RAD-Session ist isDriveActive
        // false (es wird nur von markDriveConfirmed gesetzt — dem
        // Fahrzeug-Start-Pfad). Der Abstell-/Ausstiegs-Trigger erreicht
        // die Rad-Session daher nicht; sie endet über den Watchdog-Match
        // (Punkt 1) — spätestens 5 Minuten nach dem letzten Signal.
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        val b = bridge()

        val bike = manager.start(
            activityTypeId = "radfahren", title = "Radfahren",
            sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = t0
        )
        awaitLive(manager, bike.id)
        assertThat(manager.liveSession.value?.activityTypeId).isEqualTo("radfahren")
        assertThat(manager.liveSession.value?.isLive).isTrue()

        // Der Detektor ist bereit (Evidenz da) …
        var detectorFired = false
        var t = 0L
        while (t <= 90_000L) {
            if (b.onWalkStopSample(WalkStopDetector.TYPE_WALKING, 80, t0 + t)) detectorFired = true
            t += 15_000L
        }
        assertThat(detectorFired).isTrue()

        // … aber das Trigger-Gate, das der Receiver dafür prüft, ist für
        // die Rad-Session geschlossen.
        assertThat(b.isDriveActive()).isFalse()
    }

    @Test
    fun `eine laufende Rad-Session verhindert die Spazieren-Session - Session-Gate und Vetos`() = runTest {
        // Der Veto-Pfad (Handoff-Punkt 3): Sonst entstünde eine
        // „Spazieren"-Session ÜBER der Radfahrt.
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        val bike = manager.start(
            activityTypeId = "radfahren", title = "Radfahren",
            sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = t0
        )
        awaitLive(manager, bike.id)

        // Stufe 1 — Session-Gate der Walking-Engine: „nichts anderes
        // zeichnet auf" ist bei laufender Rad-Session NICHT erfüllt.
        assertThat(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = t0 - 6 * 60_000L, now = t0,
                walkingEnabled = true,
                anythingRecording = manager.liveSession.value?.isLive == true,
                lastDriveEndMs = null
            )
        ).isFalse()
    }

    @Test
    fun `die Walking-Phase wird von echten Rad-Zahlen verworfen - drei Veto-Stufen`() {
        // Stufe 2 — Fahrzeug-Tempo der Einzel-Fixe: 25 km/h (6,9 m/s)
        // liegt UNTER der 8-m/s-Schwelle → dieses Veto greift bei einer
        // 25-km/h-Radfahrt NICHT (ehrlich dokumentiert, genau deshalb ist
        // das Session-Gate oben nötig). Ab 28,8 km/h (8 m/s) greift es —
        // dann ist der Fix aber auch als Fahrzeug-Tempo klassifiziert.
        assertThat(WalkingDetectionEngine.isVehicleSpeed(kmh(25.0))).isFalse()
        assertThat(WalkingDetectionEngine.isVehicleSpeed(kmh(28.0))).isFalse()
        assertThat(WalkingDetectionEngine.isVehicleSpeed(kmh(30.0))).isTrue()

        // Stufe 3 — Displacement-Veto über dist/dt (≥ 5 m/s): schon
        // 45 s Radfahrt bei 25 km/h sind ~313 m → Fahrzeug-Niveau
        // gegenüber dem Vor-Fix. Die Phase wird verworfen, sobald sie
        // einen Fix später als 30 s sieht.
        val displacement45s = kmh(25.0) * 45.0
        assertWithMessage("Veto-Probe: ${displacement45s.toInt()} m in 45 s")
            .that(WalkingDetectionEngine.isVehicleDisplacement(displacement45s, 45_000L)).isTrue()

        // Stufe 4 — MAX-Gate: die Phase selbst (5 Minuten Radfahrt) hätte
        // einen Netto-Schnitt von ~6,9 m/s — über WALKING_MAX_AVG_SPEED_MPS
        // (5,0 m/s) und damit keine Wanderung.
        val net5min = kmh(25.0) * 300.0
        assertWithMessage("Phase-Schnitt: ${(net5min / 300.0)} m/s über 5 Min")
            .that(WalkingDetectionEngine.exceedsWalkingSpeed(net5min, 300_000L)).isTrue()
    }

    // ── Fakes (Muster: BicycleRideIntegrationTest) ───────────────────

    private fun driveWorkersPath(): String {
        val candidate = File(
            "src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition/DriveWorkers.kt"
        )
        return if (candidate.exists()) candidate.path
        else "app/src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition/DriveWorkers.kt"
    }

    private suspend fun awaitLive(manager: LiveActivityManager, id: String?) {
        repeat(200) {
            if (manager.liveSession.value?.id == id) return
            delay(10)
        }
    }

    private class FakeActivityRepository : ActivityRepository {
        val live = MutableStateFlow<ActivitySession?>(null)
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
        override suspend fun updatePauseState(id: String, status: String, pausedAt: Long?) {}
        override suspend fun pauseSession(id: String, endAt: Long) {}
        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override suspend fun updatePauseData(id: String, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?> = flowOf(null)
        override fun getById(id: String): Flow<ActivitySession?> = flowOf(live.value?.takeIf { it.id == id })
        override fun getByExternalId(externalId: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override suspend fun insert(session: ActivitySession) { if (session.isLive) live.value = session }
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

    private class FakeTypeRepository : com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository {
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
