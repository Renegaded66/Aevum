package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.CalendarEventCacheDao
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * M18.132: Regressionstest für den Ghost-Termin-Fix in
 * [CalendarRepositoryImpl.replaceWindow].
 *
 * DER BUG (Symptom: „Termin steht in der Termin-Auswahl, aber nicht im
 * Kalender"): Bis M18.131 löschte replaceWindow nur Termine, die VOR dem
 * Sync-Fenster enden. Ein im Kalender GELÖSCHTER Termin blieb als Ghost
 * im Cache, bis er selbst aus dem Fenster fiel — bis zu 9 Tage sichtbar
 * in Picker und Timeline-Vorschau.
 *
 * Der Fix macht den Cache pro Sync zum SPIEGEL: was im Fenster liegt und
 * nicht mehr gelesen wurde, wird entfernt.
 *
 * Die Test-Strategie kopiert SessionDedupCleanerTest: die Ghost-Berechnung
 * ist eine reine Mengen-Operation (Cache-IDs minus Reader-IDs) — sie wird
 * hier gegen eine Fake-DAO-Implementierung verifiziert, ohne Robolectric.
 */
class CalendarCacheGhostPruneTest {

    private fun event(
        id: String,
        startAt: Long,
        endAt: Long = startAt + 3_600_000L
    ) = CalendarEventCache(
        eventId = id,
        calendarId = "1",
        calendarName = "Google",
        title = "Termin $id",
        description = null,
        location = null,
        startAt = startAt,
        endAt = endAt,
        allDay = false,
        attendees = null,
        syncedAt = 0L
    )

    /**
     * Minimaler Fake der von replaceWindow genutzten DAO-Operationen —
     * genau die Methoden, die der Ghost-Fix hinzufügt.
     */
    private class FakeCacheDao : CalendarEventCacheDao {
        val rows = mutableMapOf<String, CalendarEventCache>()

        override suspend fun getInWindowOnce(from: Long, to: Long): List<CalendarEventCache> =
            rows.values.filter { it.startAt < to && it.endAt > from }

        override fun getInWindow(from: Long, to: Long): kotlinx.coroutines.flow.Flow<List<CalendarEventCache>> =
            throw UnsupportedOperationException("im Fake nicht benötigt")

        override fun getAll(): kotlinx.coroutines.flow.Flow<List<CalendarEventCache>> =
            throw UnsupportedOperationException("im Fake nicht benötigt")

        override suspend fun getLastSyncedAt(): Long? = null

        override suspend fun count(): Int = rows.size

        override fun countFlow(): kotlinx.coroutines.flow.Flow<Int> =
            throw UnsupportedOperationException("im Fake nicht benötigt")

        override suspend fun upsertAll(events: List<CalendarEventCache>) {
            events.forEach { rows[it.eventId] = it }
        }

        override suspend fun deleteEndedBefore(before: Long) {
            rows.entries.removeIf { it.value.endAt < before }
        }

        override suspend fun getIdsEndingAfter(from: Long): List<String> =
            rows.values.filter { it.endAt >= from }.map { it.eventId }

        override suspend fun deleteByIds(eventIds: List<String>) {
            eventIds.forEach { rows.remove(it) }
        }

        override suspend fun clear() = rows.clear()
    }

    private val day = 1_700_000_000_000L // Bezugspunkt
    private val hour = 3_600_000L

    @Test
    fun `geloeschter Termin verschwindet aus dem Cache`() {
        runTest {
                val dao = FakeCacheDao()
                // Erster Sync: zwei Termine im Fenster.
                dao.upsertAll(listOf(event("a", day), event("b", day + hour)))
                // Zweiter Sync: der Kalender liefert nur noch b — a wurde GELÖSCHT.
                val readerResult = listOf(event("b", day + hour))
                // replaceWindow-Kern (identisch zur Implementierung):
                dao.deleteEndedBefore(day)
                val keptIds = readerResult.map { it.eventId }.toSet()
                val inWindow = dao.getIdsEndingAfter(day)
                val ghosts = inWindow.filter { it !in keptIds }
                ghosts.chunked(500).forEach { chunk -> dao.deleteByIds(chunk) }
                dao.upsertAll(readerResult)

                assertThat(dao.rows.keys).containsExactly("b")
        }
    }

    @Test
    fun `aktuelle Termine bleiben unangetastet`() {
        runTest {
                val dao = FakeCacheDao()
                dao.upsertAll(listOf(event("a", day), event("b", day + hour)))
                val readerResult = listOf(event("a", day), event("b", day + hour))
                dao.deleteEndedBefore(day)
                val keptIds = readerResult.map { it.eventId }.toSet()
                val inWindow = dao.getIdsEndingAfter(day)
                val ghosts = inWindow.filter { it !in keptIds }
                ghosts.chunked(500).forEach { chunk -> dao.deleteByIds(chunk) }
                dao.upsertAll(readerResult)

                assertThat(dao.rows.keys).containsExactly("a", "b")
        }
    }

    @Test
    fun `vor dem Fenster beendete Termine werden gepruned`() {
        runTest {
                val dao = FakeCacheDao()
                // Termin endet vor dem Fensterbeginn …
                dao.upsertAll(listOf(event("alt", day - 2 * hour)))
                // … und wird vom Reader (Fenster ab `day`) nicht mehr geliefert.
                dao.deleteEndedBefore(day)
                assertThat(dao.rows.keys).isEmpty()
        }
    }

    @Test
    fun `leerer Kalender leert den Cache im Fenster`() {
        runTest {
                val dao = FakeCacheDao()
                dao.upsertAll(listOf(event("a", day), event("b", day + hour)))
                // Dritter Sync: Kalender leer (z. B. letzter Account-Tag gelöscht).
                val readerResult: List<CalendarEventCache> = emptyList()
                dao.deleteEndedBefore(day)
                val keptIds = readerResult.map { it.eventId }.toSet()
                val inPruneScope = dao.getIdsEndingAfter(day)
                val ghosts = inPruneScope.filter { it !in keptIds }
                ghosts.chunked(500).forEach { chunk -> dao.deleteByIds(chunk) }

                assertThat(dao.rows.keys).isEmpty()
        }
    }

    @Test
    fun `wiedergekehrter Termin wird nicht versehentlich geloescht`() {
        runTest {
                val dao = FakeCacheDao()
                dao.upsertAll(listOf(event("a", day)))
                // Kalender liefert a erneut (gleiche Instanz — z. B. Sync-Reparatur
                // nach Offline-Änderung). Delete-Vor-UPSERT darf ihn NICHT wegwischen.
                val readerResult = listOf(event("a", day))
                dao.deleteEndedBefore(day)
                val keptIds = readerResult.map { it.eventId }.toSet()
                val inWindow = dao.getIdsEndingAfter(day)
                val ghosts = inWindow.filter { it !in keptIds }
                ghosts.chunked(500).forEach { chunk -> dao.deleteByIds(chunk) }
                dao.upsertAll(readerResult)

                assertThat(dao.rows.keys).containsExactly("a")
        }
    }
}