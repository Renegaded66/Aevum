package com.d_drostes_apps.aevum.data.cleanup

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AEVUM-1: Regression-Tests für den Daten-Aufräumlauf (Deduplizierung).
 *
 * Sichert die Kern-Invariante ab: "Nach dem Cleanup gibt es keine zwei
 * Sessions gleichen Typs, die sich zeitlich überlappen — und die NEUESTE
 * jeder Duplikat-Gruppe bleibt erhalten." (User-Report: Garmin-Schlaf
 * mehrfach gesynct → ~100h Schlaf in einer Nacht.)
 *
 * Bewusst Android-frei (reine JVM-Unit-Tests, kein Robolectric) — dieselbe
 * Strategie wie GarminSleepDedup.
 */
class SessionDedupCleanerTest {

    private fun session(
        id: String,
        startAt: Long,
        endAt: Long,
        activityTypeId: String = "sleep",
        sourceType: String = "GARMIN_SLEEP_AUTO",
        createdAt: Long = startAt,
        externalId: String? = null,
        isUserEdited: Boolean = false,
        sessionStatus: String = "FINISHED",
        categoryId: String? = null
    ) = ActivitySession(
        id = id,
        title = "Schlaf",
        activityTypeId = activityTypeId,
        categoryId = categoryId,
        startAt = startAt,
        endAt = endAt,
        sourceType = sourceType,
        createdAt = createdAt,
        externalId = externalId,
        isUserEdited = isUserEdited,
        sessionStatus = sessionStatus
    )

    private fun ids(sessions: List<ActivitySession>): List<String> = sessions.map { it.id }

    // ──────────────────────────────────────────────────────────────
    // Kern-Szenario: mehrfach gesyncter Garmin-Schlaf
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `zehn ueberlappende Garmin-Schlaeufe derselben Nacht — nur die neueste bleibt`() {
        // Exakt das User-Symptom: 10 Sync-Läufe derselben Nacht, jeder mit
        // leicht verschobenen Zeiten → 10 überlappende Sessions (~80-100h).
        val sessions = (0 until 10).map { i ->
            session(
                id = "v$i",
                startAt = 1_000L + i * 60_000L,
                endAt = 10_000_000L - i * 30_000L,
                createdAt = i.toLong()
            )
        }

        val duplicates = SessionDedupCleaner.duplicatesToDelete(sessions)

        // 9 ältere Duplikate werden gelöscht, die neueste (v9) bleibt.
        assertThat(ids(duplicates)).containsExactly("v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8")
        val remaining = sessions.filter { it.id !in ids(duplicates) }
        assertThat(remaining.map { it.id }).containsExactly("v9")
    }

    @Test
    fun `ueberlappung ist symmetrisch — a startet vor b endet nach b start`() {
        val a = session("a", startAt = 100L, endAt = 200L, createdAt = 1L)
        val b = session("b", startAt = 150L, endAt = 300L, createdAt = 2L)

        assertThat(SessionDedupCleaner.overlaps(a, b)).isTrue()
        assertThat(SessionDedupCleaner.overlaps(b, a)).isTrue()
        assertThat(SessionDedupCleaner.duplicatesToDelete(listOf(a, b)).map { it.id })
            .containsExactly("a")
    }

    @Test
    fun `nicht ueberlappende Sessions gleichen Typs bleiben unberuehrt`() {
        val morning = session("m1", startAt = 100L, endAt = 200L, createdAt = 1L)
        val evening = session("m2", startAt = 300L, endAt = 400L, createdAt = 2L)

        assertThat(SessionDedupCleaner.duplicatesToDelete(listOf(morning, evening))).isEmpty()
    }

    @Test
    fun `ueberlappende Sessions VERSCHIEDENER Typen bleiben unberuehrt`() {
        val sleep = session("s1", startAt = 100L, endAt = 400L, activityTypeId = "sleep", createdAt = 1L)
        val work = session("w1", startAt = 200L, endAt = 300L, activityTypeId = "work", createdAt = 2L)

        assertThat(SessionDedupCleaner.duplicatesToDelete(listOf(sleep, work))).isEmpty()
    }

    @Test
    fun `Kette von Teil-Ueberlappungen — keine ueberlappenden Duplikate bleiben zurueck`() {
        // a(0-10) überlappt b(5-15), b überlappt c(12-20), a überlappt c NICHT.
        // Da a eine NEUERE Session (b) überlappt, ist a ein Duplikat derselben
        // Aktivität — die Kette wird zur neuesten Version (c) zusammengefasst.
        val a = session("a", startAt = 0L, endAt = 10L, createdAt = 1L)
        val b = session("b", startAt = 5L, endAt = 15L, createdAt = 2L)
        val c = session("c", startAt = 12L, endAt = 20L, createdAt = 3L)

        assertThat(ids(SessionDedupCleaner.duplicatesToDelete(listOf(a, b, c))))
            .containsExactly("a", "b")
    }

