package com.d_drostes_apps.aevum.automation.garmin

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.63: Regression-Tests für den Garmin-Schlaf-Dedup.
 *
 * Sichert die Kern-Invariante ab: "Derselbe Garmin-Schlafdatensatz darf
 * unabhängig davon, wie oft die Synchronisation ausgelöst wird, nur
 * einmal im lokalen Datenbestand vorhanden sein."
 *
 * Hintergrund (empirisch belegt über Bridge-Caches):
 * Garmin ändert dieselbe Nacht NACH dem Sync mehrfach nachträglich
 * (z.B. 23:46–08:01 → 00:10–08:00). Der alte endAt-Fenster-Filter
 * (00:00–14:00) verfehlte Sessions nach solchen Änderungen → jedes
 * Sync-Intervall legte ein neues Insert an → ~10 überlappende
 * Duplikate (User: "70 Stunden Schlaf an einem Tag").
 */
class GarminSleepDedupTest {

    private fun session(
        id: String,
        startAt: Long,
        endAt: Long,
        sourceType: String = "GARMIN_SLEEP_AUTO",
        createdAt: Long = startAt
    ) = ActivitySession(
        id = id,
        title = "Schlaf",
        activityTypeId = "sleep",
        startAt = startAt,
        endAt = endAt,
        sourceType = sourceType,
        createdAt = createdAt
    )

    // Nacht zum 10.08.: Garmin lieferte zunächst 23:46–08:01, später
    // (nachträgliche Korrektur) 00:10–08:00 — exakt das Muster aus den
    // Bridge-Caches.
    private val sleepStart = 1786311960000L // 2026-08-09 23:46 Berlin
    private val sleepEnd = 1786341660000L   // 2026-08-10 08:01 Berlin

    @Test
    fun `überlappende Session wird gefunden — auch bei verschobener Zeit`() {
        // Session wurde beim ersten Sync mit der ALTEN Zeit angelegt
        // (00:10–08:00), Garmin liefert jetzt die KORRIGIERTE (23:46–08:01).
        val existing = session("s1", 1786313400000L, 1786341600000L, createdAt = 1L)

        val overlapping = GarminSleepDedup.overlappingSessions(listOf(existing), sleepStart, sleepEnd)

        assertThat(overlapping).hasSize(1)
        assertThat(overlapping[0].id).isEqualTo("s1")
    }

    @Test
    fun `nicht überlappende Session wird ignoriert`() {
        // Eine Session am Nachmittag (Mittagsschlaf 14:00–15:00) gehört
        // nicht zur Nacht.
        val afternoon = session("s1", 1786384800000L, 1786388400000L)

        val overlapping = GarminSleepDedup.overlappingSessions(listOf(afternoon), sleepStart, sleepEnd)

        assertThat(overlapping).isEmpty()
    }

    @Test
    fun `primäre Session ist die älteste — Duplikate sind der Rest`() {
        val first = session("s1", 1786313400000L, 1786341600000L, createdAt = 100L)
        val second = session("s2", 1786311960000L, 1786341660000L, createdAt = 200L)
        val third = session("s3", 1786312500000L, 1786341000000L, createdAt = 300L)

        val primary = GarminSleepDedup.primarySession(listOf(second, third, first))
        val dups = GarminSleepDedup.duplicateSessions(listOf(second, third, first))

        assertThat(primary?.id).isEqualTo("s1")
        assertThat(dups.map { it.id }).containsExactly("s2", "s3").inOrder()
    }

    @Test
    fun `zehn Sync-Läufe mit leicht verschobenen Zeiten erzeugen genau eine Primär-Session`() {
        // Simuliert 10 Sync-Läufe, bei denen Garmin die Zeit nachträglich
        // minimal verschiebt (exakt das User-Symptom "~10x synchronisiert").
        val sessions = mutableListOf<ActivitySession>()
        var createdAt = 1L
        for (i in 0 until 10) {
            // Jede "Version" weicht um einige Minuten ab (Garmin-Korrekturen).
            val startShift = i * 60_000L
            val endShift = i * 30_000L
            sessions += session(
                id = "v$i",
                startAt = sleepStart + startShift,
                endAt = sleepEnd - endShift,
                createdAt = createdAt++
            )
        }

        val overlapping = GarminSleepDedup.overlappingSessions(sessions, sleepStart, sleepEnd)
        val primary = GarminSleepDedup.primarySession(overlapping)
        val dups = GarminSleepDedup.duplicateSessions(overlapping)

        // Alle 10 überlappen die Nacht (Dedup findet sie alle)...
        assertThat(overlapping).hasSize(10)
        // ...genau EINE bleibt als Primär-Session übrig.
        assertThat(primary?.id).isEqualTo("v0")
        assertThat(dups).hasSize(9)
    }
}
