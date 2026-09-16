package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.ui.screens.timeline.PlannedSessionUi
import com.d_drostes_apps.aevum.ui.screens.timeline.TimelineEmptyState
import com.d_drostes_apps.aevum.ui.screens.timeline.TimelineSessionUi
import com.d_drostes_apps.aevum.ui.screens.timeline.TriggerEventUi
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.129-FIX: Regressionstests für den Empty-State der Timeline.
 *
 * DER GEMELDETE BUG (Devon): "für die Wochenansicht klappt das hervorragend,
 * allerdings noch nicht für die Tagesansicht, dort steht an den jeweiligen
 * Tagen weiterhin nur 'Noch keine Aktivitäten'."
 *
 * URSACHE: Der Empty-State-Guard der Tagesansicht prüfte nur `sessions`,
 * `triggerEvents` und `durationOnlySessions` — die geplanten Kalender-Blöcke
 * kannte er nicht. Ein Tag ohne echte Aufzeichnung, aber mit passenden
 * Terminen, fiel deshalb in den Empty-State und die Plan-Blöcke wurden nie
 * gezeichnet. Die Wochenansicht nutzt einen anderen Render-Pfad ohne diesen
 * Guard — deshalb war sie nicht betroffen.
 *
 * Diese Tests halten die Invariante fest: JEDE Inhaltsquelle zählt, sonst
 * kehrt der Bug bei der nächsten Erweiterung zurück.
 */
class TimelineEmptyStateTest {

    private fun session(id: String = "s1") = TimelineSessionUi(
        id = id,
        title = "Sport",
        categoryId = "cat",
        categoryName = "Gesundheit",
        activityTypeName = "Sport",
        time = "10:00",
        range = "10:00–11:00",
        duration = "1 Std",
        source = "MANUAL",
        startMinuteOfDay = 600,
        endMinuteOfDay = 660,
        isRunning = false,
        isOverlapping = false
    )

    private fun trigger(id: String = "t1") = TriggerEventUi(
        id = id,
        label = "Geofence",
        time = "10:00",
        minuteOfDay = 600,
        confidence = 80,
        source = "GEOFENCE"
    )

    private fun planned(id: String = "p1") = PlannedSessionUi(
        id = id,
        title = "Studium",
        activityTypeId = "studium",
        activityTypeName = "Studium",
        activityIcon = "📚",
        activityColor = 0L,
        ruleName = "Studium",
        startMinuteOfDay = 615,
        endMinuteOfDay = 705,
        timeRange = "10:15–11:45",
        durationMinutes = 90
    )

    // ── Der gemeldete Bug ─────────────────────────────────────────────

    @Test
    fun `Tag mit NUR geplanten Bloecken hat Inhalt`() {
        // Genau der gemeldete Fall: keine Aufzeichnung, keine Trigger,
        // aber ein geplanter Kalender-Termin. Vor dem Fix zeigte die
        // Tagesansicht hier "Noch keine Aktivitäten".
        assertThat(
            TimelineEmptyState.hasDayContent(
                sessions = emptyList(),
                durationOnlySessions = emptyList(),
                triggers = emptyList(),
                plannedSessions = listOf(planned())
            )
        ).isTrue()
    }

    @Test
    fun `Tag ohne jede Inhaltsquelle hat keinen Inhalt`() {
        // Der Empty-State muss weiterhin erscheinen, wenn wirklich nichts da ist.
        assertThat(
            TimelineEmptyState.hasDayContent(
                sessions = emptyList(),
                durationOnlySessions = emptyList(),
                triggers = emptyList(),
                plannedSessions = emptyList()
            )
        ).isFalse()
    }

    // ── Alle Inhaltsquellen einzeln ───────────────────────────────────

    @Test
    fun `echte Session zaehlt als Inhalt`() {
        assertThat(
            TimelineEmptyState.hasDayContent(listOf(session()), emptyList(), emptyList(), emptyList())
        ).isTrue()
    }

    @Test
    fun `Nur-Dauer-Session zaehlt als Inhalt`() {
        assertThat(
            TimelineEmptyState.hasDayContent(emptyList(), listOf(session()), emptyList(), emptyList())
        ).isTrue()
    }

    @Test
    fun `Trigger zaehlt als Inhalt`() {
        assertThat(
            TimelineEmptyState.hasDayContent(emptyList(), emptyList(), listOf(trigger()), emptyList())
        ).isTrue()
    }

    @Test
    fun `geplante Bloecke zaehlen als Inhalt`() {
        assertThat(
            TimelineEmptyState.hasDayContent(emptyList(), emptyList(), emptyList(), listOf(planned()))
        ).isTrue()
    }

    @Test
    fun `Kombination aus Plan und Session hat Inhalt`() {
        assertThat(
            TimelineEmptyState.hasDayContent(
                listOf(session()), emptyList(), emptyList(), listOf(planned())
            )
        ).isTrue()
    }

    // ── isPlanOnlyDay (Hinweis-Banner) ────────────────────────────────

    @Test
    fun `nur Plaene bedeutet Hinweis-Banner`() {
        assertThat(
            TimelineEmptyState.isPlanOnlyDay(
                sessions = emptyList(),
                durationOnlySessions = emptyList(),
                plannedSessions = listOf(planned())
            )
        ).isTrue()
    }

    @Test
    fun `Plan plus echte Aufzeichnung zeigt KEINEN Hinweis`() {
        // Sonst würde der Hinweis auch an Tagen erscheinen, an denen
        // bereits etwas aufgezeichnet wurde — das wäre verwirrend.
        assertThat(
            TimelineEmptyState.isPlanOnlyDay(
                sessions = listOf(session()),
                durationOnlySessions = emptyList(),
                plannedSessions = listOf(planned())
            )
        ).isFalse()
    }

    @Test
    fun `Plan plus Nur-Dauer-Aufzeichnung zeigt KEINEN Hinweis`() {
        assertThat(
            TimelineEmptyState.isPlanOnlyDay(
                sessions = emptyList(),
                durationOnlySessions = listOf(session()),
                plannedSessions = listOf(planned())
            )
        ).isFalse()
    }

    @Test
    fun `ohne Plaene zeigt keinen Hinweis`() {
        assertThat(
            TimelineEmptyState.isPlanOnlyDay(
                sessions = listOf(session()),
                durationOnlySessions = emptyList(),
                plannedSessions = emptyList()
            )
        ).isFalse()
        assertThat(
            TimelineEmptyState.isPlanOnlyDay(emptyList(), emptyList(), emptyList())
        ).isFalse()
    }
}
