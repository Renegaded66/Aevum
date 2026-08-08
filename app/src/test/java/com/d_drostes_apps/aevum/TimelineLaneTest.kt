package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.ui.screens.timeline.TimelineSessionUi
import org.junit.Test

/**
 * M12.2: Tests für die Lane-Zuweisung der Timeline.
 *
 * Sicherstellt, dass sich überlappende Sessions unterschiedliche Lanes
 * bekommen — die Grundlage für die kollisionsfreie Timeline.
 */
class TimelineLaneTest {

    @Test
    fun nonOverlappingSessionsGetSameLane() {
        val s1 = session("a", 60, 120)  // 01:00 - 02:00
        val s2 = session("b", 180, 240) // 03:00 - 04:00
        val lanes = assignLanes(listOf(s1, s2))
        assertThat(lanes["a"]).isEqualTo(0)
        assertThat(lanes["b"]).isEqualTo(0)
    }

    @Test
    fun overlappingSessionsGetDifferentLanes() {
        val s1 = session("a", 60, 180)  // 01:00 - 03:00
        val s2 = session("b", 120, 200) // 02:00 - 03:20
        val lanes = assignLanes(listOf(s1, s2))
        assertThat(lanes["a"]).isNotEqualTo(lanes["b"])
    }

    @Test
    fun threeOverlappingSessionsNeedThreeLanes() {
        val s1 = session("a", 0, 100)
        val s2 = session("b", 20, 80)
        val s3 = session("c", 40, 60)
        val lanes = assignLanes(listOf(s1, s2, s3))
        assertThat(lanes.values.toSet()).hasSize(3)
    }

    @Test
    fun chainOfOverlapsSpillsIntoMoreLanes() {
        // s1: 0-100, s2: 50-150, s3: 100-200
        // s1 und s2 überlappen → Lanes 0 und 1.
        // s3 beginnt genau bei 100, also s1 fertig → kann Lane 0 übernehmen.
        val s1 = session("a", 0, 100)
        val s2 = session("b", 50, 150)
        val s3 = session("c", 100, 200)
        val lanes = assignLanes(listOf(s1, s2, s3))
        // Erwartung: a=0, b=1, c=0 (wiederverwendet)
        assertThat(lanes["a"]).isEqualTo(0)
        assertThat(lanes["b"]).isEqualTo(1)
        assertThat(lanes["c"]).isEqualTo(0)
    }

    private fun session(id: String, startMin: Int, endMin: Int): TimelineSessionUi =
        TimelineSessionUi(
            id = id,
            title = id,
            categoryId = "test",
            categoryName = "Test",
            activityTypeName = "Test",
            time = "%02d:%02d".format(startMin / 60, startMin % 60),
            range = "$startMin-$endMin",
            duration = "${endMin - startMin}m",
            source = "MANUAL",
            startMinuteOfDay = startMin,
            endMinuteOfDay = endMin,
            isRunning = false,
            isOverlapping = false,
            isAuto = false
        )

    /**
     * Spiegel der privaten Funktion in TimelineScreen.kt.
     * Wir testen die Lane-Logik hier isoliert, um die visuellen
     * Komponenten nicht mocken zu müssen.
     */
    private fun assignLanes(sessions: List<TimelineSessionUi>): Map<String, Int> {
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
}
