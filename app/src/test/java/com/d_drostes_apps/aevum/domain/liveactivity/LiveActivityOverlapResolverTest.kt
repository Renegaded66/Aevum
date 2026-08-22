package com.d_drostes_apps.aevum.domain.liveactivity

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.71: Überlappende Aktivitäten — nur die Überlappungszeit überschreiben.
 *
 * User-Spec: Beim Start einer neuen Aktivität, die sich mit einer
 * bestehenden zeitlich überlappt, wird die ERSTE nur im überlappenden
 * Zeitraum überschrieben — nie gelöscht.
 *   (a) Neue startet mitten in bestehender → bestehende endet exakt am
 *       Start der neuen (end = neuer start).
 *   (b) Neue liegt komplett in bestehender → bestehende wird in zwei Teile
 *       gesplittet (vor + nach der neuen).
 *   (c) Kein Löschen ganzer Sessions.
 *
 * Reine Logik-Tests (Muster: ScreenRecordingEngineTest M18.70).
 */
class LiveActivityOverlapResolverTest {

    private val now = 1_000_000L

    private fun session(
        id: String = "s1",
        startAt: Long,
        endAt: Long? = null,
        status: String = "RUNNING"
    ) = ActivitySession(
        id = id,
        title = "Bestehend",
        categoryId = null,
        activityTypeId = "work",
        startAt = startAt,
        endAt = endAt,
        timezoneId = "UTC",
        sourceType = "LIVE",
        sessionStatus = status
    )

    private fun resolve(
        existing: ActivitySession,
        newStart: Long,
        newEnd: Long? = null
    ) = LiveActivityOverlapResolver.resolve(existing, newStart, newEnd, now)

    // ── Regel (a): Neue startet mitten in bestehender ──

    @Test
    fun `neue startet mitten in laufender - bestehende endet exakt am neuen Start`() {
        // Bestehende läuft seit 100, neue startet bei 400 (rückwirkend).
        val result = resolve(
            existing = session(startAt = 100L),
            newStart = 400L
        )

        assertThat(result.overwritten).hasSize(1)
        assertThat(result.inserted).isEmpty()
        // end = neuer start (Regel a), NICHT now (1_000_000)
        assertThat(result.overwritten[0].endAt).isEqualTo(400L)
        assertThat(result.overwritten[0].startAt).isEqualTo(100L)
    }

    @Test
    fun `neue startet mitten in abgeschlossener - bestehende endet am neuen Start`() {
        val result = resolve(
            existing = session(startAt = 100L, endAt = 800L, status = "FINISHED"),
            newStart = 400L
        )

        assertThat(result.overwritten).hasSize(1)
        assertThat(result.overwritten[0].endAt).isEqualTo(400L)
        assertThat(result.inserted).isEmpty()
    }

    // ── Regel (b): neue liegt komplett in bestehender → Split ──

    @Test
    fun `neue liegt komplett in bestehender - bestehende wird in zwei Teile gesplittet`() {
        // Bestehende 100..800, neue 300..500 (abgeschlossen, z. B. manueller
        // Import-Pfad) → Teile 100..300 und 500..800.
        val result = resolve(
            existing = session(startAt = 100L, endAt = 800L, status = "FINISHED"),
            newStart = 200L,
            newEnd = 500L
        )

        assertThat(result.overwritten).hasSize(1)
        assertThat(result.overwritten[0].startAt).isEqualTo(100L)
        assertThat(result.overwritten[0].endAt).isEqualTo(200L)

        assertThat(result.inserted).hasSize(1)
        val second = result.inserted[0]
        assertThat(second.startAt).isEqualTo(500L)
        assertThat(second.endAt).isEqualTo(800L)
        // Der zweite Teil übernimmt Typ/Titel, ist aber abgeschlossen
        assertThat(second.title).isEqualTo("Bestehend")
        assertThat(second.activityTypeId).isEqualTo("work")
        assertThat(second.sessionStatus).isEqualTo("FINISHED")
        assertThat(second.id).isNotEqualTo("s1")
    }

    // ── Sonderfall: bestehende beginnt in der neuen, endet aber danach ──

    @Test
    fun `bestehende beginnt in der neuen und endet danach - nur der Rest bleibt`() {
        // Bestehende 300..800, neue 100..500 → Rest 500..800.
        val result = resolve(
            existing = session(startAt = 300L, endAt = 800L, status = "FINISHED"),
            newStart = 100L,
            newEnd = 500L
        )

        assertThat(result.overwritten).hasSize(1)
        assertThat(result.overwritten[0].startAt).isEqualTo(500L)
        assertThat(result.overwritten[0].endAt).isEqualTo(800L)
        assertThat(result.inserted).isEmpty()
    }

    // ── Regel (c): kein Löschen ganzer Sessions ──

    @Test
    fun `bestehende liegt komplett in der neuen - wird nicht geloescht sondern auf Null gekuerzt`() {
        val result = resolve(
            existing = session(startAt = 400L, endAt = 600L, status = "FINISHED"),
            newStart = 100L,
            newEnd = 900L
        )

        // Kein softDelete im Resolver-Ergebnis: Session bleibt erhalten,
        // aber auf 0-Länge (startAt == endAt) gekürzt → unsichtbar, nicht gelöscht.
        assertThat(result.overwritten).hasSize(1)
        assertThat(result.overwritten[0].startAt).isEqualTo(900L)
        assertThat(result.overwritten[0].endAt).isEqualTo(900L)
        assertThat(result.inserted).isEmpty()
    }

    // ── Keine Überlappung ──

    @Test
    fun `keine ueberlappung - nichts wird veraendert`() {
        // Neue startet NACH dem Ende der bestehenden.
        val result = resolve(
            existing = session(startAt = 100L, endAt = 200L, status = "FINISHED"),
            newStart = 300L
        )

        assertThat(result.overwritten).isEmpty()
        assertThat(result.inserted).isEmpty()
    }

    @Test
    fun `neue endet exakt beim Start der bestehenden - keine ueberlappung`() {
        val result = resolve(
            existing = session(startAt = 500L, endAt = 800L, status = "FINISHED"),
            newStart = 100L,
            newEnd = 500L
        )

        assertThat(result.overwritten).isEmpty()
        assertThat(result.inserted).isEmpty()
    }

    @Test
    fun `gleicher Startpunkt - neue uebernimmt ab jetzt, bestehende wird auf Null gekuerzt`() {
        // Bestehende läuft seit 100, neue startet exakt bei 100.
        val result = resolve(
            existing = session(startAt = 100L),
            newStart = 100L
        )

        // Komplette Überlappung (Regel c): nicht gelöscht, sondern auf
        // 0-Länge gekürzt — die alte Session bleibt als Datensatz erhalten.
        assertThat(result.overwritten).hasSize(1)
        assertThat(result.overwritten[0].startAt).isEqualTo(result.overwritten[0].endAt)
        assertThat(result.inserted).isEmpty()
    }
}
