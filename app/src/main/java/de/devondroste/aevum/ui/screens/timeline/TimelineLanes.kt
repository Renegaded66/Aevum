package de.devondroste.aevum.ui.screens.timeline

/**
 * M12.2 / M13: Lane-Zuweisung für überlappende Sessions.
 *
 * Greedy lane-packing: für jede Session (nach Start sortiert) wird die erste
 * Lane gewählt, deren Ende vor dem Start liegt. So bekommen nicht überlappende
 * Sessions dieselbe Lane — und überlappende Sessions unterschiedliche.
 *
 * Public function so it's testable and re-usable.
 */
fun assignTimelineLanes(sessions: List<TimelineSessionUi>): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    val sorted = sessions.sortedBy { it.startMinuteOfDay }
    val laneEndTimes = mutableListOf<Int>()
    for (s in sorted) {
        var assigned = -1
        for (i in laneEndTimes.indices) {
            if (laneEndTimes[i] <= s.startMinuteOfDay) {
                assigned = i
                break
            }
        }
        if (assigned == -1) {
            assigned = laneEndTimes.size
            laneEndTimes.add(s.endMinuteOfDay)
        } else {
            laneEndTimes[assigned] = s.endMinuteOfDay
        }
        result[s.id] = assigned
    }
    return result
}
