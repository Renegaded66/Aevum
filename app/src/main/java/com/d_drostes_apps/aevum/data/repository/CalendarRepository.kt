package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.CalendarEventCacheDao
import com.d_drostes_apps.aevum.data.db.CalendarRuleDao
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarRule
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.129: Repository für die Kalender-Integration.
 *
 * Zwei getrennte Zuständigkeiten, bewusst in EINEM Repository (die
 * Aufrufer — Sync-Worker, Auto-Run-Worker, ViewModels — brauchen fast
 * immer beide):
 *
 *  1. REGELN: vom Nutzer gepflegt, überleben alles.
 *  2. TERMIN-CACHE: reiner Spiegel des Kalenders, wird bei jedem Sync
 *     erneuert und ist jederzeit verwerfbar.
 */
interface CalendarRepository {
    // ── Regeln ──────────────────────────────────────────────────────
    fun getRules(): Flow<List<CalendarRule>>
    suspend fun getRulesOnce(): List<CalendarRule>
    suspend fun getEnabledRulesOnce(): List<CalendarRule>
    suspend fun getRuleByIdOnce(id: String): CalendarRule?
    fun ruleCount(): Flow<Int>
    suspend fun upsertRule(rule: CalendarRule)
    suspend fun setRuleEnabled(id: String, enabled: Boolean)
    suspend fun deleteRule(id: String)

    // ── Termin-Cache ────────────────────────────────────────────────
    fun getEventsInWindow(from: Long, to: Long): Flow<List<CalendarEventCache>>
    /** M18.129: alle gecachten Termine (Cache ist auf das Sync-Fenster begrenzt). */
    fun getEvents(): Flow<List<CalendarEventCache>>
    suspend fun getEventsInWindowOnce(from: Long, to: Long): List<CalendarEventCache>
    suspend fun getLastSyncedAt(): Long?
    suspend fun eventCount(): Int
    fun eventCountFlow(): Flow<Int>
    /** Ersetzt den Cache-Inhalt für das Sync-Fenster (idempotent per UPSERT). */
    suspend fun replaceWindow(events: List<CalendarEventCache>, pruneBefore: Long)
    suspend fun clearEvents()
    /** Markiert den Sync-Zeitpunkt (auch bei 0 Terminen — "erfolgreich leer"). */
    suspend fun markSynced(at: Long)
}

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val ruleDao: CalendarRuleDao,
    private val eventDao: CalendarEventCacheDao,
    private val settingsDao: AutomationSettingsDao
) : CalendarRepository {

    override fun getRules(): Flow<List<CalendarRule>> = ruleDao.getAll()

    override suspend fun getRulesOnce(): List<CalendarRule> = ruleDao.getAllOnce()

    override suspend fun getEnabledRulesOnce(): List<CalendarRule> = ruleDao.getEnabledOnce()

    override suspend fun getRuleByIdOnce(id: String): CalendarRule? = ruleDao.getByIdOnce(id)

    override fun ruleCount(): Flow<Int> = ruleDao.count()

    override suspend fun upsertRule(rule: CalendarRule) = ruleDao.upsert(rule)

    override suspend fun setRuleEnabled(id: String, enabled: Boolean) =
        ruleDao.setEnabled(id, enabled, System.currentTimeMillis())

    override suspend fun deleteRule(id: String) = ruleDao.delete(id)

    override fun getEventsInWindow(from: Long, to: Long): Flow<List<CalendarEventCache>> =
        eventDao.getInWindow(from, to)

    override fun getEvents(): Flow<List<CalendarEventCache>> = eventDao.getAll()

    override suspend fun getEventsInWindowOnce(from: Long, to: Long): List<CalendarEventCache> =
        eventDao.getInWindowOnce(from, to)

    override suspend fun getLastSyncedAt(): Long? = eventDao.getLastSyncedAt()

    override suspend fun eventCount(): Int = eventDao.count()

    override fun eventCountFlow(): Flow<Int> = eventDao.countFlow()

    /**
     * M18.129: Cache-Aktualisierung.
     *
     * Reihenfolge ist wichtig: erst die abgelaufenen Termine entfernen,
     * dann die frischen einfügen (UPSERT). So bleibt der Cache konsistent,
     * auch wenn der Kalender einen Termin gelöscht hat — die neue Liste
     * enthält ihn nicht mehr, und er verschwindet durch das Pruning.
     *
     * `pruneBefore` = Sync-Fensterbeginn: alles, was VOR dem Fenster endet,
     * ist für Vorschau und Auto-Start irrelevant.
     */
    override suspend fun replaceWindow(events: List<CalendarEventCache>, pruneBefore: Long) {
        eventDao.deleteEndedBefore(pruneBefore)
        if (events.isNotEmpty()) {
            eventDao.upsertAll(events)
        }
    }

    override suspend fun clearEvents() = eventDao.clear()

    /**
     * Markiert den Sync-Zeitpunkt.
     *
     * Gezieltes Spalten-UPDATE statt `upsert(settings.copy(...))` — ein
     * read-modify-write über das ganze Settings-Objekt könnte einen
     * parallel geschriebenen Toggle (z. B. aus der UI) überschreiben.
     */
    override suspend fun markSynced(at: Long) =
        settingsDao.setCalendarLastSyncAt(at, System.currentTimeMillis())
}
