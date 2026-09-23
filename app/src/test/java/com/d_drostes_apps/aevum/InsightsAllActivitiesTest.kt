package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.screens.insights.BreakdownMode
import com.d_drostes_apps.aevum.ui.screens.insights.InsightPeriod
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsAnalytics
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.137 (Kanban t_13e9f843): Die VOLLSTAENDIGE Aktivitaeten-Liste
 * ([InsightsUiState.allBreakdown]) — Grundlage fuer das Auf-/Zuklappen der
 * Top-Liste.
 *
 * Warum eigene Suite statt Ergaenzung in [InsightsAnalyticsTest]:
 * dort geht es um die Aggregations-Semantik (Gruppierung, Prozente,
 * Vorperioden-Vergleich). Hier geht es um den VERTRAG der vollstaendigen
 * Liste: vollstaendig, richtig sortiert, deckungsgleich mit den Top 5,
 * einschliesslich der Tagespauschalen. Die Trennung macht sichtbar, welche
 * Zusicherung bei einem Regress gebrochen ist.
 */
class InsightsAllActivitiesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 7, 19)
    private val categories = listOf(
        Category("work", "Arbeit", "#6366F1", "□"),
        Category("sport", "Sport", "#22C55E", "▲"),
        Category("digital", "Digital", "#64748B", "■"),
        Category("social", "Soziales", "#EC4899", "●")
    )

    /** Sieben Typen — bewusst mehr als die Top-5-Grenze. */
    private val types = listOf(
        ActivityType("deep_work", "Deep Work", "work"),
        ActivityType("gym", "Fitnessstudio", "sport"),
        ActivityType("phone", "Smartphone", "digital"),
        ActivityType("friends", "Freunde", "social"),
        ActivityType("reading", "Lesen", "work"),
        ActivityType("cooking", "Kochen", "work"),
        ActivityType("walking", "Spazieren", "sport")
    )

    /**
     * Sieben Aktivitaeten mit klar unterschiedlicher Dauer (7h absteigend
     * bis 1h). Damit ist die erwartete Sortierung eindeutig und der Test
     * kann die REIHENFOLGE pruefen, nicht nur die Laenge.
     */
    private fun sevenActivities(): List<ActivitySession> = listOf(
        session("a", "deep_work", "work", 8, 15),   // 7h
        session("b", "gym", "sport", 15, 21),       // 6h
        session("c", "phone", "digital", 6, 11),    // 5h
        session("d", "friends", "social", 11, 15),  // 4h
        session("e", "reading", "work", 5, 8),      // 3h
        session("f", "cooking", "work", 4, 6),      // 2h
        session("g", "walking", "sport", 3, 4)      // 1h
    )

    private fun build(
        sessions: List<ActivitySession>,
        accumulations: List<AllowanceAccumulationDay> = emptyList(),
        mode: BreakdownMode = BreakdownMode.Activity
    ): InsightsUiState = InsightsAnalytics.build(
        sessions = sessions,
        categories = categories,
        activityTypes = types,
        selectedPeriod = InsightPeriod.Today,
        anchorDate = anchor,
        zoneId = zone,
        allowanceAccumulations = accumulations,
        breakdownMode = mode
    )

    /**
     * KERN-ZUSICHERUNG der Card: die Liste ist VOLLSTAENDIG — alle sieben
     * Aktivitaeten, nicht die ersten fuenf.
     */
    @Test
    fun allBreakdownContainsEveryActivityNotJustTopFive() {
        val result = build(sevenActivities())

        assertThat(result.allBreakdown).hasSize(7)
        assertThat(result.allBreakdown.map { it.label }).containsExactly(
            "Deep Work", "Fitnessstudio", "Smartphone", "Freunde",
            "Lesen", "Kochen", "Spazieren"
        ).inOrder()
    }

    /** Die Top-5-Ansicht bleibt unveraendert bei fuenf Eintraegen. */
    @Test
    fun topBreakdownStaysLimitedToFiveInActivityMode() {
        val result = build(sevenActivities())

        assertThat(result.topBreakdown).hasSize(5)
    }

    /**
     * Sortierkriterium: `durationMs` absteigend — dasselbe wie bei den
     * Top 5. Explizit als Monotonie-Assert formuliert, damit der Test
     * auch eine kuenftige Umsortierung faengt.
     */
    @Test
    fun allBreakdownIsSortedByDurationDescending() {
        val durations = build(sevenActivities()).allBreakdown.map { it.durationMs }

        assertThat(durations).containsExactly(
            7 * HOUR, 6 * HOUR, 5 * HOUR, 4 * HOUR, 3 * HOUR, 2 * HOUR, HOUR
        ).inOrder()
    }

    /**
     * Die Top 5 sind ein PRAEFIX der vollstaendigen Liste — gleiche
     * Elemente, gleiche Reihenfolge. Genau das verhindert, dass die
     * zugeklappte und die aufgeklappte Ansicht auseinanderlaufen
     * ("keine doppelten oder veralteten Daten" aus t_386782be).
     */
    @Test
    fun topBreakdownIsPrefixOfAllBreakdown() {
        val result = build(sevenActivities())

        assertThat(result.topBreakdown)
            .isEqualTo(result.allBreakdown.take(5))
    }

    /**
     * Prozente beider Ansichten beruhen auf DERSELBEN Basis. Sonst wuerden
     * die Zahlen beim Aufklappen springen, obwohl sich an den Daten nichts
     * geaendert hat.
     */
    @Test
    fun percentagesUseSameBasisInBothViews() {
        val result = build(sevenActivities())

        result.allBreakdown.forEach { slice ->
            val matching = result.topBreakdown.firstOrNull { it.id == slice.id }
                ?: return@forEach
            assertThat(matching.percent).isEqualTo(slice.percent)
        }
    }

    /**
     * Tie-Break: drei Aktivitaeten mit IDENTISCHER Dauer. Ohne stabilen
     * zweiten Schluessel waere die Reihenfolge beliebig — die Liste wuerde
     * beim Auf-/Zuklappen springen. Erwartet: alphabetisch nach `label`.
     */
    @Test
    fun equalDurationsAreOrderedDeterministicallyByLabel() {
        val tied = listOf(
            session("f", "cooking", "work", 10, 12),   // 2h, "Kochen"
            session("c", "phone", "digital", 6, 8),    // 2h, "Smartphone"
            session("a", "deep_work", "work", 13, 15)  // 2h, "Deep Work"
        )

        val labels = build(tied).allBreakdown.map { it.label }

        // "Deep Work" < "Kochen" < "Smartphone" (lexikografisch, aufsteigend).
        assertThat(labels).containsExactly("Deep Work", "Kochen", "Smartphone").inOrder()
    }

    /** Die Sortierung ist stabil: zweimal bauen → identische Reihenfolge. */
    @Test
    fun orderingIsStableAcrossRebuilds() {
        val sessions = sevenActivities()

        assertThat(build(sessions).allBreakdown)
            .isEqualTo(build(sessions).allBreakdown)
    }

    /**
     * Tagespauschalen sind auch in der vollstaendigen Liste enthalten —
     * ein frueherer `.take(5)` auf den Pauschalen-Slices haette sie in der
     * aufgeklappten Ansicht verschluckt.
     *
     * Szenario bewusst so gebaut, dass der alte Deckel WIEDER greifen
     * wuerde: SECHS Pauschalen-Typen (mehr als fuenf) plus eine echte
     * Aktivitaet. Mit `.take(5)` auf den Pauschalen fehlt genau einer
     * (6 Slices); ohne Deckel sind alle sieben da. Die Erwartung 7
     * unterscheidet also die beiden Implementierungen, statt in beiden
     * Faellen gruen zu sein.
     */
    @Test
    fun allowancesAreIncludedInAllBreakdown() {
        val allowanceTypes = listOf(
            "reading" to 90, "walking" to 80, "cooking" to 70,
            "friends" to 60, "phone" to 50, "gym" to 40
        )
        val accumulations = allowanceTypes.mapIndexed { index, (typeId, minutes) ->
            accumulation(allowanceId = "al$index", typeId = typeId, minutes = minutes)
        }

        val result = build(
            sessions = listOf(session("a", "deep_work", "work", 8, 10)),
            accumulations = accumulations
        )

        val ids = result.allBreakdown.map { it.id }
        // Alle sechs Pauschalen-Typen sind vertreten — keiner abgeschnitten.
        allowanceTypes.forEach { (typeId, _) ->
            assertThat(ids).contains("allowance_$typeId")
        }
        assertThat(result.allBreakdown).hasSize(7)
    }

    /**
     * Die Summe der Pauschalen erscheint als eigener Slice mit der
     * aufsummierten Dauer (90 min + 30 min getrennt nach Typ).
     */
    @Test
    fun allowanceSliceCarriesAccumulatedDuration() {
        val accumulations = listOf(
            accumulation(allowanceId = "al1", typeId = "reading", minutes = 60),
            accumulation(allowanceId = "al1b", typeId = "reading", minutes = 30)
        )

        val result = build(sessions = emptyList(), accumulations = accumulations)

        val reading = result.allBreakdown.first { it.id == "allowance_reading" }
        assertThat(reading.durationMs).isEqualTo(90 * 60_000L)
    }

    /**
     * Leere Datenbasis → leere Liste, kein Absturz. (Ein leerer Zeitraum
     * ist der Normalfall nach einer frischen Installation.)
     */
    @Test
    fun emptyInputYieldsEmptyList() {
        val result = build(sessions = emptyList())

        assertThat(result.allBreakdown).isEmpty()
        assertThat(result.topBreakdown).isEmpty()
    }

    /**
     * Kategorie-Modus: dort ist die angezeigte Liste bereits vollstaendig
     * (M18.66-FIX17). `allBreakdown` muss dieselbe Liste sein — ein
     * einheitlicher Vertrag, damit die UI nicht je nach Modus
     * unterschiedlich aufklappen muss.
     */
    @Test
    fun categoryModeReturnsSameListInBothViews() {
        val result = build(sevenActivities(), mode = BreakdownMode.Category)

        assertThat(result.allBreakdown).isEqualTo(result.topBreakdown)
        // Vier Kategorien (work, sport, digital, social) — alle da.
        assertThat(result.allBreakdown).hasSize(4)
    }

    /**
     * Prozentbasis: die Anteile sind Anteile an der GESAMTEN erfassten Zeit
     * des Zeitraums (alle Aktivitaeten + Pauschalen) — nicht am
     * Top-5-Ausschnitt.
     *
     * Das ist eine bewusste, sichtbare Korrektur: Frueher war die Basis im
     * Aktivitaets-Modus die Summe der Top 5, weil dieselbe `.take(5)`-
     * Kappung auch in die Prozentrechnung floss. Bei mehr als fuenf
     * Aktivitaeten summierte sich die Anzeige dadurch auf 100 % ueber
     * einen Ausschnitt, und die Prozente wichen von denen im
     * Kategorie-Modus (dort war die Basis schon immer vollstaendig) und
     * von `topActivities.percent` ab.
     *
     * Rechnung im Test: 7 Aktivitaeten = 28 h, Pauschale = 4 h → Basis
     * 32 h. "Deep Work" (7 h) ist damit 7/32 = 21,875 % → 22 %.
     * Mit der alten Top-5-Basis waere es 7/24 = 29 % gewesen.
     */
    @Test
    fun percentagesAreSharesOfTheWholePeriodNotOfTheTopFive() {
        val result = build(
            sessions = sevenActivities(),
            accumulations = listOf(
                accumulation(allowanceId = "al1", typeId = "reading", minutes = 240)
            )
        )

        val deepWork = result.allBreakdown.first { it.id == "deep_work" }
        assertThat(deepWork.durationMs).isEqualTo(7 * HOUR)
        assertThat(deepWork.percent).isEqualTo(22)
    }

    private fun accumulation(
        allowanceId: String,
        typeId: String,
        minutes: Int
    ) = AllowanceAccumulationDay(
        date = anchor.toString(),
        timezoneId = zone.id,
        allowanceId = allowanceId,
        activityTypeId = typeId,
        minutes = minutes
    )

    private fun session(
        id: String,
        typeId: String,
        categoryId: String,
        startHour: Int,
        endHour: Int,
        date: LocalDate = anchor
    ): ActivitySession {
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = id, title = id, activityTypeId = typeId,
            categoryId = categoryId, startAt = start, endAt = end
        )
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