    // ──────────────────────────────────────────────────────────────
    // M18.64-Fall: gleiche externalId trotz verschobener Zeiten
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `gleiche externalId ohne zeitliche Ueberlappung wird trotzdem dedupliziert`() {
        // Garmin verschiebt die Nacht nachträglich KOMPLETT (23-07 → 01-09):
        // keine Überlappung, aber dieselbe externalId = dieselbe Nacht.
        val first = session("s1", startAt = 100L, endAt = 200L, createdAt = 1L, externalId = "garmin_sleep_2026-08-10")
        val shifted = session("s2", startAt = 500L, endAt = 600L, createdAt = 2L, externalId = "garmin_sleep_2026-08-10")

        assertThat(ids(SessionDedupCleaner.duplicatesToDelete(listOf(first, shifted))))
            .containsExactly("s1")
    }

    @Test
    fun `verschiedene externalIds bleiben unberuehrt`() {
        val nightA = session("n1", startAt = 100L, endAt = 200L, createdAt = 1L, externalId = "garmin_sleep_2026-08-10")
        val nightB = session("n2", startAt = 300L, endAt = 400L, createdAt = 2L, externalId = "garmin_sleep_2026-08-11")

        assertThat(SessionDedupCleaner.duplicatesToDelete(listOf(nightA, nightB))).isEmpty()
    }

    // ──────────────────────────────────────────────────────────────
    // Schutzregeln: MANUAL / user-edited / live / offen
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `manuelle Sessions werden NIE geloescht — auch nicht bei Ueberlappung`() {
        val manual = session("m1", startAt = 100L, endAt = 400L, sourceType = "MANUAL", createdAt = 1L)
        val auto = session("a1", startAt = 150L, endAt = 350L, createdAt = 2L)

        assertThat(SessionDedupCleaner.isProtected(manual)).isTrue()
        assertThat(SessionDedupCleaner.duplicatesToDelete(listOf(manual, auto))).isEmpty()
    }

    @Test
    fun `user-edited Sessions werden NIE geloescht`() {
        val edited = session("e1", startAt = 100L, endAt = 400L, createdAt = 1L, isUserEdited = true)
        val auto = session("a1", startAt = 150L, endAt = 350L, createdAt = 2L)

        assertThat(SessionDedupCleaner.duplicatesToDelete(listOf(edited, auto))).isEmpty()
    }

    @Test
    fun `live Sessions und offene Sessions werden NIE geloescht`() {
        val live = session("l1", startAt = 100L, endAt = 400L, createdAt = 1L, sessionStatus = "RUNNING")
        val open = session("o1", startAt = 100L, endAt = 400L, createdAt = 1L).copy(endAt = null)
        val auto = session("a1", startAt = 150L, endAt = 350L, createdAt = 2L)

        assertThat(SessionDedupCleaner.isProtected(live)).isTrue()
        assertThat(SessionDedupCleaner.isProtected(open)).isTrue()
        assertThat(SessionDedupCleaner.duplicatesToDelete(listOf(live, open, auto))).isEmpty()
    }

    // ──────────────────────────────────────────────────────────────
    // Gruppierung: Fallback activityTypeId → categoryId
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Sessions mit NULL-Type aber categoryId sleep werden der Schlaf-Gruppe zugeordnet`() {
        // M18.65-FIX 3: Die REPLACE-Seed-Kaskade kann activityTypeId auf NULL
        // setzen — die Sessions sind aber weiterhin Schlaf (categoryId="sleep").
        val typed = session("t1", startAt = 100L, endAt = 400L, activityTypeId = "sleep", createdAt = 1L)
        val nullTyped = session("n1", startAt = 150L, endAt = 350L, activityTypeId = null, categoryId = "sleep", createdAt = 2L)

        assertThat(ids(SessionDedupCleaner.duplicatesToDelete(listOf(typed, nullTyped))))
            .containsExactly("t1")
    }

    // ──────────────────────────────────────────────────────────────
    // Deterministische Auswahl: höchstes createdAt, Tie-Break id
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `neueste Session gewinnt — Tie-Break hoechste id bei gleichem createdAt`() {
        val a = session("aaa", startAt = 100L, endAt = 200L, createdAt = 5L)
        val b = session("bbb", startAt = 150L, endAt = 250L, createdAt = 5L)

        assertThat(SessionDedupCleaner.newest(listOf(a, b)).id).isEqualTo("bbb")
        assertThat(ids(SessionDedupCleaner.duplicatesToDelete(listOf(a, b)))).containsExactly("aaa")
    }
}
