package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.CalendarEventPinDao
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.131: Repository für die manuell markierten Einzel-Termine.
 *
 * Bewusst getrennt vom [CalendarRepository]: dort leben Regeln und der
 * Termin-Cache — beides Dinge, die Aevum selbst verwaltet bzw. spiegelt.
 * Eine Markierung ist dagegen eine ausdrückliche Nutzer-Entscheidung zu
 * genau einer Termin-Instanz mit eigener Lebensdauer (sie überlebt
 * Cache-Syncs und Regel-Änderungen). Diese Trennung macht außerdem die
 * Aufräum-Regeln nachvollziehbar: nur dieses Repository löscht Markierungen.
 */
interface CalendarEventPinRepository {
    /** Alle Markierungen (reaktiv, für die Einstellungs-Liste). */
    fun getAll(): Flow<List<CalendarEventPin>>

    suspend fun getAllOnce(): List<CalendarEventPin>

    suspend fun getByIdOnce(eventId: String): CalendarEventPin?

    /** Markierungen im Zeitfenster (echter Overlap) für den Auto-Start. */
    suspend fun getInWindowOnce(from: Long, to: Long): List<CalendarEventPin>

    fun countFlow(): Flow<Int>

    suspend fun upsert(pin: CalendarEventPin)

    suspend fun remove(eventId: String)

    /**
     * Räumt Markierungen auf, deren Termin abgelaufen ist.
     *
     * WARUM DAS NÖTIG IST: Eine Markierung gilt für einen Termin, dessen
     * Zeitfenster endet. Ohne Aufräumen würde die Liste in den Einstellungen
     * unbegrenzt wachsen und der Nutzer müsste Vergangenes manuell löschen.
     *
     * Die Grenze liegt bewusst NICHT bei „jetzt", sondern bei „Beginn des
     * heutigen Tages": eine Termin-Karte des laufenden Tages soll sichtbar
     * bleiben (auch mit ihrem Ablauf-Status), sonst verschwindet ein Termin
     * für den Nutzer abrupt, während er ihn gerade ansieht.
     */
    suspend fun pruneEndedBefore(before: Long)

    /**
     * Entfernt Markierungen, deren Termin nicht mehr im Cache steht —
     * also im Kalender gelöscht oder verschoben wurde.
     *
     * Das ist das vom Auftrag geforderte Verhalten („das soll nicht mehr
     * passieren, wenn der Handyeintrag gelöscht wird"): die Aufzeichnung
     * endet mit dem Termin, und die verwaiste Markierung wird entfernt,
     * damit sie nicht später einen anders gelegenen Termin erwischt.
     *
     * ZWEI SCHUTZREGELN (beide bewusst):
     *  - Ist [validEventIds] leer (leerer Kalender), wird NICHTS gelöscht —
     *    sonst würde ein vorübergehend leerer Sync alle Markierungen
     *    vernichten (Berechtigung entzogen, Provider-Fehler).
     *  - Markierungen, deren Termin GERADE LÄUFT, bleiben erhalten, auch
     *    wenn der Termin im Kalender gelöscht wurde. Sie tragen als
     *    einziges verbliebenes Objekt die ursprüngliche Endzeit; ohne sie
     *    liefe die laufende Aufzeichnung bis zum 8-Stunden-Watchdog weiter.
     *
     * @param now Bezugszeit für den Laufend-Schutz.
     */
    suspend fun pruneOrphans(validEventIds: List<String>, now: Long)

    /** Entfernt ALLE Markierungen (z. B. wenn der Kalender-Sync abgeschaltet wird). */
    suspend fun clear()
}

@Singleton
class CalendarEventPinRepositoryImpl @Inject constructor(
    private val dao: CalendarEventPinDao
) : CalendarEventPinRepository {

    override fun getAll(): Flow<List<CalendarEventPin>> = dao.getAll()

    override suspend fun getAllOnce(): List<CalendarEventPin> = dao.getAllOnce()

    override suspend fun getByIdOnce(eventId: String): CalendarEventPin? =
        dao.getByIdOnce(eventId)

    override suspend fun getInWindowOnce(from: Long, to: Long): List<CalendarEventPin> =
        dao.getInWindowOnce(from, to)

    override fun countFlow(): Flow<Int> = dao.countFlow()

    override suspend fun upsert(pin: CalendarEventPin) = dao.upsert(pin)

    override suspend fun remove(eventId: String) = dao.deleteById(eventId)

    override suspend fun pruneEndedBefore(before: Long) = dao.deleteEndedBefore(before)

    override suspend fun pruneOrphans(validEventIds: List<String>, now: Long) {
        // Leerer Cache = kein verwertbares Signal (Sync noch nie gelaufen,
        // Berechtigung entzogen, Fenster ohne Termine). Nichts löschen.
        if (validEventIds.isEmpty()) return
        dao.deleteOrphans(validEventIds, now)
    }

    override suspend fun clear() = dao.clear()
}
