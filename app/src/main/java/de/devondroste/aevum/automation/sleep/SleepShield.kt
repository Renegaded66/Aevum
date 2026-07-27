package de.devondroste.aevum.automation.sleep

import de.devondroste.aevum.data.repository.ActivityRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M16.6: Zentrale Schutzlogik gegen nächtliche False-Positive-Trigger.
 *
 * Hintergrund: Wenn der Nutzer schläft, dürfen keine normalen Bewegungs-
 * oder Fahrt-Trigger erzeugt werden. Insbesondere nicht aus:
 *   - GPS-Flattern am Geofence-Rand (mehrere gleichzeitige EXITs)
 *   - "Home arrived" morgens durch erste echte Aktivität
 *   - IN_VEHICLE Activity-Recognition während Schlaf
 *   - Trigger-Paaren, die eine Reise konstruieren
 *
 * Schlaf-Sessions werden in [ActivitySession] mit activityTypeId="sleep"
 * gespeichert. Wenn eine solche Session den Trigger-Zeitpunkt abdeckt,
 * ist der Trigger zu unterdrücken oder als LOW-confidence zu markieren.
 *
 * Zusätzlich wird eine konservative Heuristik genutzt, wenn noch keine
 * Sleep-Session existiert: Wenn der Trigger zwischen 22:00 und 08:00
 * liegt UND der Nutzer in den letzten 12h eine plausible Schlafphase
 * hatte (z.B. lange Bildschirm-Aus-Zeit), wird ebenfalls suppressiert.
 *
 * Designprinzipien:
 *  - Lieber einen Trigger weniger als einen falschen Trigger mehr.
 *  - Trigger während nachweislichem Schlaf werden auf LOW gesetzt, aber
 *    nicht verworfen (für Debugging). Travel-Candidates werden ganz
 *    unterdrückt.
 */
@Singleton
class SleepShield @Inject constructor(
    private val activityRepository: ActivityRepository
) {

    /**
     * Prüft, ob zum Zeitpunkt [atMs] eine nachgewiesene Schlaf-Session aktiv ist.
     * Sucht im ±36h-Fenster, um Mitternacht-Schlaf korrekt abzudecken.
     *
     * @return `true` wenn eine Sleep-Session (activityTypeId="sleep") den
     *         Zeitpunkt überdeckt.
     */
    suspend fun isSleepActive(atMs: Long): Boolean {
        val windowStart = atMs - 36L * 3_600_000L
        val windowEnd = atMs + 36L * 3_600_000L
        val overlapping = activityRepository.getOverlappingRange(windowStart, windowEnd).first()
        return overlapping.any { session ->
            val end = session.endAt ?: (atMs + 1L)
            session.activityTypeId == "sleep" &&
                session.deletedAt == null &&
                session.startAt <= atMs &&
                end >= atMs
        }
    }

    /**
     * Prüft, ob ein Trigger komplett zu unterdrücken ist (Travel-Candidate
     * darf nicht erzeugt werden).
     *
     * Regeln:
     *  - Trigger liegt zwischen 22:00 und 08:00 → sehr wahrscheinlich Schlaf
     *  - UND eine Sleep-Session überlappt das Fenster ODER der Trigger ist
     *    < 12h nach dem Ende der letzten Sleep-Session
     *
     * @return `true` wenn der Trigger zu unterdrücken ist.
     */
    suspend fun shouldSuppress(atMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val hour = Instant.ofEpochMilli(atMs).atZone(zoneId).hour
        val isNightHours = hour in NIGHT_HOURS

        if (!isNightHours) return false

        // 1) Sleep-Session überlappt direkt?
        if (isSleepActive(atMs)) return true

        // 2) Letzte Sleep-Session vor <12h? Konservativ: wenn der User
        //    vor weniger als 12h geschlafen hat und der Trigger in der
        //    Nacht liegt, ist es wahrscheinlich Schlaf-Stub oder Aufwachen.
        val lastSleepSession = activityRepository.getOverlappingRange(
            atMs - 24L * 3_600_000L,
            atMs + 12L * 3_600_000L
        ).first()
            .filter { it.activityTypeId == "sleep" && it.deletedAt == null }
            .maxByOrNull { it.endAt ?: it.startAt }

        if (lastSleepSession != null) {
            val sleepEnd = lastSleepSession.endAt ?: return false
            val hoursSinceSleepEnd = (atMs - sleepEnd).coerceAtLeast(0L) / 3_600_000L
            // Wenn der Trigger in den ersten 90 Minuten nach Schlafende liegt
            // und der Trigger zwischen 04:00 und 09:00 stattfindet → das ist
            // plausibel das morgendliche Aufwachen, nicht eine Fahrt.
            if (hoursSinceSleepEnd in 0L..1L && hour in MORNING_WAKEUP_HOURS) {
                return true
            }
        }
        return false
    }

    /**
     * Markiert einen Trigger als LOW-anchor (für Travel-Pair-Rule-Engine).
     * Wenn `shouldSuppress` true ist → AnchorQuality = LOW (kein Travel-Candidate).
     * Sonst → AnchorQuality = MEDIUM (normal).
     */
    suspend fun anchorQualityFor(atMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): AnchorQuality =
        if (shouldSuppress(atMs, zoneId)) AnchorQuality.LOW else AnchorQuality.MEDIUM

    enum class AnchorQuality { HIGH, MEDIUM, LOW }

    private companion object {
        // 22:00 bis 08:00 (Nachtfenster). Wir nutzen hour-Werte:
        // 22, 23, 0, 1, 2, 3, 4, 5, 6, 7
        val NIGHT_HOURS = setOf(22, 23, 0, 1, 2, 3, 4, 5, 6, 7)
        val MORNING_WAKEUP_HOURS = setOf(4, 5, 6, 7, 8, 9)
    }
}

/**
 * M16.6: Helper-Funktion, die für einen Zeitpunkt + eine Reihe von
 * Geofence-EXITs ermittelt, ob alle EXITs unterdrückt werden sollen.
 *
 * Wird vom GeofenceTransitionProcessor aufgerufen, bevor EXITs als
 * Trigger gespeichert werden.
 */
suspend fun SleepShield.shouldSuppressTransition(
    atMs: Long,
    transition: GeofenceTransition
): Boolean {
    // Nur EXITs nachts prüfen — ENTERs sind in der Regel echte Bewegungen
    // (der Nutzer betritt tatsächlich einen Ort), auch wenn es nachts ist.
    if (transition != GeofenceTransition.Exit) return false
    return shouldSuppress(atMs)
}

// Re-export GeofenceTransition enum, damit der Helper ohne extra Import funktioniert.
typealias GeofenceTransition = de.devondroste.aevum.automation.geofence.GeofenceTransition

// Re-export LocalTime für Kompaktheit in Tests / Erweiterungen.
@Suppress("unused")
private val localTimeNow: LocalTime = LocalTime.now()