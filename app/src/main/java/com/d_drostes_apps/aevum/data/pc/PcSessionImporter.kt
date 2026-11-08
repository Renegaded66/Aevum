package com.d_drostes_apps.aevum.data.pc

import com.d_drostes_apps.aevum.data.db.ActivitySessionDao
import com.d_drostes_apps.aevum.data.db.ActivityTypeDao
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Importiert Sessions vom PC-Tracker in die Aevum-Timeline.
 *
 * Leitplanken (docs/PC-TRACKER-PROTOCOL.md, Abschnitt 5):
 *  - Idempotent: externalId = "pc_<Session-UUID des PCs>" — wiederholte Syncs
 *    aktualisieren dieselbe Session statt Duplikate anzulegen.
 *  - Auto-Mapping über den Namen: Für jede PC-Aktivität ("Zocken", "Arbeit" …)
 *    wird ein ActivityType gleichen Namens gesucht; fehlt er, wird er einmalig
 *    angelegt (kein Schema-Update, keine Migration).
 *  - Vom Nutzer bearbeitete Sessions werden beim Re-Sync NICHT überschrieben.
 */
@Singleton
class PcSessionImporter @Inject constructor(
    private val sessionDao: ActivitySessionDao,
    private val typeDao: ActivityTypeDao
) {
    data class Result(
        val inserted: Int,
        val updated: Int,
        val skippedUserEdited: Int,
        val createdTypes: List<String>,
        /** M36: doppelt importierte, überlappende PC-Zeilen, die aufgeräumt wurden. */
        val mergedDuplicates: Int = 0
    )

    suspend fun import(
        deviceName: String,
        sessions: List<PcTrackerClient.PcSession>,
        deviceId: String = "",
        now: Long = System.currentTimeMillis()
    ): Result {
        if (sessions.isEmpty()) {
            return Result(0, 0, 0, emptyList())
        }

        val externalIds = sessions.map { externalIdFor(it.id) }
        val existingByExternal = sessionDao.getByExternalIdsOnce(externalIds)
            .groupBy { it.externalId }

        val allTypes = typeDao.getAll().first().toMutableList()
        val typeCache = HashMap<String, ActivityType>()
        val createdNames = LinkedHashSet<String>()

        var inserted = 0
        var updated = 0
        var skipped = 0

        for (session in sessions) {
            if (session.endAt <= session.startAt) {
                continue
            }
            val externalId = externalIdFor(session.id)
            val type = resolveType(session, allTypes, typeCache, createdNames)
            val title = session.activityName.trim().ifBlank {
                session.program.trim().ifBlank { "PC" }
            }
            val description = describe(session, deviceName)
            val timezone = session.timezoneId.ifBlank { "UTC" }

            // Bei soft-gelöschten Zeilen bleibt die Löschung bestehen (der
            // Nutzer hat die Aufzeichnung bewusst entfernt); der UNIQUE-Index
            // auf external_id verhindert sonst ein erneutes Einfügen.
            val existing = existingByExternal[externalId]
                ?.firstOrNull { it.deletedAt == null }
                ?: existingByExternal[externalId]?.firstOrNull()
            if (existing != null && existing.deletedAt != null) {
                skipped++
            } else if (existing == null) {
                sessionDao.insert(
                    ActivitySession(
                        // M36: Die lokale ID wird aus der externen Identität
                        // abgeleitet. Damit ist der Import auch bei zwei
                        // gleichzeitigen Sync-Läufen eindeutig: Beide schreiben
                        // dieselbe Primärschlüssel-Zeile (REPLACE) statt zweier
                        // Kopien mit zufälligen UUIDs.
                        id = externalId,
                        title = title,
                        categoryId = type.defaultCategoryId,
                        activityTypeId = type.id,
                        startAt = session.startAt,
                        endAt = session.endAt,
                        timezoneId = timezone,
                        description = description,
                        sourceType = SOURCE_TYPE,
                        createdBy = CREATED_BY,
                        externalId = externalId,
                        originDeviceId = deviceId.ifBlank { null },
                        confidence = 1.0f,
                        isUserEdited = false,
                        createdAt = now,
                        updatedAt = now,
                        revision = 1
                    )
                )
                inserted++
            } else if (existing.isUserEdited) {
                // Der Nutzer hat die Session angepasst — Sync lässt sie in Ruhe.
                skipped++
            } else {
                sessionDao.update(
                    existing.copy(
                        title = title,
                        categoryId = type.defaultCategoryId,
                        activityTypeId = type.id,
                        startAt = session.startAt,
                        endAt = session.endAt,
                        timezoneId = timezone,
                        description = description,
                        originDeviceId = deviceId.ifBlank { existing.originDeviceId },
                        updatedBy = CREATED_BY,
                        updatedAt = now,
                        revision = existing.revision + 1
                    )
                )
                updated++
            }
        }

        // M36: Bestands-Duplikate (aus der Zeit vor der Unique-ID bzw. aus
        // parallelen Sync-Läufen) sofort mit aufräumen. Die Aufräum-Logik
        // löscht nur PC-eigene, nicht nutzerbearbeitete, ineinanderliegende
        // Zeilen — nie eine echte, aufeinanderfolgende Aufzeichnung.
        val merged = dedupeStoredSessions()

        return Result(inserted, updated, skipped, createdNames.toList(), merged)
    }

    /**
     * Räumt doppelte PC-Zeilen in der Datenbank auf. Idempotent und billig:
     * läuft nach jedem Import; ohne Duplikate wird nichts gelöscht.
     */
    suspend fun dedupeStoredSessions(): Int {
        val stored = sessionDao.getBySourceTypeOnce(SOURCE_TYPE)
        if (stored.size < 2) return 0
        val duplicates = PcSessionDeduper.duplicatesToDelete(stored)
        if (duplicates.isEmpty()) return 0
        duplicates.map { it.id }.chunked(400).forEach { chunk ->
            sessionDao.hardDeleteByIds(chunk)
        }
        return duplicates.size
    }

    /**
     * Auto-Mapping über den Namen: 1) gleicher Name, 2) gleiche stabile ID
     * ("pc_gaming"), 3) einmalig neu anlegen.
     */
    private suspend fun resolveType(
        session: PcTrackerClient.PcSession,
        allTypes: MutableList<ActivityType>,
        cache: HashMap<String, ActivityType>,
        createdNames: MutableSet<String>
    ): ActivityType {
        val name = session.activityName.trim().ifBlank {
            session.program.trim().ifBlank { "PC" }
        }
        cache[name.lowercase()]?.let { return it }

        allTypes.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
            cache[name.lowercase()] = it
            return it
        }

        val proposedId = "pc_" + session.activityKey.trim().lowercase()
            .ifBlank { "activity_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }
        allTypes.firstOrNull { it.id == proposedId }?.let {
            cache[name.lowercase()] = it
            return it
        }

        val created = ActivityType(
            id = proposedId,
            name = name,
            defaultCategoryId = session.categoryKey.takeIf { it in KNOWN_CATEGORY_IDS },
            isSystem = false,
            icon = session.icon.trim().ifBlank { "•" }
        )
        typeDao.insert(created)
        allTypes.add(created)
        createdNames.add(name)
        cache[name.lowercase()] = created
        return created
    }

    private fun describe(session: PcTrackerClient.PcSession, deviceName: String): String? {
        val program = session.program.trim()
        return when {
            program.isEmpty() -> null
            else -> program
        }
    }

    private fun externalIdFor(pcSessionId: String) = "pc_$pcSessionId"

    companion object {
        const val SOURCE_TYPE = "PC_TRACKER"
        const val CREATED_BY = "PC_IMPORT"

        /** Die stabilen Kategorie-IDs aus dem Aevum-Seed (EnsureDefaultDataUseCase). */
        val KNOWN_CATEGORY_IDS = setOf(
            "work", "sleep", "sport", "learning", "health",
            "leisure", "relationships", "transport", "digital", "household", "unknown"
        )
    }
}
