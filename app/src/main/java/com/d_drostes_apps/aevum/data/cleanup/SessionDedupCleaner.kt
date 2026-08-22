package com.d_drostes_apps.aevum.data.cleanup

import com.d_drostes_apps.aevum.data.model.ActivitySession

/**
 * AEVUM-1: Daten-Aufräumskript — gleichzeitige/duplizierte Aktivitäten deduplizieren.
 *
 * Problem (User-Report): Der Garmin-Schlaf wurde anfangs mehrfach synchronisiert —
 * in manchen Nächten standen dadurch ~100 Stunden Schlaf in der Timeline
 * (mehrere überlappende Sessions derselben Nacht).
 *
 * Diese Logik ist bewusst Android-frei (JVM-Unit-Tests ohne Robolectric) und
 * reine Entscheidungsfindung: Sie bekommt eine Liste von Sessions und liefert
 * die ID-Liste der ZU LÖSCHENDEN Duplikate. Das Löschen selbst übernimmt
 * [CleanupDuplicateSessionsUseCase] (einmalig beim App-Start).
 *
 * Regeln:
 *  1. Gleiche `externalId` (stabile Import-Identität, z.B. "garmin_sleep_<tag>")
 *     = definitiv dieselbe Aktivität → nur die NEUESTE bleibt (auch wenn Garmin
 *     die Zeiten nachträglich so verschoben hat, dass keine Überlappung mehr
 *     besteht — der M18.62/64-Root-Cause-Fall).
 *  2. Zeitliche Überlappung bei gleichem Typ: `a.startAt < b.endAt AND
 *     b.startAt < a.endAt` → die NEUESTE (höchstes createdAt, Tie-Break: höchste
 *     id) bleibt, die älteren werden gelöscht.
 *
 * Schutz (NIEMALS gelöscht — Codebase-Policy, User-Eingriff gewinnt):
 *  - MANUAL-Sessions (M18.51: "Schlaf ist geschützt")
 *  - isUserEdited-Sessions
 *  - Live-Sessions (RUNNING/PAUSED) und Sessions ohne endAt (offen)
 *
 * Hinweis: `activity_session.id` ist eine TEXT-UUID, KEIN Auto-Increment —
 * "zuletzt hinzugefügt" wird daher über `createdAt` bestimmt (nicht über die
 * id-Sortierung wie in einem reinen SQL-Join möglich).
 */
object SessionDedupCleaner {

    /** Quelle, die der User selbst angelegt hat — wird nie angefasst. */
    const val SOURCE_MANUAL = "MANUAL"

    /**
     * True, wenn zwei abgeschlossene Sessions zeitlich überlappen
     * (`a.startAt < b.endAt AND b.startAt < a.endAt`). Offene Sessions
     * (endAt == null) überlappen nie — sie sind ohnehin geschützt.
     */
    fun overlaps(a: ActivitySession, b: ActivitySession): Boolean {
        val aEnd = a.endAt ?: return false
        val bEnd = b.endAt ?: return false
        return a.startAt < bEnd && b.startAt < aEnd
    }

    /** Sessions, die der Cleanup niemals löschen darf. */
    fun isProtected(session: ActivitySession): Boolean =
        session.sourceType == SOURCE_MANUAL ||
            session.isUserEdited ||
            session.isLive ||
            session.endAt == null

    /**
     * Die NEUESTE Session einer Gruppe: höchstes createdAt, Tie-Break höchste
     * id (deterministisch, da UUIDs eindeutig sind).
     */
    fun newest(sessions: List<ActivitySession>): ActivitySession =
        sessions.maxWith(compareBy({ it.createdAt }, { it.id }))

    /**
     * Berechnet die zu löschenden Duplikate aus einer Liste von Sessions.
     *
     * @param sessions Alle nicht-gelöschten Sessions (einmaliger Lookup).
     * @return Die Sessions, die gelöscht werden müssen (die ÄLTEREN Duplikate).
     *         Die jeweils neueste Session jeder Duplikat-Gruppe bleibt erhalten.
     */
    fun duplicatesToDelete(sessions: List<ActivitySession>): List<ActivitySession> {
        val candidates = sessions.filter { !isProtected(it) }
        val deleteIds = LinkedHashSet<String>()

        // Regel 1: Gleiche externalId = definitiv dieselbe Aktivität.
        // Greift auch ohne zeitliche Überlappung (Garmin verschiebt
        // Schlafzeiten nachträglich — der Kern des Duplikat-Bugs).
        candidates
            .filter { !it.externalId.isNullOrBlank() }
            .groupBy { it.externalId }
            .values
            .forEach { group ->
                if (group.size > 1) {
                    val keeper = newest(group)
                    group.filter { it.id != keeper.id }.forEach { deleteIds.add(it.id) }
                }
            }

        // Regel 2: Zeitliche Überlappung bei gleichem Typ.
        // Gruppierung: activityTypeId, Fallback auf categoryId (M18.65-FIX 3:
        // Sessions, deren Type durch die REPLACE-Seed-Kaskade auf NULL gesetzt
        // wurde, aber categoryId="sleep" tragen, gehören zur Schlaf-Gruppe).
        candidates
            .filter { it.id !in deleteIds }
            .groupBy { it.activityTypeId ?: it.categoryId }
            .values
            .forEach { group ->
                if (group.size < 2) return@forEach
                // Älteste zuerst — jede Session, die eine NEUERE überlappt,
                // ist ein Duplikat. Die neueste der Gruppe kann nie markiert
                // werden (keine neuere vorhanden).
                val sorted = group.sortedWith(compareBy({ it.createdAt }, { it.id }))
                for (i in sorted.indices) {
                    val older = sorted[i]
                    if (older.id in deleteIds) continue
                    val overlapsNewer = sorted.drop(i + 1).any { newer ->
                        overlaps(older, newer)
                    }
                    if (overlapsNewer) deleteIds.add(older.id)
                }
            }

        return candidates.filter { it.id in deleteIds }
    }
}
