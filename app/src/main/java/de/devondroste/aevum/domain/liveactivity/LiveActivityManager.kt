package de.devondroste.aevum.domain.liveactivity

import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M9/M10: Live Activity Manager — vereinfachte Zustandsmaschine.
 *
 * Single source of truth: die [ActivitySession] in der DB.
 * Alle Zeiten werden on-the-fly aus `startAt`, `currentPauseStartedAt`
 * und `totalPausedMs` berechnet — keine RAM-Duplikate.
 *
 * M10: Live-Timer wird über [tick] (1Hz, läuft nur wenn RUNNING) und
 * [nowMs] (jeder Tick aktualisiert) in die UI gepumpt. Die Card nimmt
 * [nowMs] als Zeit-Referenz, sodass die Anzeige in Echtzeit läuft.
 */
@Singleton
class LiveActivityManager @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val triggerEventRepository: TriggerEventRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // M17: In-Memory Geofence-Tracking für Auto-Discard. Wir mappen
    // geofenceId → (sessionId, scheduledDiscardJob). Wird beim Auto-Start
    // befüllt, beim Refresh zurückgesetzt, beim Stop/Exit abgebrochen.
    // Bewusst nicht in der DB — die Information lebt nur so lange wie
    // die Live-Session selbst (nach stop() ist der Job obsolet).
    private val autoDiscardByGeofence = java.util.concurrent.ConcurrentHashMap<
        String, AutoDiscardEntry>()

    private data class AutoDiscardEntry(
        val sessionId: String,
        val job: kotlinx.coroutines.Job
    )

    val liveSession: StateFlow<ActivitySession?> =
        activityRepository.getLiveSession()
            .catch { emit(null) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val liveState: StateFlow<LiveActivityState> =
        liveSession.map { session -> mapState(session) }
            .catch { emit(LiveActivityState.Idle) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), LiveActivityState.Idle)

    /** Liste der zuletzt verwendeten Activity Types für Quick-Start. */
    val recentActivityTypes: StateFlow<List<RecentActivityType>> =
        activityRepository.getAll()
            .map { sessions ->
                sessions
                    .asSequence()
                    .filter { it.deletedAt == null }
                    .filter { it.activityTypeId != null }
                    .sortedByDescending { it.startAt }
                    .mapNotNull { session ->
                        val typeId = session.activityTypeId ?: return@mapNotNull null
                        RecentActivityType(id = typeId, title = session.title, lastUsedAt = session.startAt)
                    }
                    .distinctBy { it.id }
                    .take(4)
                    .toList()
            }
            .catch { emit(emptyList()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** M9.2: Favorites als reaktive Liste. */
    val favoriteActivityTypes: StateFlow<List<ActivityType>> =
        activityTypeRepository.getFavorites()
            .catch { emit(emptyList()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** M18.19: Alle ActivityTypes (für das Wechsel-Popup). */
    val allActivityTypes: StateFlow<List<ActivityType>> =
        activityTypeRepository.getAll()
            .catch { emit(emptyList()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // M18.19: Synchroner In-Memory-Cache für die Notification (Farbe/Icon).
    // buildNotification() läuft im Service-Thread und kann nicht suspendieren.
    // Befüllt in start()/startAutoAndScheduleDiscard() — dort wird der Type
    // ohnehin geladen.
    private val typeCache = java.util.concurrent.ConcurrentHashMap<String, ActivityType>()

    fun cachedActivityType(id: String?): ActivityType? =
        id?.let { typeCache[it] }

    /** M9.2: Toggle favorite status. */
    suspend fun toggleFavorite(type: ActivityType) {
        activityTypeRepository.setFavorite(type.id, !type.isFavorite)
    }

    // ============================================================
    // M10: Live-Timer — single source of truth for the wall clock
    // ============================================================

    /**
     * Tick incremented once per second. The UI collects this and
     * recomposes when a fresh second arrives. While no session is
     * RUNNING the value stays put so the paused/idle cards freeze.
     */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    /**
     * Wall-clock millis captured at each tick. UI uses this to
     * compute the live elapsed time. When the session is paused,
     * the ticker stops and this value freezes automatically because
     * it's only updated inside the running-tick loop.
     */
    private val _nowMs = MutableStateFlow(System.currentTimeMillis())
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    init {
        // Single ticker loop: drives both _tick (counter) and _nowMs (wall clock).
        // Only fires updates while a session is RUNNING — paused and idle states freeze.
        scope.launch {
            while (true) {
                try {
                    val session = liveSession.value
                    if (session?.isRunning == true) {
                        _nowMs.value = System.currentTimeMillis()
                        _tick.value++
                    }
                } catch (_: Exception) { }
                delay(1_000)
            }
        }
    }

    // ============================================================
    // State transitions
    // ============================================================

    suspend fun start(
        activityTypeId: String,
        title: String? = null,
        note: String? = null,
        // M11: allow auto-started sessions to have a different sourceType
        sourceType: String = "LIVE",
        // M11: optional startedAt — wenn der User eine vergangene Startzeit wählt
        startedAt: Long = System.currentTimeMillis(),
        // M12.1: optional trigger reference for auto-start traceability
        sourceTriggerId: String? = null
    ): ActivitySession {
        liveSession.value?.let { existing -> forceFinish(existing) }

        val type = activityTypeRepository.getById(activityTypeId).first()
        type?.let { typeCache[it.id] = it }
        val now = System.currentTimeMillis()
        val sessionStart = startedAt.coerceAtMost(now)
        val session = ActivitySession(
            id = UUID.randomUUID().toString(),
            title = title?.takeIf { it.isNotBlank() } ?: type?.name ?: "Aktivität",
            categoryId = type?.defaultCategoryId,
            activityTypeId = activityTypeId,
            startAt = sessionStart,
            endAt = null,
            timezoneId = TimeZone.getDefault().id,
            sourceType = sourceType,
            createdBy = sourceType,
            sourceTriggerId = sourceTriggerId,
            sessionStatus = "RUNNING",
            totalPausedMs = 0L,
            currentPauseStartedAt = null,
            note = note?.takeIf { it.isNotBlank() }
        )

        activityRepository.insert(session)
        _tick.value = 0
        return session
    }

    suspend fun pause() {
        val session = liveSession.value ?: return
        if (session.sessionStatus != "RUNNING") return
        activityRepository.updatePauseState(session.id, "PAUSED", System.currentTimeMillis())
        _tick.value++
    }

    suspend fun resume() {
        val session = liveSession.value ?: return
        if (session.sessionStatus != "PAUSED") return

        val now = System.currentTimeMillis()
        val pauseStart = session.currentPauseStartedAt ?: return
        val newTotalPausedMs = session.totalPausedMs + (now - pauseStart)

        activityRepository.updatePauseState(session.id, "RUNNING", null)
        activityRepository.updatePauseData(session.id, newTotalPausedMs, session.pauseSegmentsJson)
        _tick.value++
    }

    suspend fun stop(): ActivitySession? {
        val session = liveSession.value ?: return null
        val now = System.currentTimeMillis()
        val finalPauseMs = session.totalPausedMs +
            (if (session.isPaused && session.currentPauseStartedAt != null)
                (now - session.currentPauseStartedAt) else 0L)

        activityRepository.finishSession(session.id, now, finalPauseMs, session.pauseSegmentsJson)
        return session.copy(
            sessionStatus = "FINISHED",
            endAt = now,
            totalPausedMs = finalPauseMs,
            currentPauseStartedAt = null
        )
    }

    private suspend fun forceFinish(session: ActivitySession) {
        val now = System.currentTimeMillis()
        activityRepository.finishSession(
            session.id, now, session.effectivePausedMs(now), session.pauseSegmentsJson
        )
    }

    /** M11: Force-finish the current session (if any) during auto-start. */
    suspend fun forceFinishForAuto() {
        val session = liveSession.value ?: return
        if (session.sessionStatus in setOf("RUNNING", "PAUSED")) {
            val now = System.currentTimeMillis()
            activityRepository.finishSession(
                session.id, now, session.effectivePausedMs(now), session.pauseSegmentsJson
            )
        }
    }

    /** M12.1: Discard the current live session — stop + soft-delete.
     *  The session is treated as if it never happened (deletedAt set).
     *  Only works for auto-started sessions (GEOFENCE_AUTO). */
    suspend fun discardLiveSession(): Boolean {
        val session = liveSession.value ?: return false
        if (session.sourceType != "GEOFENCE_AUTO") return false
        if (session.sessionStatus !in setOf("RUNNING", "PAUSED")) return false
        val now = System.currentTimeMillis()
        activityRepository.finishSession(
            session.id, now, session.effectivePausedMs(now), session.pauseSegmentsJson
        )
        activityRepository.softDelete(session.id, now)
        return true
    }

    // ============================================================
    // M17: Auto-Discard-Mechanik für Geofence-Auto-Sessions
    // ============================================================

    /**
     * M17: Startet eine Auto-Session und plant einen Auto-Discard nach
     * [autoDiscardAfterMs] Millisekunden. Wenn in dieser Zeit kein
     * [refreshAutoDiscard] für den gleichen Geofence kommt, war es ein
     * GPS-Sprung und die Session wird verworfen (stop + softDelete).
     *
     * Bewusst inline (nicht über [start]), weil wir sourceType + den
     * Discard-Job in einem Schritt setzen müssen.
     */
    suspend fun startAutoAndScheduleDiscard(
        activityTypeId: String,
        title: String? = null,
        note: String? = null,
        sourceTriggerId: String? = null,
        geofenceId: String,
        autoDiscardAfterMs: Long = 60_000L
    ): ActivitySession {
        val session = start(
            activityTypeId = activityTypeId,
            title = title,
            note = note,
            sourceType = "GEOFENCE_AUTO",
            sourceTriggerId = sourceTriggerId
        )
        scheduleAutoDiscard(geofenceId, session.id, autoDiscardAfterMs)
        return session
    }

    /**
     * M17: Plant den Auto-Discard-Job. Vorhandener Job für die Geofence
     * wird ersetzt (z. B. wenn der User die LiveActivityCard öffnet
     * und der Manager neu instanziiert wird).
     */
    private fun scheduleAutoDiscard(geofenceId: String, sessionId: String, afterMs: Long) {
        cancelAutoDiscard(geofenceId)
        val job = scope.launch {
            try {
                kotlinx.coroutines.delay(afterMs)
                val current = liveSession.value
                if (current != null && current.isLive && current.id == sessionId) {
                    // M17: GPS-Sprung erkannt. Die Session lief autoDiscardAfterMs
                    // lang, ohne dass der Geofence-Trigger nochmal bestätigt hat.
                    // Wir verwerfen sie (softDelete) — der User hat den Geofence
                    // wahrscheinlich nie wirklich betreten, nur ein GPS-Drift
                    // hat es kurz so aussehen lassen.
                    android.util.Log.d(
                        "LiveActivityManager",
                        "Auto-Discard nach ${afterMs}ms ohne Refresh: session $sessionId (geofence $geofenceId)"
                    )
                    val now = System.currentTimeMillis()
                    activityRepository.finishSession(
                        sessionId, now, current.effectivePausedMs(now), current.pauseSegmentsJson
                    )
                    activityRepository.softDelete(sessionId, now)
                } else {
                    android.util.Log.d(
                        "LiveActivityManager",
                        "Auto-Discard übersprungen: Session $sessionId nicht mehr live (current=${current?.id})"
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // strukturiert
            } catch (e: Exception) {
                android.util.Log.e("LiveActivityManager", "Auto-Discard Job Fehler", e)
            }
        }
        autoDiscardByGeofence[geofenceId] = AutoDiscardEntry(sessionId, job)
    }

    /**
     * M17: Setzt den Auto-Discard-Timer zurück. Wird vom
     * GeofenceTransitionProcessor aufgerufen, wenn ein zweiter Enter-
     * Trigger für den gleichen Geofence ankommt (GPS-Sprung-Schutz
     * bestätigt: User ist wirklich da).
     */
    fun refreshAutoDiscard(geofenceId: String) {
        val entry = autoDiscardByGeofence[geofenceId] ?: return
        val session = liveSession.value
        if (session == null || session.id != entry.sessionId || !session.isLive) {
            // Session ist weg, Discard-Job ist obsolet.
            cancelAutoDiscard(geofenceId)
            return
        }
        // 60s neu starten
        scheduleAutoDiscard(geofenceId, entry.sessionId, AUTO_DISCARD_DEFAULT_MS)
    }

    /**
     * M17: Bricht den Auto-Discard ab. Wird vom
     * GeofenceTransitionProcessor aufgerufen, wenn ein Exit-Trigger
     * für den Geofence ankommt (der Auto-Stop ist die richtige Aktion,
     * kein Discard).
     */
    fun cancelAutoDiscard(geofenceId: String) {
        val entry = autoDiscardByGeofence.remove(geofenceId) ?: return
        entry.job.cancel()
    }

    private fun mapState(session: ActivitySession?): LiveActivityState {
        if (session == null) return LiveActivityState.Idle
        val sourceLabel = if (session.sourceType == "GEOFENCE_AUTO") {
            session.title // Der Geofence-Name ist der Session-Titel bei Auto-Start
        } else null
        return when (session.sessionStatus) {
            "RUNNING" -> LiveActivityState.Running(
                sessionId = session.id,
                title = session.title,
                categoryId = session.categoryId,
                startAt = session.startAt,
                totalPausedMs = session.totalPausedMs,
                note = session.note,
                sourceType = session.sourceType,
                sourceLabel = sourceLabel
            )
            "PAUSED" -> LiveActivityState.Paused(
                sessionId = session.id,
                title = session.title,
                categoryId = session.categoryId,
                startAt = session.startAt,
                totalPausedMs = session.totalPausedMs,
                pauseStartedAt = session.currentPauseStartedAt ?: System.currentTimeMillis(),
                note = session.note,
                sourceType = session.sourceType,
                sourceLabel = sourceLabel
            )
            else -> LiveActivityState.Idle
        }
    }
}

data class RecentActivityType(
    val id: String,
    val title: String,
    val lastUsedAt: Long
)

/**
 * M10: Live-Activity states are now time-source-independent.
 * The UI passes the current `nowMs` from the ticker into the
 * `activeMs(now)` / `totalMs(now)` helpers — so a single tick
 * recomposition makes the displayed time advance by one second.
 */
sealed class LiveActivityState {
    data object Idle : LiveActivityState()

    data class Running(
        val sessionId: String,
        val title: String,
        val categoryId: String?,
        val startAt: Long,
        val totalPausedMs: Long,
        val note: String?,
        val sourceType: String = "LIVE",
        val sourceLabel: String? = null
    ) : LiveActivityState() {
        fun totalMs(now: Long): Long = (now - startAt).coerceAtLeast(0)
        fun activeMs(now: Long): Long = (totalMs(now) - totalPausedMs).coerceAtLeast(0)
        val isPaused: Boolean get() = false
        val isAuto: Boolean get() = sourceType == "GEOFENCE_AUTO"
    }

    data class Paused(
        val sessionId: String,
        val title: String,
        val categoryId: String?,
        val startAt: Long,
        val totalPausedMs: Long,
        val pauseStartedAt: Long,
        val note: String?,
        val sourceType: String = "LIVE",
        val sourceLabel: String? = null
    ) : LiveActivityState() {
        fun totalMs(now: Long): Long = (now - startAt).coerceAtLeast(0)
        fun activeMs(now: Long): Long =
            (totalMs(now) - totalPausedMs - (now - pauseStartedAt)).coerceAtLeast(0)
        fun currentPauseMs(now: Long): Long = (now - pauseStartedAt).coerceAtLeast(0)
        val isPaused: Boolean get() = true
        val isAuto: Boolean get() = sourceType == "GEOFENCE_AUTO"
    }
}

private const val AUTO_DISCARD_DEFAULT_MS = 60_000L
