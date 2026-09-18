package com.d_drostes_apps.aevum.domain.calendar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * M18.131: Regressionstest für den tagesübergreifenden Timeline-Block.
 *
 * DER GEMELDETE FEHLER (User): „In der Timeline bei tagesübergreifenden
 * Aufzeichnungen wird die vom Vortag nicht ganz angezeigt, nur ein kleiner
 * Strich vom Startzeitpunkt ist sichtbar, aber dieser ganze Eintrag wird
 * erst ab dem nächsten Tag 0 Uhr angezeigt."
 *
 * ROOT CAUSE: Für die Geometrie wurde `TimeFormatting.minutesOfDay()`
 * benutzt — die UHRZEIT im Tag (0..1439). Das auf das Tagesende geclippte
 * Ende einer Mitternachts-Session ist 00:00 des Folgetags, also 0 Minuten.
 * Der Renderer liest `endMinuteOfDay <= 0` als ungültig und zeichnet
 * `startMinuteOfDay + 1` — der einminütige Strich.
 *
 * Dieser Test sichert die zugrunde liegende Zusicherung ab, die von der
 * Uhrzeit-Semantik verletzt wurde: das Minutenpaar einer Session muss die
 * SICHTBARE Dauer im Tag abbilden. Ein Block 23:00–24:00 hat 60 Minuten,
 * nicht 1.
 *
 * Er prüft bewusst die reine Geometrie-Funktion und nicht das ViewModel:
 * das ViewModel braucht Android (Context, Room) und ist damit kein
 * JVM-Unit-Test-Kandidat — die fehlerhafte Zeile war aber genau diese
 * Umrechnung, und die ist hier vollständig abgedeckt.
 */
class TimelineMidnightGeometryTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val night = LocalDate.of(2026, 9, 18)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    // ── Die Kern-Zusicherung: Tagesende ist 1440, nicht 0 ───────────────

    @Test
    fun `Tagesende ergibt 1440 Minuten und nicht 0`() {
        val dayStart = millis(night, 0)
        val dayEnd = millis(night.plusDays(1), 0)
        assertThat(
            com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(dayEnd, dayStart)
        ).isEqualTo(1440)
    }

    @Test
    fun `Tagesbeginn ergibt 0 Minuten`() {
        val dayStart = millis(night, 0)
        assertThat(
            com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(dayStart, dayStart)
        ).isEqualTo(0)
    }

    /**
     * Der vollständige gemeldete Fall: Schlaf 23:00 → 07:00 des Folgetags.
     *
     * Tag 1 (Starttag): sichtbarer Ausschnitt 23:00–24:00 → 1380..1440,
     * also 60 Minuten. Vor dem Fix war das 1380..1381 = 1 Minute (der
     * gemeldete Strich).
     */
    @Test
    fun `Mitternachts-Session hat im Starttag 60 sichtbare Minuten`() {
        val dayStart1 = millis(night, 0)
        val dayEnd1 = millis(night.plusDays(1), 0)

        val sessionStart = millis(night, 23)
        val sessionEnd = millis(night.plusDays(1), 7)

        val clipStart = maxOf(sessionStart, dayStart1)
        val clipEnd = minOf(sessionEnd, dayEnd1)

        val startMin = com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(clipStart, dayStart1)
        val endMin = com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(clipEnd, dayStart1)

        assertThat(startMin).isEqualTo(1380)
        assertThat(endMin).isEqualTo(1440)
        assertThat(endMin - startMin).isEqualTo(60)
    }

    /**
     * Der Folgetag war nie betroffen — dieser Fall dokumentiert, dass der
     * Fix ihn nicht verändert hat (00:00–07:00 = 420 Minuten).
     */
    @Test
    fun `Mitternachts-Session hat im Folgetag 420 sichtbare Minuten`() {
        val dayStart2 = millis(night.plusDays(1), 0)
        val dayEnd2 = millis(night.plusDays(2), 0)

        val sessionStart = millis(night, 23)
        val sessionEnd = millis(night.plusDays(1), 7)

        val clipStart = maxOf(sessionStart, dayStart2)
        val clipEnd = minOf(sessionEnd, dayEnd2)

        val startMin = com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(clipStart, dayStart2)
        val endMin = com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(clipEnd, dayStart2)

        assertThat(startMin).isEqualTo(0)
        assertThat(endMin).isEqualTo(420)
    }

    // ── Randfälle ──────────────────────────────────────────────────────

    @Test
    fun `Tagessession bleibt unveraendert`() {
        val dayStart = millis(night, 0)
        val start = millis(night, 9)
        val end = millis(night, 17, 30)
        assertThat(com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(start, dayStart)).isEqualTo(540)
        assertThat(com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(end, dayStart)).isEqualTo(1050)
    }

    /**
     * „Läuft noch" (endAt = null) wird auf `now` geclippt — auch das darf
     * nie 0 ergeben, solange der Tag nicht vorbei ist.
     */
    @Test
    fun `laufende Session wird auf jetzt geclippt und nie 0`() {
        val dayStart = millis(night, 0)
        val now = millis(night, 14, 15)
        assertThat(com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(now, dayStart)).isEqualTo(855)
    }

    /**
     * Ein Zeitpunkt VOR Tagesbeginn (kommt durch Zeitzonenwechsel während
     * einer laufenden Session vor) wird auf 0 begrenzt statt negativ zu
     * werden — ein negativer Offset würde die Blockgeometrie spiegeln.
     */
    @Test
    fun `Zeitpunkt vor Tagesbeginn wird auf 0 begrenzt`() {
        val dayStart = millis(night, 0)
        val before = millis(night.minusDays(1), 23)
        assertThat(com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(before, dayStart)).isEqualTo(0)
    }

    /** Ein Offset jenseits des Tagesendes wird auf 1440 begrenzt. */
    @Test
    fun `Zeitpunkt nach Tagesende wird auf 1440 begrenzt`() {
        val dayStart = millis(night, 0)
        val after = millis(night.plusDays(1), 3)
        assertThat(com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(after, dayStart)).isEqualTo(1440)
    }

    /**
     * Die Gegenprobe zum Fix: derselbe Wert über die alte Uhrzeit-Funktion
     * ergibt 0 — das ist der Beweis, dass der Fix die Ursache trifft und
     * nicht bloß ein Symptom verdeckt.
     */
    @Test
    fun `alte Uhrzeit-Funktion liefert am Tagesende 0 - Ursache belegt`() {
        val dayEnd = millis(night.plusDays(1), 0)
        assertThat(
            com.d_drostes_apps.aevum.domain.time.TimeFormatting.minutesOfDay(dayEnd, zone)
        ).isEqualTo(0)
    }

    /** Jeder Minutenwert im Tag ist ein gültiger Blockrand (0..1440). */
    @Test
    fun `jede Minute des Tages liegt im gueltigen Bereich`() {
        val dayStart = millis(night, 0)
        for (minute in 0..1440) {
            val t = dayStart + minute * 60_000L
            val offset = com.d_drostes_apps.aevum.ui.screens.timeline.clippedMinuteOffset(t, dayStart)
            assertThat(offset).isAtLeast(0)
            assertThat(offset).isAtMost(1440)
        }
    }
}
