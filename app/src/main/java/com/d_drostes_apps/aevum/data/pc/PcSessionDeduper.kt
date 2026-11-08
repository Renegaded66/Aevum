package com.d_drostes_apps.aevum.data.pc

import com.d_drostes_apps.aevum.data.model.ActivitySession

/**
 * M36: Erkennt doppelt importierte PC-Aufzeichnungen.
 *
 * Der PC liefert pro Aufzeichnung eine stabile ID (`externalId = "pc_<id>"`).
 * Zwei parallele Sync-Läufe konnten jedoch bisher dieselbe Aufzeichnung
 * gleichzeitig importieren: Beide sahen „noch nicht vorhanden" und legten
 * je eine eigene Zeile mit eigener lokaler UUID an. Stand der PC beim ersten
 * Lauf noch bei 21:00 und beim zweiten bereits bei 22:00, entstanden zwei
 * überlappende Aufzeichnungen (20–21 und 20–22) — genau der gemeldete Fall.
 *
 * Diese Logik ist bewusst Android-frei und rein entscheidend (JVM-testbar):
 * Sie bekommt alle nicht gelöschten Sessions einer Quelle und liefert die
 * IDs der zu löschenden Duplikate. Gelöscht wird über den DAO.
 *
 * Regeln (nur [PcSessionImporter.SOURCE_TYPE], nie nutzerbearbeitete Sessions):
 *  1. Gleiche `externalId` = dieselbe PC-Aufzeichnung. Es bleibt die neueste
 *     Zeile (höchstes `createdAt`, Tie-Break höchste ID) — unabhängig davon,
 *     ob der PC die Zeiten nachträglich verlängert hat.
 *  2. Verschiedene `externalId`, aber eine Aufzeichnung liegt vollständig in
 *     der anderen (Start ± [START_TOLERANCE_MS], Ende ± [END_TOLERANCE_MS])
 *     und beide gehören zur selben Aktivität — oder starten nahezu gleich.
 *     Dann ist die kürzere Zeile der Duplikat-Rest und wird gelöscht.
 *
 * Schwellen bewusst klein: echte aufeinanderfolgende Sessions (auch mit
 * kurzer Pause) liegen nie ineinander; ein PC zeichnet immer nur eine
 * Aktivität gleichzeitig auf.
 */
object PcSessionDeduper {

    /** Start-Zeitpunkte gelten bis zu 2 Minuten Abweichung als „derselbe Start". */
    const val START_TOLERANCE_MS = 120_000L

    /** End-Zeitpunkte dürfen bis zu 2 Minuten abweichen (Sync-Kante). */
    const val END_TOLERANCE_MS = 120_000L

    fun duplicatesToDelete(sessions: List<ActivitySession>): List<ActivitySession> {
        val candidates = sessions.filter {
            it.sourceType == PcSessionImporter.SOURCE_TYPE &&
                it.deletedAt == null &&
                it.endAt != null &&
                it.endAt!! > it.startAt &&
                !it.isUserEdited &&
                !it.isLive
        }
        if (candidates.size < 2) return emptyList()

        val deleteIds = LinkedHashSet<String>()

        // Regel 1: gleiche externe Identität.
        candidates
            .filter { !it.externalId.isNullOrBlank() }
            .groupBy { it.externalId }
            .values
            .forEach { group ->
                if (group.size > 1) {
                    val keeper = group.maxWith(compareBy({ it.createdAt }, { it.id }))
                    group.filter { it.id != keeper.id }.forEach { deleteIds.add(it.id) }
                }
            }

        // Regel 2: ineinanderliegende Aufzeichnungen.
        val remaining = candidates
            .filter { it.id !in deleteIds }
            .sortedBy { it.startAt }

        for (i in remaining.indices) {
            val older = remaining[i]
            if (older.id in deleteIds) continue
            val olderEnd = older.endAt ?: continue

            for (j in i + 1 until remaining.size) {
                val newer = remaining[j]
                if (newer.startAt - older.startAt > START_TOLERANCE_MS) break
                if (newer.id in deleteIds) continue
                val newerEnd = newer.endAt ?: continue

                val sameActivity = older.activityTypeId != null &&
                    older.activityTypeId == newer.activityTypeId
                val sameStart = kotlin.math.abs(older.startAt - newer.startAt) <= START_TOLERANCE_MS
                if (!sameActivity && !sameStart) continue

                val containsNewer = older.startAt <= newer.startAt + START_TOLERANCE_MS &&
                    olderEnd >= newerEnd - END_TOLERANCE_MS
                val newerContainsOlder = newer.startAt <= older.startAt + START_TOLERANCE_MS &&
                    newerEnd >= olderEnd - END_TOLERANCE_MS
                if (!containsNewer && !newerContainsOlder) continue

                val olderDuration = olderEnd - older.startAt
                val newerDuration = newerEnd - newer.startAt
                val shorter = if (olderDuration < newerDuration) older else newer
                deleteIds.add(shorter.id)
                if (shorter.id == older.id) break
            }
        }

        return candidates.filter { it.id in deleteIds }
    }
}
