package com.d_drostes_apps.aevum.automation.activityrecognition

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.automation.model.AutomationConstants
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.DetectionEvent
import com.d_drostes_apps.aevum.data.model.RawSourceEvent
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.DetectionEventRepository
import com.d_drostes_apps.aevum.data.repository.RawSourceEventRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.domain.automation.ReviewCandidateUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M12.2: Activity Recognition Worker.
 *
 * - Empfängt IN_VEHICLE-Cluster vom Google Activity Recognition API.
 * - Erzeugt ActivityCandidates mit activityTypeId = "driving".
 * - Übergibt sie an [ReviewCandidateUseCase.acceptAuto] — das gleiche
 *   Verfahren wie Health Connect Sleep. Damit läuft die Fahrt komplett
 *   über die gleiche Live-Session-Architektur, ohne separates System.
 *
 * Confidence-Threshold: ≥ 0.70 (gleicher Wert wie überall in Aevum).
 *
 * Der eigentliche BroadcastReceiver [ActivityTransitionReceiver] sammelt
 * Roh-Events und ruft den Worker. Der Worker kann auch direkt manuell
 * getriggert werden (z. B. aus dem Settings-Screen "Jetzt prüfen").
 */
class ActivityRecognitionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun rawSourceRepository(): RawSourceEventRepository
        fun detectionRepository(): DetectionEventRepository
        fun candidateRepository(): ActivityCandidateRepository
        fun reviewCandidateUseCase(): ReviewCandidateUseCase
        fun triggerEventRepository(): TriggerEventRepository
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
        // M16.6: Schlaf-Schutzschicht gegen Driving-False-Positives
        fun sleepShield(): com.d_drostes_apps.aevum.automation.sleep.SleepShield
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        val bridge = deps.activityRecognitionBridge()
        val rawRepo = deps.rawSourceRepository()
        val detRepo = deps.detectionRepository()
        val candRepo = deps.candidateRepository()
        val reviewUc = deps.reviewCandidateUseCase()
        val triggerRepo = deps.triggerEventRepository()
        val liveActivityManager = deps.liveActivityManager()
        val sleepShield = deps.sleepShield()

        // M12.2: Aggregiere IN_VEHICLE-Cluster aus dem In-Memory-Buffer.
        // Der Buffer wird vom ActivityTransitionReceiver befüllt.
        // M18.3: NICHT bei leerem Cluster returnen — erst prüfen, ob ein
        // EXIT-Marker vorliegt (Fahrt-Ende). Sonst wird der Stop nie
        // verarbeitet, wenn kein neuer Cluster kommt.
        val cluster = bridge.drainVehicleCluster()
        val exitAt = bridge.consumeVehicleExited()

        // M18.64-FIX (Stale-Confirmation-Bug): Die Bestätigung wird IMMER
        // konsumiert — vorher blieb das Flag stehen, wenn kein Cluster da
        // war (DriveProbeWorker setzt markDriveConfirmed, aber der Cluster
        // wurde evtl. schon von einem parallelen Lauf gedrained). Ein
        // stehengebliebenes Flag ließ später einen UNBESTÄTIGTEN Cluster
        // durch (Sofort-Start ohne GPS-Beweis).
        val confirmed = bridge.consumeDriveConfirmation()

        // M18.64: Cluster aus GPS-Geschwindigkeits-Probes bauen, wenn kein
        // AR-Cluster da ist (DriveProbeWorker-Pfad — Fahrt begann ohne
        // Google-IN_VEHICLE-Event). Start = ältester Probe im Fenster.
        val effectiveCluster = cluster ?: if (confirmed) {
            DriveDetectionEngine.toVehicleCluster(bridge.currentDriveProbes())
        } else null

        // M18.45: Bestätigungs-Gate gilt nur für einen Start-Cluster.
        // Bei einem EXIT darf ein leerer/alter Cluster niemals verhindern,
        // dass die laufende Session zuerst beendet wird.
        if (effectiveCluster != null && !confirmed && exitAt == null) {
            Log.d(TAG, "Fahrt nicht per GPS bestätigt — Cluster verworfen (kein Sofort-Start)")
            return Result.success()
        }

        // M18.3: EXIT zuerst verarbeiten — Fahrt-Ende ohne Cluster nötig.
        if (exitAt != null) {
            try {
                val liveSession = liveActivityManager.liveSession.value
                if (liveSession != null && liveSession.isLive &&
                    liveSession.activityTypeId == "transport" &&
                    liveSession.sourceType == "ACTIVITY_RECOGNITION_AUTO"
                ) {
                    liveActivityManager.stop()
                    Log.d(TAG, "Mobilitäts-Session gestoppt (EXIT @ $exitAt)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-Stop der Mobilitäts-Session fehlgeschlagen", e)
            }
        }

        // M18.45: Duplikat-Schutz erst nach dem EXIT-Pfad. So kann ein
        // EXIT eine laufende Auto-Session zuverlässig beenden.
        val existingLive = liveActivityManager.liveSession.value
        if (existingLive != null && existingLive.isLive &&
            existingLive.activityTypeId == "transport" &&
            existingLive.sourceType == "ACTIVITY_RECOGNITION_AUTO"
        ) {
            Log.d(TAG, "Auto-Mobilitäts-Session läuft bereits — Cluster nur als Trigger verbucht")
            if (effectiveCluster != null) {
                val detId = UUID.randomUUID().toString()
                detRepo.insert(
                    DetectionEvent(
                        id = detId,
                        rawEventId = null,
                        sourceId = "activity_recognition",
                        kind = AutomationConstants.DETECTION_ACTIVITY_RECOGNITION_IN_VEHICLE,
                        startAt = effectiveCluster.startMs,
                        endAt = effectiveCluster.endMs,
                        confidence = (effectiveCluster.peakConfidence / 100f).coerceIn(0f, 1f),
                        metadataJson = "{\"duplicate\":true}"
                    )
                )
            }
            return Result.success()
        }

        if (effectiveCluster == null) return Result.success()

        // M18.42-FIX (Root Cause "Autofahrt wird nicht aufgezeichnet"):
        // Der MIN_CLUSTER_DURATION_MS-Check (90s) verhinderte den Start
        // KOMPLETT. Google liefert mit requestActivityTransitionUpdates
        // NUR ENTER/EXIT-Uebergangs-Events — keine kontinuierlichen
        // Samples. Ein einzelnes ENTER hat startMs == endMs ->
        // durationMs = 0 -> fiel IMMER unter die 90s-Schwelle -> die
        // Session wurde NIE gestartet.
        // Jetzt: ENTER startet sofort (Transition API ist intern bereits
        // bestaetigt), EXIT stoppt ueber den vehicleExitedAt-Marker oben.

        // M16.6: SleepShield. Wenn das Cluster mitten in einem nachgewiesenen
        // oder sehr wahrscheinlichen Schlaf-Fenster liegt, handelt es sich
        // um eine IN_VEHICLE-False-Positive (z.B. Vibration im Bus, Sensor-
        // Spring). Wir verwerfen den Cluster hier komplett.
        if (sleepShield.shouldSuppress(effectiveCluster.startMs)) {
            android.util.Log.d(
                "ActivityRecognitionWorker",
                "IN_VEHICLE-Cluster im Schlaf-Fenster → suppressed (start=${effectiveCluster.startMs})"
            )
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val confidence: Float = (effectiveCluster.peakConfidence / 100f).coerceIn(0f, 1f)

        // Dedup: wenn schon ein Roh-Event mit dieser Cluster-ID existiert → skip
        val externalId = "ar_invehicle_${effectiveCluster.startMs}_${effectiveCluster.endMs}"
        val existing = rawRepo.getBySourceAndExternalId("activity_recognition", externalId).first()
        if (existing != null) return Result.success()

        val rawId = UUID.randomUUID().toString()
        rawRepo.insert(
            RawSourceEvent(
                id = rawId,
                sourceId = "activity_recognition",
                externalId = externalId,
                eventType = "IN_VEHICLE_CLUSTER",
                observedAt = now,
                startAt = effectiveCluster.startMs,
                endAt = effectiveCluster.endMs,
                timezoneId = java.time.ZoneId.systemDefault().id,
                payloadJson = "{\"peakConfidence\":$confidence,\"sampleCount\":${effectiveCluster.sampleCount}}"
            )
        )

        val detectionId = UUID.randomUUID().toString()
        detRepo.insert(
            DetectionEvent(
                id = detectionId,
                rawEventId = rawId,
                sourceId = "activity_recognition",
                kind = AutomationConstants.DETECTION_ACTIVITY_RECOGNITION_IN_VEHICLE,
                startAt = effectiveCluster.startMs,
                endAt = effectiveCluster.endMs,
                confidence = confidence,
                metadataJson = "{\"peakConfidence\":$confidence,\"sampleCount\":${effectiveCluster.sampleCount}}"
            )
        )

        val hours = effectiveCluster.durationMs / 3_600_000
        val minutes = (effectiveCluster.durationMs % 3_600_000) / 60_000
        val durationStr = when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }

        // M15: TriggerEvent für die Timeline-Trigger-Liste und für
        // zukünftige Session-Anker. anchorQuality=HIGH für IN_VEHICLE,
        // weil das Google's stabilster Activity-Typ ist.
        triggerRepo.insert(
            TriggerEvent(
                id = UUID.randomUUID().toString(),
                occurredAt = effectiveCluster.startMs,
                type = "DRIVING_STARTED",
                source = "activity_recognition",
                confidence = confidence,
                detectionEventId = detectionId,
                metadataJson = "{\"clusterDurationMs\":${effectiveCluster.durationMs},\"peakConfidence\":$confidence}",
                anchorQuality = "HIGH"
            )
        )
        // M15: zusätzlich ein ENDED-Trigger mit dem Cluster-Ende, damit die
        // Timeline einen klaren Start- und Endpunkt für die Fahrt zeigt.
        triggerRepo.insert(
            TriggerEvent(
                id = UUID.randomUUID().toString(),
                occurredAt = effectiveCluster.endMs,
                type = "DRIVING_ENDED",
                source = "activity_recognition",
                confidence = confidence,
                detectionEventId = detectionId,
                metadataJson = "{\"clusterDurationMs\":${effectiveCluster.durationMs}}",
                anchorQuality = "HIGH"
            )
        )
        Log.d(TAG, "Trigger DRIVING_STARTED + DRIVING_ENDED für ${durationStr} Cluster erzeugt")

        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = "Mobilität ($durationStr)",
            suggestedCategoryId = "transport",
            // M18.3: "transport" statt "driving" — Google unterscheidet nicht
            // zwischen Auto/Bus/Zug, alle sind IN_VEHICLE. Eine ehrliche
            // Kategorie "Mobilität" statt einer Lügen-Kategorie "Autofahren".
            activityTypeId = "transport",
            startAt = effectiveCluster.startMs,
            endAt = effectiveCluster.endMs,
            confidence = confidence,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Activity Recognition: $durationStr im Fahrzeug (Konfidenz ${(confidence * 100).toInt()}%)",
            createdBy = "ACTIVITY_RECOGNITION_V1",
            createdAt = now,
            sourceCandidateId = rawId
        )
        candRepo.insert(candidate)

        // Auto-Accept — gleiche Pipeline wie Schlaf und Geofence.
        // M15: Quelle: ACTIVITY_RECOGNITION_AUTO wird in der Timeline
        // als "Auto" markiert, weil dieser sourceType in AUTO_SOURCES ist.
        reviewUc.acceptAuto(listOf(candidate))

        // M18.3 + M18.42: Session starten — der ENTER-Transition ist
        // die Bestaetigung. Die Dauer-Schwelle entfaellt (Transition API
        // liefert keine kontinuierlichen Samples; MIN_CLUSTER_DURATION_MS
        // blockierte jeden Start).
        // M18.42: Zusaetzlicher Duplicate-Schutz: EXIT-Marker wurde oben
        // schon konsumiert. Wenn eine Session laeuft, wird sie hier nicht
        // doppelt gestartet.
        run {
            // M18.3: Genug Fahrsamples — Mobilitäts-Session starten.
            try {
                val existing = liveActivityManager.liveSession.value
                val isDuplicate = existing != null && existing.isLive && existing.activityTypeId == "transport"
                if (!isDuplicate) {
                    // Aktuelle andere Live-Session beenden (z. B. manuelles
                    // Workout) bevor die Fahrt startet — wie bei Geofence.
                    if (existing != null && existing.isLive) {
                        liveActivityManager.forceFinishForAuto()
                    }
                    val session = liveActivityManager.start(
                        activityTypeId = "transport",
                        title = "Mobilität",
                        sourceType = "ACTIVITY_RECOGNITION_AUTO",
                        // startedAt = Cluster-Start, NICHT now() — der User
                        // war ja schon die ganze Zeit im Fahrzeug.
                        startedAt = effectiveCluster.startMs
                    )
                    Log.d(TAG, "Mobilitäts-Session gestartet: ${session.id} (start=${effectiveCluster.startMs})")
                    // Foreground-Service starten, damit der Timer weiterläuft
                    // wenn der User die App schließt.
                    com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
                } else {
                    Log.d(TAG, "Mobilitäts-Session läuft bereits (${existing?.id}) — Cluster ignoriert")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-Start der Mobilitäts-Session fehlgeschlagen", e)
            }
        }

        return Result.success()
    }

    private companion object {
        const val TAG = "ActivityRecognitionWorker"
        // M12.2: Mindestdauer 90s, um Pendelverkehr / kurze Beifahrten zu ignorieren.
        // Spiegelung der Geofence-Dwell-Schwelle.
        const val MIN_CLUSTER_DURATION_MS = 90_000L
    }
}

