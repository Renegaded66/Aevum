package com.d_drostes_apps.aevum.ui.screens.timeline

/**
 * Zeitbereich einer Lücke auf der Tages-Timeline, in Minuten ab Tagesbeginn
 * (0..1440). 1440 steht für 24:00 des dargestellten Tages.
 */
internal data class TimelineGapRange(
    val startMinute: Int,
    val endMinute: Int
)

/**
 * Sucht die freie Lücke direkt unter einer Aufzeichnung.
 *
 * Hintergrund: Ein Tap auf eine leere Stelle der Tages-Timeline legt eine neue
 * Aktivität an. Endet die Aufzeichnung direkt über der Tap-Position, soll der
 * Editor die Lücke zwischen dieser und der nächsten Aufzeichnung exakt
 * ausfüllen — Start = Ende der oberen Aktivität, Ende = Start der unteren.
 *
 * Die Funktion ist bewusst frei von Compose/Android: Die Schwelle wird von der
 * UI aus einer dp-Distanz in Minuten umgerechnet, damit "halbwegs direkt
 * darunter" bei jedem Zoom gleich groß wirkt.
 *
 * @param clickedMinute Tap-Position als Minutenoffset ab Tagesbeginn.
 * @param sessions Sichtbare Blöcke des Tages (bereits auf den Tag geclippt).
 * @param maxDistanceBelowMinutes Maximale Distanz zwischen dem Ende der
 *   oberen Aktivität und dem Tap. Größere Distanzen gelten nicht mehr als
 *   "direkt darunter".
 * @return Die vollständige Lücke oder null, wenn keine eindeutige, freie Lücke
 *   direkt unter dem Tap liegt (z. B. letzte Aktivität des Tages, Tap in einer
 *   Aktivität oder Überlappung im Bereich).
 */
internal fun findGapBelowActivity(
    clickedMinute: Int,
    sessions: List<TimelineSessionUi>,
    maxDistanceBelowMinutes: Int
): TimelineGapRange? {
    if (maxDistanceBelowMinutes <= 0) return null
    if (clickedMinute !in 0 until MINUTES_PER_DAY) return null

    // Identische End-Geometrie wie die Zeichnung: sehr kurze bzw. auf 0
    // gefallene Enden werden als eine Minute breiter Block behandelt.
    val blocks = sessions.mapNotNull { session ->
        val start = session.startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY)
        val rawEnd = session.endMinuteOfDay
        val end = when {
            rawEnd <= 0 -> start + 1
            rawEnd < start + 1 -> start + 1
            rawEnd > MINUTES_PER_DAY -> MINUTES_PER_DAY
            else -> rawEnd
        }.coerceAtMost(MINUTES_PER_DAY)
        if (end <= start) null else TimelineGapBlock(start, end)
    }

    // Der Tap muss in freier Zeit liegen. Sonst würde die berechnete Lücke
    // eine bestehende Aufzeichnung überlagern.
    if (blocks.any { clickedMinute >= it.startMinute && clickedMinute < it.endMinute }) return null

    // Oberste Aktivität: die, deren Block am nächsten über dem Tap endet.
    val previous = blocks
        .filter { it.endMinute <= clickedMinute }
        .maxByOrNull { it.endMinute }
        ?: return null

    if (clickedMinute - previous.endMinute > maxDistanceBelowMinutes) return null

    // Nächste Aktivität: der früheste Block, der nach dem Ende der oberen
    // Aktivität beginnt. Fehlt er, ist es die letzte Aufzeichnung des Tages.
    val next = blocks
        .filter { it.startMinute >= previous.endMinute }
        .minByOrNull { it.startMinute }
        ?: return null

    // Angrenzende Blöcke haben keine Lücke, die man ausfüllen könnte.
    if (next.startMinute <= previous.endMinute) return null

    return TimelineGapRange(
        startMinute = previous.endMinute,
        endMinute = next.startMinute
    )
}

/** Nur die für die Lückensuche relevanten Blockkanten. */
private data class TimelineGapBlock(val startMinute: Int, val endMinute: Int)
