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
     * M18.64: Stabile externe Identität einer Garmin-Schlaf-Nacht.
     *
     * Garmin liefert KEINE stabile Schlaf-ID über die Bridge (empirisch
     * belegt: die Bridge-Caches enthalten nur Start/Ende/Dauer). Die
     * einzig stabile Semantik ist der AUFWACH-TAG: date=X = Schlaf der
     * Nacht zum Morgen von X. Garmin ändert die Schlafzeiten nachträglich
     * (z.B. 23:46–08:01 → 00:10–08:00), aber die Nacht-Identität bleibt
     * gleich. Diese ID wird in activity_session.external_id persistiert
     * und macht den Import idempotent: Sync 1 legt an, Sync 2..N finden
     * die ID und UPDATEN nur noch.
     */
    fun externalIdForNight(wakeDateIso: String): String = "garmin_sleep_$wakeDateIso"

    /**
     * Primär-Session einer Nacht: die ÄLTESTE (frühestes createdAt).
     * Bei gleichem createdAt die mit der kleinsten ID (deterministisch).
     */
    fun primarySession(sessions: List<ActivitySession>): ActivitySession? =
        sessions.minWithOrNull(
            compareBy({ it.createdAt ?: Long.MAX_VALUE }, { it.id })
        )

    /**
     * M18.64: Findet die Primär-Session einer Nacht über die stabile
     * externalId (falls bereits persistiert). Liefert die älteste
     * (frühestes createdAt) — deterministisch.
     */
    fun primaryByExternalId(sessions: List<ActivitySession>): ActivitySession? =
        primarySession(sessions)

    /**
     * M18.64: Bestands-Bereinigung — Duplikate derselben Nacht, die VOR
     * der externalId-Persistierung entstanden sind (Alt-Bestände ohne
     * externalId). Kriterium: GARMIN_SLEEP_AUTO-Sessions, die das
     * Garmin-Intervall um mehr als [OVERLAP_THRESHOLD_MS] überlappen.
     * Die älteste bleibt (Primär), alle anderen werden soft-deleted.
     *
     * WICHTIG (M18.63-Selbstprüfung): Es werden NUR überlappende
     * Sessions bereinigt — nicht-überlappende GARMIN_SLEEP_AUTO-Sessions
     * im weiten Nachtfenster sind echte andere Schlafereignisse
     * (Mittagsschlaf) und bleiben unberührt.
     */
    fun duplicatesToCleanup(
        nightSessions: List<ActivitySession>,
        sleepStart: Long,
        sleepEnd: Long
    ): List<ActivitySession> {
        val overlapping = overlappingSessions(nightSessions, sleepStart, sleepEnd)
            // M18.103: User-bearbeitete Sessions sind auch für die
            // Duplikat-Bereinigung tabu — sie könnten sonst als
            // "Duplikat" gelöscht werden, obwohl der User sie
            // angefasst hat.
            .filter { it.sourceType == "GARMIN_SLEEP_AUTO" && !it.isUserEdited }
        val primary = primarySession(overlapping) ?: return emptyList()
        return overlapping.filter { it.id != primary.id }
    }

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

    // ──────────────────────────────────────────────────────────────
    // M18.65: "Schlaf steht schon exakt mit diesen Zeiten in der
    // Timeline? → nichts tun. Sonst: vorhandene Activities im Zeitraum
    // löschen und den Schlaf (Activity + Kategorie Schlaf) schreiben."
    // ──────────────────────────────────────────────────────────────

    /** Toleranz: 1 Minute — ein Sync sollte nie mehr als das ändern. */
    const val EXACT_MATCH_TOLERANCE_MS = 60L * 1000

    /**
     * True, wenn die Session exakt den Garmin-Schlafzeiten entspricht
     * (innerhalb [EXACT_MATCH_TOLERANCE_MS]). Ein Sync-Vorgang, der
     * dieselben Daten nochmal liefert, darf dann nichts tun.
     */
    fun matchesExactly(
        session: ActivitySession,
        sleepStart: Long,
        sleepEnd: Long
    ): Boolean {
        val end = session.endAt ?: return false
        return kotlin.math.abs(session.startAt - sleepStart) <= EXACT_MATCH_TOLERANCE_MS &&
            kotlin.math.abs(end - sleepEnd) <= EXACT_MATCH_TOLERANCE_MS
    }

    /**
     * True, wenn eine Session durch eine Garmin-Schlaf-Session ERSETZT
     * werden darf. Garmin-Schlaf ist die Wahrheit (M18.59-Policy: die
     * gewählte Quelle gewinnt) — ersetzt werden:
     *  - bestehende GARMIN_SLEEP_AUTO-Sessions derselben Nacht
     *    (Zeitkorrektur ohne Zeit-Überlappung — Garmin ändert Zeiten
     *    nachträglich und verschiebt sie dabei; M18.62/64-Lektion)
     *  - Screen-Heuristik-Sessions (Bildschirmzeit-Schlaf, andere Quelle)
     * NICHT ersetzt werden: MANUAL-Sessions (User-Eingriff hat Vorrang,
     * M18.51-Policy "Schlaf ist geschützt") und alle Nicht-Schlaf-
     * Aktivitäten (der Sync fasst fremde Activities nie an).
     *
     * @param session       Bestehende Session im Nachtfenster
     * @param sleepStart    Garmin-Schlafbeginn
     * @param sleepEnd      Garmin-Schlafende
     */
    fun isReplaceableBySleep(
        session: ActivitySession,
        sleepStart: Long,
        sleepEnd: Long
    ): Boolean {
        val end = session.endAt ?: return false
        if (session.deletedAt != null) return false
        // Nur Schlaf-Sessions kommen als Ersatzkandidaten in Frage.
        // M18.65-FIX 3: Auch Sessions mit NULL-Type aber categoryId="sleep"
        // (durch REPLACE-Seed-Kaskade auf NULL gesetzt) sind Schlaf.
        if (session.activityTypeId != "sleep" && session.categoryId != "sleep") return false
        // Manuell eingetragener Schlaf wird NIE überschrieben.
        if (session.sourceType == "MANUAL") return false
        // M18.103 (User: "Manchmal ändere ich die Aktivität in der
        // Timeline, falls Garmin den Schlaf falsch aufgezeichnet hat.
        // Beim nächsten Garmin Sync ist wieder die Garmin Zeit da und
        // meine Änderung ist weg. Es soll solange regelmäßig syncen,
        // bis man es bearbeitet, dann erhält es eine Flag und wird
        // nicht mehr verändert"): Sobald der User eine Session über
        // den Editor bearbeitet hat (SaveManualActivityUseCase setzt
        // isUserEdited=true), ist sie für den Sync tabu — weder
        // Zeit-Update noch Ersetzen. Garmin liefert evtl. korrigierte
        // Zeiten, aber der User-Eingriff gewinnt.
        if (session.isUserEdited) return false
        // Garmin-Schlaf derselben Nacht: Zeit-Überlappung ODER gleiche
        // Nacht (Zeitkorrektur, die nicht mehr überlappt).
        if (session.sourceType == "GARMIN_SLEEP_AUTO") {
            return overlappingSessions(listOf(session), sleepStart, sleepEnd).isNotEmpty() ||
                sameSleepNight(session, sleepStart, sleepEnd)
        }
        // Andere Quellen (Screen-Heuristik, Health Connect): nur bei
        // echter Überlappung ersetzen — ein Mittagsschlaf der Heuristik
        // weit weg von der Nacht bleibt stehen.
        return overlappingSessions(listOf(session), sleepStart, sleepEnd).isNotEmpty()
    }

    /**
     * True, wenn die Session in DERSELBEN Nacht liegt wie der
     * Garmin-Schlaf — unabhängig von der Überlappung. Garmin verschiebt
     * Schlafzeiten nachträglich (z.B. 23:46–08:01 → 00:10–08:00); eine
     * GARMIN_SLEEP_AUTO-Session, deren Zeitbereich komplett im
     * 12h-zu-14h-Nachtfenster des Aufwach-Tags liegt, ist dieselbe
     * Nacht. (Mittagsschlaf liegt nicht in diesem Fenster.)
     */
    fun sameSleepNight(
        session: ActivitySession,
        sleepStart: Long,
        sleepEnd: Long
    ): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        // Aufwach-Tag aus dem Garmin-Ende ableiten (date=X = Nacht zum
        // Morgen von X; Ende liegt morgens zwischen 00:00 und 14:00).
        val wakeDay = java.time.Instant.ofEpochMilli(sleepEnd).atZone(zone).toLocalDate()
        val nightStart = wakeDay.atStartOfDay(zone).minusHours(12).toInstant().toEpochMilli()
        val nightEnd = wakeDay.atStartOfDay(zone).plusHours(14).toInstant().toEpochMilli()
        val end = session.endAt ?: return false
        return session.startAt >= nightStart && end <= nightEnd
    }
}