/**
 * M12.2: In-Memory-Cluster-Speicher für IN_VEHICLE-Events.
 *
 * Android liefert IN_VEHICLE als Strom von DetectedActivity-Updates
 * (Confidence zwischen 0..100). Wir sammeln sie hier, solange sie
 * zeitlich nahe beieinander liegen (max 5min Lücke) und geben
 * den fertigen Cluster an den Worker.
 *
 * Bewusst kein @Singleton mit eigener Concurrency-Layer — die Activity
 * Recognition API liefert auf dem Main-Thread, der Worker liest auf
 * Dispatchers.IO. Da drain() den Buffer atomar ersetzt, ist die
 * Datenkonsistenz ohne Locking gewährleistet.
 */
@Singleton
class ActivityRecognitionBridge @Inject constructor(
    // M18.44: Gate-Checks für die Trigger-Settings (driving/walking/bicycle).
    // Der Receiver fragt pro Event ab, ob die jeweilige Erkennung aktiv ist.
    private val settingsRepository: com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
) {
    @Volatile private var pending: VehicleCluster? = null
    private val maxGapMs = 5L * 60 * 1000 // 5 Minuten

    /** M18.3: EXIT-Signal für Fahrzeug. Der Receiver setzt diesen Marker,
     * wenn Google eine IN_VEHICLE-EXIT-Transition liefert. Der Worker
     * konsumiert ihn und stoppt die Session — so wird nie im selben Lauf
     * gestartet UND gestoppt (M15-Bug). */
    @Volatile private var vehicleExitedAt: Long? = null

    // ──────────────────────────────────────────────────────────────
    // M18.45: SMARTE FAHRTERKENNUNG (User-Feedback: "kurze Erkennungen
    // verwerfen", "Activity + Standort kombinieren", "Timer stoppen").
    //
    // lastVehicleSampleMs: Herzschlag der Fahrt. Der DriveWatchdog
    // stoppt die Session, wenn 8 Minuten lang kein IN_VEHICLE-Signal
    // mehr kam (Google liefert oft keinen EXIT — User steht am Ziel)
    // und der GPS-Bewegungs-Check den Stillstand bestätigt.
    // ──────────────────────────────────────────────────────────────
    @Volatile private var lastVehicleSampleMs: Long = 0L

    /** Letzter IN_VEHICLE-Sample — Herzschlag für den Watchdog. */
    @Synchronized
    fun lastVehicleSample(): Long = lastVehicleSampleMs

    // ──────────────────────────────────────────────────────────────
    // M18.64: GPS-GESCHWINDIGKEITS-PROBES (DriveProbeWorker).
    //
    // Unabhängiger Erkennungspfad neben Googles IN_VEHICLE-Transitions:
    // Der DriveProbeWorker holt alle 2 Minuten einen GPS-Fix und puffert
    // hier die Geschwindigkeits-Probes. Die DriveDetectionEngine
    // klassifiziert die Serie (mehrere aufeinanderfolgende Messungen
    // über AUTO_SPEED_MPS = echte Fahrt). Damit werden Fahrten auch
    // erkannt, wenn Google kein IN_VEHICLE-Event liefert (App im
    // Hintergrund, Fahrt begann vor dem App-Start, Sensor-Spring).
    //
    // Herzschlag-Kopplung: Eine als Fahrt klassifizierte Probe refresht
    // lastVehicleSampleMs — der DriveWatchdog (8 Min ohne Signal) lebt
    // damit auch bei AR-losen Fahrten. Stillstands-Probes refreshen den
    // Herzschlag NICHT → der Watchdog stoppt die Session am Ziel.
    // ──────────────────────────────────────────────────────────────
    private val driveProbes = java.util.Collections.synchronizedList(
        mutableListOf<com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine.DriveProbe>()
    )

    /** M18.64: GPS-Probe puffern. [refreshHeartbeat] nur bei bestätigter
     *  Fahrt-Klassifikation true — Stillstand darf den Watchdog nicht
     *  am Leben halten. Alte Probes (> 15 Min) werden beim Hinzufügen
     *  entfernt — der Puffer bleibt auf das Erkennungsfenster begrenzt. */
    @Synchronized
    fun addDriveProbe(
        probe: com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine.DriveProbe,
        refreshHeartbeat: Boolean
    ) {
        val cutoff = probe.timestampMs - com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine.MAX_PROBE_AGE_MS
        driveProbes.removeAll { it.timestampMs < cutoff }
        driveProbes.add(probe)
        if (refreshHeartbeat) lastVehicleSampleMs = probe.timestampMs
    }

    /** M18.64: Herzschlag der Fahrt direkt refreshen (nach bestätigter
     *  GPS-Klassifikation). Hält den DriveWatchdog am Leben, wenn Google
     *  keine IN_VEHICLE-Samples liefert (Normalfall bei GPS-Erkennung). */
    @Synchronized
    fun refreshDriveHeartbeat(tsMs: Long) {
        lastVehicleSampleMs = tsMs
    }

    /** M18.64: Alle gepufferten Probes entnehmen (atomar). */
    @Synchronized
    fun drainDriveProbes(): List<com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine.DriveProbe> {
        val copy = driveProbes.toList()
        driveProbes.clear()
        return copy
    }

    /** M18.64: Aktuelle Probes lesen (ohne zu leeren). */
    @Synchronized
    fun currentDriveProbes(): List<com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine.DriveProbe> =
        driveProbes.toList()

    // M18.45: Bestätigungs-Flag. Der DriveConfirmWorker setzt es, wenn
    // nach 2 Minuten die GPS-Bewegung ≥ 200 m bestätigt hat. Der
    // ActivityRecognitionWorker drainet den Cluster nur dann.
    @Volatile private var driveConfirmed = false

    @Synchronized
    fun markDriveConfirmed() {
        driveConfirmed = true
    }

    @Synchronized
    fun consumeDriveConfirmation(): Boolean {
        val c = driveConfirmed
        driveConfirmed = false
        return c
    }

    @Synchronized
    fun addSample(epochMs: Long, confidence: Int) {
        // M18.45: Herzschlag für den DriveWatchdog — jedes IN_VEHICLE-
        // Signal bestätigt, dass die Fahrt noch läuft.
        lastVehicleSampleMs = epochMs
        val c = pending
        if (c == null || epochMs - c.lastMs > maxGapMs) {
            // Neuer Cluster
            pending = VehicleCluster(
                startMs = epochMs,
                endMs = epochMs,
                lastMs = epochMs,
                sampleCount = 1,
                peakConfidence = confidence
            )
        } else {
            pending = c.copy(
                endMs = epochMs,
                lastMs = epochMs,
                sampleCount = c.sampleCount + 1,
                peakConfidence = maxOf(c.peakConfidence, confidence)
            )
        }
    }

    /** M18.3: EXIT-Transition für IN_VEHICLE markieren (Fahrt vorbei). */
    @Synchronized
    fun markVehicleExited(epochMs: Long) {
        vehicleExitedAt = epochMs
    }

    /** M18.44: Echte Gates aus den Trigger-Settings (cached, non-blocking). */
    @Volatile private var cachedDriving = true
    @Volatile private var cachedWalking = true
    @Volatile private var cachedBicycle = true
    @Volatile private var settingsLoadedAt = 0L

    @Synchronized
    fun isDrivingEnabled(): Boolean {
        refreshCacheIfStale()
        return cachedDriving
    }

    @Synchronized
    fun isWalkingEnabled(): Boolean {
        refreshCacheIfStale()
        return cachedWalking
    }

    @Synchronized
    fun isBicycleEnabled(): Boolean {
        refreshCacheIfStale()
        return cachedBicycle
    }

    /** Settings max. 30s cachen — die DB-Query ist sonst pro Event zu teuer. */
    private fun refreshCacheIfStale() {
        val now = System.currentTimeMillis()
        if (now - settingsLoadedAt > 30_000L) {
            settingsLoadedAt = now
            try {
                val settings = kotlinx.coroutines.runBlocking { settingsRepository.get().first() }
                cachedDriving = settings?.drivingDetectionEnabled ?: true
                cachedWalking = settings?.walkingDetectionEnabled ?: true
                cachedBicycle = settings?.bicycleDetectionEnabled ?: true
            } catch (_: Exception) {
                // Cache behalten (Default an) — nie den Receiver crashen.
            }
        }
    }

    /** M18.3: EXIT-Marker konsumieren (atomar). Null = kein Exit-Signal. */
    @Synchronized
    fun consumeVehicleExited(): Long? {
        val v = vehicleExitedAt
        vehicleExitedAt = null
        return v
    }

    @Synchronized
    fun drainVehicleCluster(): VehicleCluster? {
        val c = pending ?: return null
        pending = null
        return c.copy(durationMs = c.endMs - c.startMs)
    }

    /**
     * M12.2: Force-flush — z. B. wenn der User die App öffnet.
     * Wird aus dem ActivityRecognition-Receiver gerufen, sobald der
     * letzte Sample länger als 5min her ist.
     */
    @Synchronized
    fun forceDrainIfStale(nowMs: Long): VehicleCluster? {
        val c = pending ?: return null
        if (nowMs - c.lastMs > maxGapMs) {
            pending = null
            return c.copy(durationMs = c.endMs - c.startMs)
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    // M14: STILL-Cluster für die Schlaf-Fusion.
    //
    // Google Activity Recognition liefert STILL-Transitions, wenn das Gerät
    // länger als ~30s still liegt. Auf einem Nachttisch liefert das nachts
    // ein sehr starkes Schlaf-Signal — kombiniert mit Screen-Events und
    // Digital Balance wird daraus ein hochkonfidenter Schlaf-Candidate.
    //
    // Eigener Buffer, KEIN Konflikt mit der VehicleCluster-Logik oben: ein
    // Gerät kann in der Nacht nicht gleichzeitig IN_VEHICLE und STILL sein.
    // ──────────────────────────────────────────────────────────────────────
    @Volatile private var pendingStill: StillCluster? = null
    private val stillMaxGapMs = 10L * 60 * 1000 // 10 min — länger als Vehicle, weil STILL
                                                 // nicht ständig neu gemeldet wird

    @Synchronized
    fun addStillSample(epochMs: Long, confidence: Int) {
        val c = pendingStill
        if (c == null || epochMs - c.lastMs > stillMaxGapMs) {
            pendingStill = StillCluster(
                startMs = epochMs,
                endMs = epochMs,
                lastMs = epochMs,
                sampleCount = 1,
                peakConfidence = confidence
            )
        } else {
            pendingStill = c.copy(
                endMs = epochMs,
                lastMs = epochMs,
                sampleCount = c.sampleCount + 1,
                peakConfidence = maxOf(c.peakConfidence, confidence)
            )
        }
    }

    /**
     * Liefert den akkumulierten STILL-Cluster, aber NUR wenn er mindestens
     * [minDurationMs] lang ist. Kürzere Cluster (z. B. User legt das Phone
     * mal 5 min weg) werden verworfen — die sind kein Schlaf-Signal.
     *
     * Atomar: drained den Buffer, sodass nachfolgende Calls einen frischen
     * Cluster aufbauen.
     */
    @Synchronized
    fun drainStillCluster(minDurationMs: Long = 4L * 60 * 60 * 1000): StillCluster? {
        val c = pendingStill ?: return null
        pendingStill = null
        val withDuration = c.copy(durationMs = c.endMs - c.startMs)
        return if (withDuration.durationMs >= minDurationMs) withDuration else null
    }

    @Synchronized
    fun currentStillCluster(nowMs: Long): StillCluster? {
        val c = pendingStill ?: return null
        return c.copy(durationMs = nowMs - c.startMs, lastMs = nowMs)
    }
}

data class VehicleCluster(
    val startMs: Long,
    val endMs: Long,
    val lastMs: Long,
    val sampleCount: Int,
    val peakConfidence: Int,
    val durationMs: Long = endMs - startMs
)

/**
 * M14: STILL-Cluster — Pendant zu [VehicleCluster] für die Schlaf-Fusion.
 * Gleiches Schema, anderer Use-Case.
 */
data class StillCluster(
    val startMs: Long,
    val endMs: Long,
    val lastMs: Long,
    val sampleCount: Int,
    val peakConfidence: Int,
    val durationMs: Long = endMs - startMs
)

/**
 * M15: Trigger-Worker für ON_BICYCLE / WALKING / RUNNING.
 *
 * Im Gegensatz zu IN_VEHICLE (Auto-Start) sind diese Activity-Typen
 * zu unzuverlässig für automatische Sessions. Wir erzeugen NUR TriggerEvents
 * mit niedriger Confidence (50%) — sie erscheinen in der Timeline als
 * dezente Marker, der User kann sie bei Bedarf manuell zu einer Session
 * "befördern".
 *
 * Eigener Worker, damit der schwere IN_VEHICLE-Pfad (mit Auto-Start)
 * nicht durch zusätzliche Transition-Verarbeitung ausgebremst wird.
 */
class ActivityRecognitionTriggerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun triggerEventRepository(): TriggerEventRepository
        fun rawSourceRepository(): RawSourceEventRepository
        fun detectionRepository(): DetectionEventRepository
        // M18.23: Event-driven GPS-Check
        fun eventDrivenLocationChecker(): com.d_drostes_apps.aevum.automation.geofence.EventDrivenLocationChecker
        fun geofenceTransitionProcessor(): com.d_drostes_apps.aevum.automation.geofence.GeofenceTransitionProcessor
        fun geofenceRepository(): com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext, Deps::class.java
        )
        val triggerRepo = deps.triggerEventRepository()
        val rawRepo = deps.rawSourceRepository()
        val detRepo = deps.detectionRepository()
        val now = System.currentTimeMillis()
        val inputData = inputData
        val activityType = inputData.getString(KEY_ACTIVITY_TYPE) ?: return Result.failure()
        val transition = inputData.getString(KEY_TRANSITION) ?: return Result.failure()
        val triggerType = triggerTypeFor(activityType, transition) ?: return Result.success()
        val confidence = inputData.getFloat(KEY_CONFIDENCE, 0.5f).coerceIn(0f, 1f)
        val externalId = "ar_${activityType.lowercase()}_${transition.lowercase()}_$now"

        // M18.27: Vorab-Deklaration — der Suppress-Check unten laeuft auch
        // dann, wenn der GPS-Check eine Exception wirft (dann ist
        // matchedGeofence null -> kein Suppress, konservativ).
        var matchedGeofence: com.d_drostes_apps.aevum.data.model.PlaceGeofence? = null
        var walkingSuppressed = false

        // M18.23: Event-driven GPS-Check. Bei jedem Activity-Recognition-Event
        // wird ein einmaliger GPS-Fix geholt und geprueft, ob der User in einer
        // Geofence ist. Das ersetzt das 24/7 Geofencing und verhindert False-Trigger.
        try {
            val locResult = deps.eventDrivenLocationChecker().checkCurrentLocationAgainstGeofences()
            matchedGeofence = locResult.matchedGeofence
            val allGeofences = locResult.allGeofences

            // M18.27: Walking/Running-False-Positive-Fix.
            // Google's Activity Recognition meldet WALKING/RUNNING haeufig
            // mit niedriger Confidence, wenn der User NUR IM HAUS den Raum
            // wechselt (z.B. Wohnzimmer -> Kueche). Der GPS-Fix oben zeigt:
            // Wenn der User in einer bekannten Geofence (Zuhause/Arbeit) ist,
            // ist ein WALKING/RUNNING-Trigger mit hoher Wahrscheinlichkeit
            // ein Raumwechsel statt echter Bewegung. Wir verwerfen ihn.
            // WICHTIG: Der Check steht NACH dem Geofence-Handling — ein
            // WALKING-ENTER beim Ankommen in der Arbeit darf den
            // Geofence-Enter (Auto-Start) NICHT verhindern. Suppressed wird
            // nur: (a) EXIT-Transitions (Ende eines Raumwechsels) und
            // (b) ENTER in einer Geofence OHNE Auto-Start (reiner Raumwechsel).
            walkingSuppressed =
                (activityType == "WALKING" || activityType == "RUNNING") &&
                matchedGeofence != null &&
                (transition == "EXIT" || matchedGeofence.autoStartActivityTypeId == null)

            if (matchedGeofence != null && matchedGeofence.autoStartActivityTypeId != null) {
                // Der Activity-Recognition-GPS-Fallback ist nur ein Signal für
                // Suppression/Diagnostik. Er darf keinen bestätigten Geofence-
                // Übergang erzeugen, weil er Debouncer und StabilizationWorker
                // umgehen würde. Echte Übergänge kommen ausschließlich über
                // GeofenceBroadcastReceiver.
                android.util.Log.d(TAG, "GPS-Check: Geofence ${matchedGeofence.name} erkannt; GMS bleibt authoritative")
            } else if (matchedGeofence == null && allGeofences.isNotEmpty() && transition == "EXIT") {
                // Ein GPS-Fix außerhalb ist ebenfalls kein bestätigter EXIT.
                // Dieser Pfad bleibt dem GMS-Geofence-Ereignis vorbehalten.
                android.util.Log.d(TAG, "GPS-Check: außerhalb einer Geofence; EXIT bleibt GMS vorbehalten")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "GPS-Check fehlgeschlagen (nicht blockierend): ${e.message}")
        }

        // M18.27: Walking/Running-False-Positive-Suppression.
        // Der Check laeuft NACH dem Geofence-Handling (das darf nie
        // blockiert werden). Wenn der User in einer bekannten Geofence
        // ist (Raumwechsel drinnen) und Google WALKING/RUNNING meldet,
        // verwerfen wir den Trigger komplett — kein Raw-Event, kein
        // DetectionEvent, kein Timeline-Marker.
        if (walkingSuppressed) {
            android.util.Log.d(
                TAG,
                "WALKING/RUNNING in Geofence '${matchedGeofence?.name}' -> suppressed (Raumwechsel)"
            )
            return Result.success()
        }

        val existing = rawRepo.getBySourceAndExternalId("activity_recognition", externalId).first()
        if (existing != null) return Result.success()

        val rawId = UUID.randomUUID().toString()
        rawRepo.insert(
            RawSourceEvent(
                id = rawId,
                sourceId = "activity_recognition",
                externalId = externalId,
                eventType = "${activityType}_${transition}",
                observedAt = now,
                startAt = now,
                endAt = now,
                timezoneId = java.time.ZoneId.systemDefault().id,
                payloadJson = "{\"activityType\":\"$activityType\",\"transition\":\"$transition\",\"confidence\":$confidence}"
            )
        )
        val detectionId = UUID.randomUUID().toString()
        detRepo.insert(
            DetectionEvent(
                id = detectionId,
                rawEventId = rawId,
                sourceId = "activity_recognition",
                kind = "ACTIVITY_RECOGNITION_${activityType}",
                startAt = now,
                confidence = confidence,
                metadataJson = "{\"activityType\":\"$activityType\",\"transition\":\"$transition\"}"
            )
        )
        triggerRepo.insert(
            TriggerEvent(
                id = UUID.randomUUID().toString(),
                occurredAt = now,
                type = triggerType,
                source = "activity_recognition",
                confidence = confidence,
                detectionEventId = detectionId,
                metadataJson = "{\"activityType\":\"$activityType\"}",
                // M15: ON_BICYCLE/WALKING/RUNNING sind LOW — Google's Confidence
                // springt oft zwischen den Typen, ein HIGH wäre gelogen.
                anchorQuality = "LOW"
            )
        )
        Log.d(TAG, "Trigger $triggerType erzeugt (Confidence=$confidence)")
        return Result.success()
    }

    private fun triggerTypeFor(activityType: String, transition: String): String? {
        if (transition != "ENTER" && transition != "EXIT") return null
        val suffix = if (transition == "ENTER") "_STARTED" else "_ENDED"
        return when (activityType) {
            "ON_BICYCLE" -> "BICYCLE$suffix"
            "WALKING" -> "WALKING$suffix"
            "RUNNING" -> "RUNNING$suffix"
            else -> null
        }
    }

    companion object {
        const val KEY_ACTIVITY_TYPE = "activity_type"
        const val KEY_TRANSITION = "transition"
        const val KEY_CONFIDENCE = "confidence"
        private const val TAG = "ActivityRecognitionTriggerWorker"
    }
}

