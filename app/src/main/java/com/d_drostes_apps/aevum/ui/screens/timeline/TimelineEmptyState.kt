package com.d_drostes_apps.aevum.ui.screens.timeline

/**
 * M18.129-FIX: Entscheidungen darüber, wann die Timeline den Empty-State
 * zeigen darf.
 *
 * WARUM EINE EIGENE, REINE FUNKTION:
 * Diese Bedingung war als Inline-Ausdruck im Composable versteckt und
 * enthielt einen echten Bug — sie prüfte Sessions und Trigger, aber NICHT
 * die geplanten Kalender-Blöcke. Folge: Ein Tag ohne Aufzeichnung, aber mit
 * geplanten Terminen, zeigte „Noch keine Aktivitäten" statt der
 * gestrichelten Plan-Blöcke. Die Wochenansicht war nicht betroffen, weil
 * sie einen anderen Render-Pfad ohne diesen Guard nutzt — der Bug war also
 * nur in der Tagesansicht sichtbar.
 *
 * Als reine Funktion ist die Logik in Unit-Tests abdeckbar, und jede
 * künftige Inhaltsquelle muss hier bewusst ergänzt werden (statt in einem
 * tief verschachtelten Composable übersehen zu werden).
 */
object TimelineEmptyState {

    /**
     * Gibt es IRGENDEINEN sichtbaren Inhalt für den gewählten Tag?
     *
     * Alle Inhaltsquellen der Tages- und Listenansicht zählen:
     *  - echte Aufzeichnungen ([sessions])
     *  - Nur-Dauer-Aufzeichnungen ([durationOnlySessions], eigener Block
     *    ganz oben in der Liste)
     *  - Trigger-Marker ([triggers])
     *  - **geplante Kalender-Blöcke ([plannedSessions])** — der M18.129-Fix
     */
    fun hasDayContent(
        sessions: List<TimelineSessionUi>,
        durationOnlySessions: List<TimelineSessionUi>,
        triggers: List<TriggerEventUi>,
        plannedSessions: List<PlannedSessionUi>
    ): Boolean =
        sessions.isNotEmpty() ||
            durationOnlySessions.isNotEmpty() ||
            triggers.isNotEmpty() ||
            plannedSessions.isNotEmpty()

    /**
     * Enthält der Tag NUR geplante Blöcke (keine echte Aufzeichnung)?
     *
     * Wird genutzt, um einen erklärenden Hinweis einzublenden: Ohne ihn
     * könnte der Nutzer die gestrichelten Flächen für einen Darstellungs-
     * fehler halten oder glauben, es sei bereits etwas aufgezeichnet.
     */
    fun isPlanOnlyDay(
        sessions: List<TimelineSessionUi>,
        durationOnlySessions: List<TimelineSessionUi>,
        plannedSessions: List<PlannedSessionUi>
    ): Boolean =
        plannedSessions.isNotEmpty() &&
            sessions.isEmpty() &&
            durationOnlySessions.isEmpty()
}
