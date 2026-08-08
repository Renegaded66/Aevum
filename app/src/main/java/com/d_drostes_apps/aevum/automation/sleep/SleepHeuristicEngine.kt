package com.d_drostes_apps.aevum.automation.sleep

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import com.d_drostes_apps.aevum.automation.model.AutomationConstants
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.domain.automation.ReviewCandidateUseCase
import com.d_drostes_apps.aevum.domain.automation.SAFE_CONFIDENCE_THRESHOLD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M13: Sleep Heuristic Engine.
 *
 * Erzeugt Schlaf-Candidates aus Screen On/Off Mustern — auch ohne Health Connect.
 *
 * Heuristik (konservativ, niedrige Confidence = User muss bestätigen):
 *  - Schlaf-Start: erstes Screen-OFF zwischen 20:00 und 02:00
 *  - Schlaf-Ende: erstes Screen-ON (oder USER_PRESENT) am nächsten Morgen bis 12:00
 *  - Dauer muss zwischen 3 und 14 Stunden liegen
 *  - Mehr als 2 kurze Screen-ON (< 5 min) während der Nacht → niedrigere Confidence
 *
 * Pro Nacht wird maximal 1 Candidate erzeugt (über externalId dedupliziert).
 */
@Singleton
class SleepHeuristicEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val screenEventRepository: ScreenEventRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val activityRepository: ActivityRepository,
    private val reviewCandidateUseCase: ReviewCandidateUseCase,
    // M18.45-FIX: UsageStats-Wake-Korrektur (Android 14+ Broadcast-Limit)
    private val usageWakeDetector: UsageWakeDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false

    fun init(context: Context) {
        if (!isInitialized) {
            screenEventRepository.init(context)
            isInitialized = true
        }
    }

    /**
     * Analysiert die letzte Nacht und erzeugt ggf. einen Candidate.
     * Wird vom ScreenEventReceiver nach jedem ON/OFF getriggert.
     *
     * M16: Statt nur das letzte OFF→ON zu betrachten, suchen wir nun die
     * LÄNGSTE OFF-Periode, die in den Schlaf-Fenstern liegt. Grund: bei
     * kurzem nächtlichen Aufstehen (z. B. 3:00 Uhr kurz Handy an) hat
     * die alte Logik das OFF der kurzen Wach-Phase als Schlaf-Start
     * interpretiert, obwohl die eigentliche Schlaf-Periode viel früher
     * begann. Die längste zusammenhängende OFF-Periode ist das robuste
     * Signal für die Hauptschlaf-Phase.
     */
    suspend fun analyzeLatest() {
        if (!isInitialized) init(appContext)
        val events = screenEventRepository.readAll()
        if (events.size < 2) return

        val zoneId = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val sortedEvents = events.sortedBy { it.timestamp }

        // M16.2: Statt das erste OFF mit dem nächsten ON zu paaren (was bei
        // verpassten kurzen ON-Events den Schlaf zu früh ansetzt), nehmen wir
        // für jedes ON das ZULETZT davor liegende OFF. Grund: Wenn der User
        // um 21:30 den Screen ausschaltet, um 23:30 kurz aktiv ist (aber das
        // ON-Event nicht erfasst wird — OEM-Suppression), und um 23:35 wieder
        // ausschaltet, sieht die alte Logik nur OFF@21:30 → ON@10:00 und
        // setzt den Schlafbeginn auf 21:30. Mit der neuen Logik wird das
        // letzte OFF vor dem Morgen-ON genommen (23:35), was realistischer ist.
        //
        // M16.7: WICHTIG — wir setzen `lastOff = null` ERST, wenn das Pair
        // durch den Morgen-Fenster-Filter (onInMorningWindow) akzeptiert
        // wurde. Vorher hat der Code `lastOff = null` schon beim Finden
        // eines beliebigen ON gesetzt — das führte dazu, dass ein nächtlicher
        // Weckruf um 02:00 (verworfen durch onHour in 4..11) das 23:30-OFF
        // "verbraucht" hat, sodass das echte morgendliche ON um 08:00 ohne
        // Pair dastand → KEIN Schlaf erkannt. Jetzt: Pair nur verbrauchen,
        // wenn es den Morgen-Filter überlebt.
        val offOnPairs = mutableListOf<Pair<ScreenEvent, ScreenEvent>>()
        var lastOff: ScreenEvent? = null
        for (event in sortedEvents) {
            if (event.type == "OFF") {
                lastOff = event
            } else if (event.type == "ON" || event.type == "UNLOCK") {
                val currentOff = lastOff
                if (currentOff != null) {
                    val offHour = Instant.ofEpochMilli(currentOff.timestamp).atZone(zoneId).hour
                    val onHour = Instant.ofEpochMilli(event.timestamp).atZone(zoneId).hour
                    val offInSleepWindow = offHour >= 20 || offHour < 2
                    val onInMorningWindow = onHour in 4..11
                    // Pair NUR dann als verbraucht markieren, wenn es die
                    // Schlaf-Filter überlebt. Sonst: lastOff behalten für
                    // nachfolgende ON-Events.
                    if (offInSleepWindow && onInMorningWindow) {
                        offOnPairs.add(currentOff to event)
                        lastOff = null
                    }
                    // Wenn Filter nicht passt: lastOff bleibt erhalten, das
                    // nächste ON bekommt eine neue Chance.
                }
            }
        }
        if (offOnPairs.isEmpty()) return

        // M16.3: Bevorzuge das längste Paar, aber bestimme die Wake-Time
        // aus dem priorisierten Wake-Event (UNLOCK > ON-BROADCAST > LIFECYCLE).
        // Damit wird die Aufwachzeit aus der echten ersten Nutzung bestimmt,
        // nicht aus dem Zeitpunkt, an dem die App geöffnet wurde.
        val validSleepPairs = offOnPairs.mapNotNull { (off, on) ->
            val offLocal = Instant.ofEpochMilli(off.timestamp).atZone(zoneId).toLocalTime()
            val offHour = offLocal.hour
            val onLocal = Instant.ofEpochMilli(on.timestamp).atZone(zoneId).toLocalTime()
            val onHour = onLocal.hour
            val onDate = Instant.ofEpochMilli(on.timestamp).atZone(zoneId).toLocalDate()

            val offInSleepWindow = offHour >= 20 || offHour < 2
            val onInMorningWindow = onHour in 4..11
            if (!offInSleepWindow || !onInMorningWindow) return@mapNotNull null

            val durationMs = on.timestamp - off.timestamp
            val hours = durationMs / 3_600_000.0
            if (hours < 3.0 || hours > 14.0) return@mapNotNull null

            Triple(off, on, onDate)
        }
        if (validSleepPairs.isEmpty()) return

        // Nimm das längste gültige Paar — das ist die Hauptschlaf-Phase.
        val (offEvent, onEvent, onDate) = validSleepPairs.maxBy { it.second.timestamp - it.first.timestamp }

        // M16.3: Wake-Time aus dem semantisch besten ON/UNLOCK-Event im
        // Zeitfenster [offEvent.timestamp, onEvent.timestamp + 30min]. Wenn
        // ein echter USER_PRESENT/UNLOCK-Broadcast im Fenster liegt, wird
        // dieser Zeitpunkt genutzt — NICHT der App-Open-Zeitpunkt aus dem
        // Lifecycle-Fallback.
        val wakeCandidates = sortedEvents.filter {
            val isOnLike = it.type == "ON" || it.type == "UNLOCK"
            isOnLike && it.timestamp in offEvent.timestamp..(onEvent.timestamp + 30L * 60 * 1000)
        }
        val resolvedWakeMs = prioritizeWakeTime(wakeCandidates) ?: onEvent.timestamp

        // M18.45-FIX: UsageStats-Wake-Korrektur — siehe
        // SleepFusionEngine.detectScreenSleepWindow (Android 14+ liefert
        // SCREEN_ON nicht mehr an Hintergrund-Apps).
        val usageWake = usageWakeDetector.firstUsageSince(offEvent.timestamp)
        val finalWakeMs = if (usageWake != null && usageWake < resolvedWakeMs) {
            android.util.Log.d("SleepHeuristicEngine", "Wake-Korrektur via UsageStats: $resolvedWakeMs → $usageWake")
            usageWake
        } else {
            resolvedWakeMs
        }

        // M16.3: Dauer und Confidence aus dem resolvedWakeMs (priorisierte Wake-Time),
        // nicht aus dem naiven onEvent.timestamp. Damit passt die Heuristik
        // zur semantisch korrekten Aufwachzeit.
        val durationMs = finalWakeMs - offEvent.timestamp
        val hours = durationMs / 3_600_000.0

        // Dedup: externalId = "screen_sleep_<dateOfOn>"
        val externalId = "screen_sleep_${onDate}"
        // M16.5: Dedup gegen ALLE Candidates (PENDING + ACCEPTED + DISMISSED)
        // UND gegen bestehende Sleep-Sessions — als atomare, breite Query.
        // Hintergrund: Wenn der User den Schlaf bereits akzeptiert hat,
        // darf er nicht ein zweites Mal vorgeschlagen werden.
        // Wir laden alle Sleep-relevanten Candidates in einem Fenster von
        // ±24h um den erkannten Schlaf herum (Mitternacht-Sessions
        // überschreiten den 12h-Bereich, daher breiter).
        val candidateWindowStart = offEvent.timestamp - 24L * 3_600_000L
        val candidateWindowEnd = finalWakeMs + 24L * 3_600_000L
        val existingCandidatesInWindow = candidateRepository.getByDateRange(
            candidateWindowStart,
            candidateWindowEnd
        ).first().filter { it.activityTypeId == "sleep" }

        // 1) Source-Candidate-ID-Dedup: dieselbe externe ID bereits da?
        if (existingCandidatesInWindow.any { it.sourceCandidateId == externalId }) return

        // 2) Zeitraum-Dedup mit Toleranz (60 min): Wenn bereits ein Sleep-Candidate
        // existiert, dessen startAt innerhalb ±60min des erkannten Schlafs liegt,
        // wird kein neuer erzeugt. Das fängt Race-Conditions und Mehrfachläufe ab.
        val overlapToleranceMs = 60L * 60 * 1000
        val hasNearbySleepCandidate = existingCandidatesInWindow.any { existing ->
            // 60min-Toleranz am Start oder am Ende reicht für "dieselbe Nacht"
            val sameStart = kotlin.math.abs(existing.startAt - offEvent.timestamp) < overlapToleranceMs
            val sameEnd = kotlin.math.abs(existing.endAt - finalWakeMs) < overlapToleranceMs
            sameStart || sameEnd
        }
        if (hasNearbySleepCandidate) {
            android.util.Log.d(
                "SleepHeuristicEngine",
                "Bereits Sleep-Candidate im ±60min-Fenster — skip (externalId=$externalId)"
            )
            return
        }

        // 3) Session-Dedup: wenn bereits eine echte Sleep-Session im Fenster
        // existiert (≥30 min Überlappung), keinen neuen Candidate anlegen.
        //
        // M18.48-FIX (User: "Ich habe ein Ziel 8h Schlaf pro Nacht. Es wurde
        // fälschlich zweimal aufgezeichnet ... die eine habe ich gelöscht,
        // aber beim Ziel steht weiterhin 200%"): Vorher wurde hier mit
        // `deletedAt == null` gefiltert. Wenn der User eine doppelte
        // Schlaf-Session löschte, war sie für den Dedup unsichtbar — der
        // nächtliche Auto-Tracker erzeugte sie am nächsten Morgen erneut,
        // und das Ziel sprang zurück auf 200%. Jetzt zählt jede bereits
        // aufgezeichnete Nacht (auch eine vom User gelöschte) als "diese
        // Nacht wurde schon erfasst" — Aevum rekonstruiert gelöschte
        // Schlaf-Einträge nicht mehr.
        val existingSleepSessions = activityRepository.getOverlappingRange(
            candidateWindowStart,
            candidateWindowEnd
        ).first().filter { it.activityTypeId == "sleep" }
        val hasOverlap = existingSleepSessions.any { existing ->
            val overlapMs = minOf(finalWakeMs, existing.endAt ?: Long.MAX_VALUE) -
                    maxOf(offEvent.timestamp, existing.startAt)
            overlapMs > 30L * 60 * 1000
        }
        if (hasOverlap) {
            android.util.Log.d("SleepHeuristicEngine", "Bereits eine Sleep-Session im Fenster — skip")
            return
        }

        // Heuristic 4: Confidence — base 0.55, +0.1 wenn 7-9h, -0.2 wenn OFF/ON am Rand
        val offHour = Instant.ofEpochMilli(offEvent.timestamp).atZone(zoneId).hour
        val onHour = Instant.ofEpochMilli(finalWakeMs).atZone(zoneId).hour
        val confidence = when {
            hours in 6.0..9.5 -> 0.65f
            hours in 4.0..10.0 -> 0.58f
            else -> 0.50f
        }.let { base ->
            when {
                offHour in 21..23 && onHour in 6..9 -> base + 0.05f
                offHour in 0..1 || onHour in 10..11 -> base - 0.05f
                else -> base
            }.coerceIn(0.40f, 0.75f)
        }

        val durationHours = hours.toInt()
        val durationMinutes = ((hours - durationHours) * 60).toInt()
        val durationStr = if (durationMinutes > 0) "${durationHours}h ${durationMinutes}min" else "${durationHours}h"
        val title = "Schlaf erkannt ($durationStr)"

        // M16: Verständliche Begründung statt technischer Kryptik.
        // M16.3: Nutze finalWakeMs statt onEvent.timestamp, damit die
        // Begründung zur tatsächlichen Aufwachzeit passt.
        val reason = buildSleepReason(offEvent, offEvent.timestamp, finalWakeMs, zoneId, hours)

        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = title,
            suggestedCategoryId = "sleep",
            activityTypeId = "sleep",
            startAt = offEvent.timestamp,
            endAt = finalWakeMs,
            confidence = confidence,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = reason,
            createdBy = "SCREEN_HEURISTIC_V1",
            createdAt = now,
            sourceCandidateId = externalId
        )
        candidateRepository.insert(candidate)

        // M18.11: IMMER direkt eintragen — kein Vorschlag mehr.
        //
        // Vorher: Auto-Accept nur bei Confidence >= 0.70. Die Screen-
        // Heuristik erzeugt aber Confidence 0.50-0.75 — bei 7h Schlaf mit
        // Rand-Abzug (z.B. 0.60) blieb der Schlaf als Vorschlag in der
        // Review-Inbox hängen. Genau das war "letzte Nacht hat die
        // Schlafaufzeichnung nicht geklappt": Der User sah keinen
        // automatischen Eintrag.
        //
        // Warum ist der Screen-Pfad sicher genug für Direkt-Eintrag?
        //   - OFF nach 20:00 + ON zwischen 04:00-11:00 ist ein sehr
        //     starkes Signal (Bildschirm aus = schläft)
        //   - Der User hat es explizit so gewünscht: "kein Vorschlag,
        //     sondern wirklich direkt eingetragen"
        //   - Die Session ist als HEALTH_SLEEP_AUTO markiert und kann in
        //     der Timeline jederzeit bearbeitet/gelöscht werden
        val result = reviewCandidateUseCase.acceptAuto(listOf(candidate))
        android.util.Log.d(
            "SleepHeuristicEngine",
            "Direkt eingetragen: ${result.accepted} von 1 (Confidence=$confidence, " +
                    "Window=${formatHm(offEvent.timestamp, zoneId)}–${formatHm(finalWakeMs, zoneId)})"
        )
    }

    /**
     * M16: Baut eine verständliche, nicht-technische Begründung für den
     * Schlaf-Vorschlag. Der User sieht in der Review-Inbox:
     *   "Keine Nutzung zwischen 23:30 und 08:00 (8h 30min). Bildschirm aus + Ruhephase erkannt."
     */
    private fun buildSleepReason(
        @Suppress("UNUSED_PARAMETER") offEvent: ScreenEvent,
        offTs: Long,
        wakeMs: Long,
        zoneId: ZoneId,
        hours: Double
    ): String {
        val offStr = formatHm(offTs, zoneId)
        val onStr = formatHm(wakeMs, zoneId)
        val h = hours.toInt()
        val m = ((hours - h) * 60).toInt()
        val durationStr = if (m > 0) "${h}h ${m}min" else "${h}h"
        return "Keine Nutzung zwischen $offStr und $onStr ($durationStr). " +
               "Bildschirm aus + Ruhephase erkannt. " +
               "Morgendliches Entsperren beendet die Schlaf-Phase."
    }

    /**
     * P4: Force-analyze for a given date (e.g. from a "Schlaf vorschlagen" button).
     * Returns the created candidate, or null if no valid pattern was found.
     */
    suspend fun analyzeForDate(date: LocalDate): ActivityCandidate? {
        if (!isInitialized) init(appContext)
        val zoneId = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val events = screenEventRepository.readSince(dayStart - 6 * 3_600_000L)
            .filter { it.timestamp in dayStart - 6 * 3_600_000L..dayEnd + 6 * 3_600_000L }
            .sortedBy { it.timestamp }
        if (events.size < 2) return null

        val externalId = "screen_sleep_manual_${date}_${System.currentTimeMillis()}"
        val title = "Schlaf vorgeschlagen"

        val durationMs = 8L * 3_600_000 // default fallback
        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = title,
            suggestedCategoryId = "sleep",
            activityTypeId = "sleep",
            startAt = dayStart + 23 * 3_600_000L,
            endAt = dayStart + 23 * 3_600_000L + durationMs,
            confidence = 0.50f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Manuelle Erkennung aus Bildschirm-Daten. Bitte Zeiten anpassen.",
            createdBy = "SCREEN_HEURISTIC_V1",
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = externalId
        )
        candidateRepository.insert(candidate)
        return candidate
    }

    private fun formatHm(ts: Long, zone: ZoneId): String =
        "%02d:%02d".format(
            Instant.ofEpochMilli(ts).atZone(zone).hour,
            Instant.ofEpochMilli(ts).atZone(zone).minute
        )

    /**
     * M12.1: Status-Informationen für den UI-Dialog.
     * - letzte Bildschirm-Events
     * - geschätzter Schlaf (aus den letzten verfügbaren ON/OFF-Punkten)
     * - Zeitpunkt des zuletzt erzeugten Candidates
     */
    suspend fun getStatus(): SleepHeuristicStatus {
        if (!isInitialized) init(appContext)
        val events = screenEventRepository.readAll().sortedBy { it.timestamp }
        val zoneId = ZoneId.systemDefault()

        val lastOff = events.lastOrNull { it.type == "OFF" }
        val lastOn = events.lastOrNull { it.type == "ON" || it.type == "UNLOCK" }

        val estimatedStart: Long? = lastOff?.timestamp
        val estimatedEnd: Long? = lastOn?.timestamp

        val confidence: Float? = when {
            lastOff == null || lastOn == null -> null
            lastOn.timestamp <= lastOff.timestamp -> null
            else -> {
                val hours = (lastOn.timestamp - lastOff.timestamp) / 3_600_000.0
                val offHour = Instant.ofEpochMilli(lastOff.timestamp).atZone(zoneId).hour
                val onHour = Instant.ofEpochMilli(lastOn.timestamp).atZone(zoneId).hour
                val base = when {
                    hours in 6.0..9.5 -> 0.65
                    hours in 4.0..10.0 -> 0.58
                    else -> 0.50
                }
                val adj = when {
                    offHour in 21..23 && onHour in 6..9 -> 0.05
                    offHour in 0..1 || onHour in 10..11 -> -0.05
                    else -> 0.0
                }
                (base + adj).toFloat().coerceIn(0.40f, 0.75f)
            }
        }

        val lastCandidate = candidateRepository.getByStatus(AutomationConstants.CANDIDATE_STATUS_PENDING)
            .first()
            .firstOrNull { it.activityTypeId == "sleep" || it.createdBy == "SCREEN_HEURISTIC_V1" }

        return SleepHeuristicStatus(
            eventCount = events.size,
            lastScreenOff = lastOff?.timestamp,
            lastScreenOn = lastOn?.timestamp,
            estimatedSleepStart = estimatedStart,
            estimatedSleepEnd = estimatedEnd,
            estimatedConfidence = confidence,
            lastCandidateCreatedAt = lastCandidate?.createdAt,
            lastCandidateReason = lastCandidate?.reason
        )
    }
}

/**
 * M12.1: UI-Modell für den Status-Dialog.
 */
data class SleepHeuristicStatus(
    val eventCount: Int,
    val lastScreenOff: Long?,
    val lastScreenOn: Long?,
    val estimatedSleepStart: Long?,
    val estimatedSleepEnd: Long?,
    val estimatedConfidence: Float?,
    val lastCandidateCreatedAt: Long?,
    val lastCandidateReason: String?
)