// M18.43: Konstanten für den Walking/Running-5-Minuten-Timer — im
// Receiver (ActivityTransitionReceiver) und im TriggerWorker nutzbar.
private const val WALKING_TRIGGER_DELAY_MS = 5L * 60 * 1000
private const val WALKING_TRIGGER_WORK_NAME = "aevum.walking_trigger_delay"
private const val RUNNING_TRIGGER_WORK_NAME = "aevum.running_trigger_delay"

/**
 * M12.2: BroadcastReceiver für Activity Transition Updates.
 *
 * Google liefert nur dann ein Intent, wenn sich der Activity-Typ
 * ändert (z. B. STILL → IN_VEHICLE). Wir aggregieren die Updates
 * in [ActivityRecognitionBridge] und enqueuen den Worker.
 *
 * Hinweis: Wir parsen den GMS PendingResult (transitions + confidence),
 * nicht die AOSP ACTION_ACTIVITY_RECOGNITION — denn GMS ist die
 * zuverlässige Quelle, AOSP broadcastet nicht standardisiert.
 */
class ActivityTransitionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val bridge = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ActivityRecognitionBridgeProvider::class.java
        ).activityRecognitionBridge()

        // Wir parsen den GMS PendingResult (transitions + confidence)
        if (com.google.android.gms.location.ActivityTransitionResult.hasResult(intent)) {
            val result = com.google.android.gms.location.ActivityTransitionResult.extractResult(intent) ?: return
            val now = System.currentTimeMillis()
            var hasChange = false
            for (event in result.transitionEvents) {
                when (event.activityType) {
                    DetectedActivity.IN_VEHICLE -> {
                        // M18.44: Gate — Autofahren-Erkennung in den
                        // Trigger-Settings ausgeschaltet? Dann wird das
                        // Event komplett ignoriert (kein Sample, kein Exit).
                        if (!bridge.isDrivingEnabled()) {
                            hasChange = false
                            continue
                        }
                        // M18.3: ENTER vs EXIT unterscheiden — das ist der
                        // Kern-Fix für "Android erkennt Autofahren nicht":
                        // Vorher wurde jedes IN_VEHICLE-Event als Sample
                        // gepuffert, der Worker startete UND stoppte die
                        // Session im selben Lauf (sofort wieder beendet).
                        val transitionType = getTransitionInt(event)
                        if (transitionType ==
                            com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT
                        ) {
                            bridge.markVehicleExited(now)
                            // M18.45: EXIT = Fahrt (vermutlich) vorbei.
                            // Geplante Bestätigung abbrechen, Cluster leeren
                            // (alte Fahrt soll nicht in neue ragen), und den
                            // 90s-Watchdog starten (Ampel-Toleranz: kommt in
                            // 90s wieder IN_VEHICLE, läuft die Fahrt weiter).
                            DriveConfirmWorker.cancel(context)
                            bridge.drainVehicleCluster()
                            DriveWatchdogWorker.schedule(context, DriveWatchdogWorker.MODE_TRANSITION)
                        } else {
                            bridge.addSample(now, 75)
                            // M18.45: Kein Sofort-Start mehr! Der
                            // DriveConfirmWorker wartet 2 Minuten und prüft
                            // dann per GPS-Bewegung (≥200m), ob es eine
                            // echte Fahrt ist. REPLACE: jeder weitere ENTER
                            // resetet den Timer.
                            DriveConfirmWorker.schedule(context)
                            // Watchdog vorsorglich starten: Feuert nach 8
                            // Minuten — wenn dann keine Session läuft
                            // (Fahrt nie bestätigt), ist er ein No-Op.
                            DriveWatchdogWorker.schedule(context, DriveWatchdogWorker.MODE_NO_SIGNAL)
                        }
                        hasChange = true
                    }
                    // M14: STILL liefert das zweite Schlaf-Signal. Wir puffern
                    // jede STILL-Transition — der SleepFusionWorker aggregiert
                    // sie dann zu einem StillCluster und ruft die Fusion auf.
                    DetectedActivity.STILL -> {
                        bridge.addStillSample(now, 75)
                        hasChange = true
                    }
                    // M15: ON_BICYCLE / WALKING / RUNNING erzeugen nur
                    // TriggerEvents (kein Auto-Start, da zu unzuverlässig).
                    DetectedActivity.ON_BICYCLE,
                    DetectedActivity.WALKING,
                    DetectedActivity.RUNNING -> {
                        // M18.44: Gates — Walking/Rad-Erkennung aus den
                        // Trigger-Settings. Deaktiviert = Event ignorieren
                        // (auch Timer nicht starten/canceln).
                        val walkingOk = event.activityType == DetectedActivity.WALKING && bridge.isWalkingEnabled()
                        val runningOk = event.activityType == DetectedActivity.RUNNING && bridge.isWalkingEnabled()
                        val bicycleOk = event.activityType == DetectedActivity.ON_BICYCLE && bridge.isBicycleEnabled()
                        if (!walkingOk && !runningOk && !bicycleOk) {
                            hasChange = false
                            continue
                        }
                        // M18.43-FIX (User-Wunsch "Walking begonnen soll nur
                        // angemerkt werden, wenn man es mindestens 5 Minuten
                        // am Stück tut"): WALKING/RUNNING-ENTER starten einen
                        // 5-Minuten-Timer (UniqueWork + REPLACE). Wenn der
                        // User durchgehend läuft, feuert der Timer nach 5 Min
                        // und erzeugt den Trigger. Ein EXIT dazwischen
                        // cancelt den Timer -> kein False-Trigger bei
                        // kurzen Wegen (Wohnzimmer->Küche, 30s zum Auto).
                        // ON_BICYCLE bleibt sofort (klares Signal).
                        val transitionType = getTransitionInt(event)
                        if (event.activityType == DetectedActivity.ON_BICYCLE) {
                            enqueueTriggerWorker(context, event.activityType, transitionType)
                        } else {
                            val isEnter = transitionType ==
                                com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER
                            val workName = if (event.activityType == DetectedActivity.WALKING) {
                                WALKING_TRIGGER_WORK_NAME
                            } else {
                                RUNNING_TRIGGER_WORK_NAME
                            }
                            if (isEnter) {
                                // Timer (neu) starten — REPLACE refresht bei
                                // jedem weiteren ENTER-Sample.
                                val data = androidx.work.Data.Builder()
                                    .putString(ActivityRecognitionTriggerWorker.KEY_ACTIVITY_TYPE,
                                        if (event.activityType == DetectedActivity.WALKING) "WALKING" else "RUNNING")
                                    .putString(ActivityRecognitionTriggerWorker.KEY_TRANSITION, "ENTER")
                                    .putFloat(ActivityRecognitionTriggerWorker.KEY_CONFIDENCE, 0.65f)
                                    .build()
                                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                                    workName,
                                    androidx.work.ExistingWorkPolicy.REPLACE,
                                    androidx.work.OneTimeWorkRequestBuilder<ActivityRecognitionTriggerWorker>()
                                        .setInputData(data)
                                        .setInitialDelay(WALKING_TRIGGER_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                                        .build()
                                )
                            } else {
                                // EXIT: Timer canceln — der User hat NICHT
                                // 5 Minuten am Stück gelaufen.
                                androidx.work.WorkManager.getInstance(context).cancelUniqueWork(workName)
                            }
                        }
                        hasChange = true
                    }
                }
            }
            if (!hasChange) return
            // M18.45: Der ActivityRecognitionWorker (Session-Starter) wird
            // NICHT mehr direkt enqueued — er läuft nur noch nach
            // Bestätigung durch den DriveConfirmWorker (2 Min + GPS-Bewegung).
            // Das verhindert Sofort-Starts bei kurzen Fehl-Erkennungen.
            // M14: separater Worker für die Schlaf-Fusion. Wir enqueuen ihn
            // ebenfalls, der dedupliziert sich selbst über source_candidate_id.
            androidx.work.OneTimeWorkRequestBuilder<
                com.d_drostes_apps.aevum.automation.sleep.SleepFusionWorker
            >().build().also {
                androidx.work.WorkManager.getInstance(context).enqueue(it)
            }
        }
    }

    /**
     * M15: Wrapper für den Property-Zugriff auf [ActivityTransitionEvent].
     * GMS-Lib v21.3.0 exportiert die Property als `getTransitionType()` (Java),
     * in Kotlin als `transitionType` (nicht `transition`). Diese Helper-
     * Methode kapselt den Property-Namen, falls Google das in zukünftigen
     * Versionen wieder ändert.
     */
    private fun getTransitionInt(event: com.google.android.gms.location.ActivityTransitionEvent): Int {
        return event.transitionType
    }

    private fun enqueueTriggerWorker(context: Context, activityType: Int, transitionInt: Int) {
        val typeName = when (activityType) {
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.RUNNING -> "RUNNING"
            else -> return
        }
        val transName = when (transitionInt) {
            com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
            com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
            else -> return
        }
        val data = androidx.work.Data.Builder()
            .putString(ActivityRecognitionTriggerWorker.KEY_ACTIVITY_TYPE, typeName)
            .putString(ActivityRecognitionTriggerWorker.KEY_TRANSITION, transName)
            // M18.27: Confidence-Schwelle 0.5 -> 0.65. Google's
            // Transition-Events liefern KEINE echte Confidence — die 0.5
            // war ein willkuerlicher Platzhalter, der in der Timeline als
            // "50% Konfidenz" erschien und Raumwechsel (Wohnzimmer->Kueche)
            // als Walking markierte. 0.65 signalisiert: nur Trigger, die
            // der GPS-Check NICHT als Raumwechsel identifiziert hat.
            .putFloat(ActivityRecognitionTriggerWorker.KEY_CONFIDENCE, 0.65f)
            .build()
        val request = androidx.work.OneTimeWorkRequestBuilder<ActivityRecognitionTriggerWorker>()
            .setInputData(data)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueue(request)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityRecognitionBridgeProvider {
    fun activityRecognitionBridge(): ActivityRecognitionBridge
}

/**
 * M12.2: Hilfsfunktion zum Registrieren der Activity-Transition-Updates
 * an Googles ActivityRecognitionClient. Wird beim App-Start und nach
 * Permission-Grant aufgerufen.
 */
object ActivityRecognitionRegistrar {
    fun register(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val client = ActivityRecognition.getClient(context)
        val request = com.google.android.gms.location.ActivityTransitionRequest(
            listOf(
                // M12.2: IN_VEHICLE — Auto-Start für Fahrten.
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
                // M14: STILL — zweites Signal für die Schlaf-Fusion.
                // Google liefert nur Transition-Events, nicht den kontinuierlichen
                // STILL-Stream, daher reichen ENTER+EXIT.
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
                // M15: ON_BICYCLE / WALKING / RUNNING — Trigger-Events.
                // Diese Activity-Typen sind zu unzuverlässig für Auto-Start,
                // liefern aber nützliche Trigger-Marker in der Timeline.
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.ON_BICYCLE)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.ON_BICYCLE)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.WALKING)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.WALKING)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.RUNNING)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.RUNNING)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9002,
            Intent(context, ActivityTransitionReceiver::class.java).apply {
                action = "com.d_drostes_apps.aevum.ACTIVITY_RECOGNITION_TRANSITION"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            client.requestActivityTransitionUpdates(request, pendingIntent)
        } catch (_: Exception) { /* permission denied or GMS missing */ }
    }
}

/**
 * M12.2: Permission state detection for Activity Recognition.
 * Liefert true, wenn die App ACTIVITY_RECOGNITION hat.
 */
object ActivityRecognitionPermission {
    fun isGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
