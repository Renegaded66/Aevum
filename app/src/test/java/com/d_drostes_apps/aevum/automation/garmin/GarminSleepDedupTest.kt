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

    // ──────────────────────────────────────────────────────────────
    // M18.64: Stabile Nacht-Identität (externalId) — Idempotenz über
    // beliebig viele Syncs, App-Neustarts und Garmin-Zeitkorrekturen.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `externalId ist stabil über den Aufwach-Tag — unabhängig von der Schlafzeit`() {
        // date=2026-08-10 = Nacht zum Morgen des 10.08. — egal ob Garmin
        // 23:46–08:01 oder 00:10–08:00 liefert, die ID bleibt gleich.
        assertThat(GarminSleepDedup.externalIdForNight("2026-08-10"))
            .isEqualTo("garmin_sleep_2026-08-10")
        assertThat(GarminSleepDedup.externalIdForNight("2026-08-10"))
            .isEqualTo(GarminSleepDedup.externalIdForNight("2026-08-10"))
        // Verschiedene Nächte → verschiedene IDs.
        assertThat(GarminSleepDedup.externalIdForNight("2026-08-10"))
            .isNotEqualTo(GarminSleepDedup.externalIdForNight("2026-08-11"))
    }

    @Test
    fun `primäre Session über externalId ist die älteste — deterministisch`() {
        val first = session("s1", sleepStart, sleepEnd, createdAt = 100L).copy(externalId = "garmin_sleep_2026-08-10")
        val second = session("s2", sleepStart + 60_000L, sleepEnd, createdAt = 200L).copy(externalId = "garmin_sleep_2026-08-10")

        val primary = GarminSleepDedup.primaryByExternalId(listOf(second, first))

        assertThat(primary?.id).isEqualTo("s1")
    }

    @Test
    fun `Sync 1 bis 50 mit nachträglichen Garmin-Korrekturen erzeugen genau einen Eintrag`() {
        // Simuliert die M18.64-Import-Logik: Sync 1 legt die Session MIT
        // externalId an; jeder weitere Sync findet sie über die ID und
        // UPDATET nur (Zeiten ändern sich, ID bleibt). Ergebnis: genau
        // eine Session, egal wie oft Garmin die Zeit korrigiert.
        var primary: ActivitySession? = null
        for (i in 0 until 50) {
            // Garmin-Korrektur: Zeiten wandern bei jedem Sync leicht.
            val startShift = (i % 7) * 60_000L
            val endShift = (i % 5) * 30_000L
            val garminStart = sleepStart + startShift
            val garminEnd = sleepEnd - endShift

            val byExternalId = primary?.let { listOf(it) } ?: emptyList()
            if (byExternalId.isNotEmpty()) {
                val p = GarminSleepDedup.primaryByExternalId(byExternalId)!!
                primary = p.copy(startAt = garminStart, endAt = garminEnd, revision = p.revision + 1)
            } else {
                primary = session(
                    id = "s1",
                    startAt = garminStart,
                    endAt = garminEnd,
                    createdAt = 1L
                ).copy(externalId = GarminSleepDedup.externalIdForNight("2026-08-10"))
            }
        }

        // Genau EIN Eintrag existiert — die ID blieb über alle 50 Syncs stabil.
        assertThat(primary?.externalId).isEqualTo("garmin_sleep_2026-08-10")
        assertThat(primary?.revision).isEqualTo(50)
        // Die letzte Garmin-Korrektur ist übernommen (nicht addiert).
        assertThat(primary?.startAt).isEqualTo(sleepStart + (49 % 7) * 60_000L)
        assertThat(primary?.endAt).isEqualTo(sleepEnd - (49 % 5) * 30_000L)
    }

    @Test
    fun `Bestands-Duplikate ohne externalId werden auf eine Session reduziert`() {
        // Alt-Bestand VOR M18.64: 3 GARMIN_SLEEP_AUTO-Sessions derselben
        // Nacht, alle ohne externalId, leicht verschoben (das reale
        // Duplikat-Muster aus den Bridge-Caches).
        val a = session("a", sleepStart, sleepEnd, createdAt = 100L)
        val b = session("b", sleepStart + 120_000L, sleepEnd - 60_000L, createdAt = 200L)
        val c = session("c", sleepStart - 60_000L, sleepEnd + 30_000L, createdAt = 300L)

        val cleanup = GarminSleepDedup.duplicatesToCleanup(listOf(a, b, c), sleepStart, sleepEnd)

        // Älteste (a) bleibt, b und c werden bereinigt.
        assertThat(cleanup.map { it.id }).containsExactly("b", "c")
    }

    @Test
    fun `Mittagsschlaf ohne Überlappung wird von der Bestands-Bereinigung verschont`() {
        // M18.63-Selbstprüfung: Nicht-überlappende Sessions im weiten
        // Nachtfenster sind echte andere Schlafereignisse (Mittagsschlaf)
        // und dürfen NIE bereinigt werden.
        val night = session("n", sleepStart, sleepEnd, createdAt = 100L)
        val nap = session("nap", 1786384800000L, 1786388400000L, createdAt = 200L) // 14:00–15:00

        val cleanup = GarminSleepDedup.duplicatesToCleanup(listOf(night, nap), sleepStart, sleepEnd)

        assertThat(cleanup).isEmpty()
    }
}
