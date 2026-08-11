package com.d_drostes_apps.aevum.automation.garmin

import com.d_drostes_apps.aevum.data.model.ActivitySession

/**
 * M18.63: Pure Kotlin-Dedup-Logik für den Garmin-Schlaf-Import.
 *
 * WICHTIG: Diese Logik ist bewusst Android-frei (JVM-Unit-Tests ohne
 * Robolectric). Der GarminSyncWorker nutzt sie als Single Source of
 * Truth — die Tests hier sind die Regression-Absicherung gegen das
 * Duplikat-Problem ("ein Schlaf wird ~10x synchronisiert, überlappend").
 *
 * Root-Cause-Hintergrund (empirisch belegt über Bridge-Caches):
 * Garmin ändert dieselbe Nacht NACH dem Sync mehrfach nachträglich
 * (z.B. 23:46–08:01 → 00:10–08:00). Ein Dedup, der auf der exakten
 * Zeit oder einem engen endAt-Fenster (00:00–14:00) basiert, verfehlt
 * die Session nach einer solchen Änderung und legt ein Duplikat an.
 * Die Lösung: Überlappungs-basiert erkennen (Toleranz 30 Min), die
 * älteste Session der Nacht als Primär-Session behalten und Updates
 * darauf anwenden.
 */
object GarminSleepDedup {

    /** Überlappungstoleranz gegen Garmins nachträgliche Zeitänderungen. */
    const val OVERLAP_THRESHOLD_MS = 30L * 60 * 1000

    /**
     * Findet alle Sleep-Sessions, die das Schlaf-Intervall um mehr als
     * [OVERLAP_THRESHOLD_MS] überlappen.
     *
     * @param sessions   Alle nicht-gelöschten Sleep-Sessions im Nacht-Fenster
     *                   (bereits via getOverlappingRange geladen)
     * @param sleepStart Garmin-Schlafbeginn (GMT ms)
     * @param sleepEnd   Garmin-Schlafende (GMT ms)
     */
    fun overlappingSessions(
        sessions: List<ActivitySession>,
        sleepStart: Long,
        sleepEnd: Long
    ): List<ActivitySession> = sessions.filter { s ->
        val sEnd = s.endAt ?: return@filter false
        val overlap = minOf(sEnd, sleepEnd) - maxOf(s.startAt, sleepStart)
        overlap > OVERLAP_THRESHOLD_MS
    }

    /**
     * Primär-Session einer Nacht: die ÄLTESTE (frühestes createdAt).
     * Bei gleichem createdAt die mit der kleinsten ID (deterministisch).
     */
    fun primarySession(sessions: List<ActivitySession>): ActivitySession? =
        sessions.minWithOrNull(
            compareBy({ it.createdAt ?: Long.MAX_VALUE }, { it.id })
        )

    /**
     * Duplikate, die bereinigt werden müssen: alle außer der Primär-Session.
     * Sortiert nach createdAt (älteste zuerst), damit das Drop(1)-Muster
     * deterministisch ist.
     */
    fun duplicateSessions(sessions: List<ActivitySession>): List<ActivitySession> {
        val primary = primarySession(sessions) ?: return emptyList()
        return sessions
            .filter { it.id != primary.id }
            .sortedBy { it.createdAt ?: Long.MAX_VALUE }
    }
}
