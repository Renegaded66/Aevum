package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.ui.screens.timeline.TimelineSessionUi
import com.d_drostes_apps.aevum.ui.screens.timeline.findGapBelowActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tap direkt unter einer Aufzeichnung soll den Editor mit der kompletten
 * Lücke bis zur nächsten Aufzeichnung vorbelegen — ohne Überlagerung.
 */
class TimelineGapRangeTest {

    @Test
    fun directTapBelowActivityFillsWholeGap() {
        val gap = findGapBelowActivity(
            clickedMinute = 615, // 10:15, direkt unter dem 10:00-Ende
            sessions = listOf(
                session("a", 0, 480),    // 00:00-08:00
                session("b", 540, 600),  // 09:00-10:00
                session("c", 660, 720)   // 11:00-12:00
            ),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap?.startMinute).isEqualTo(600)
        assertThat(gap?.endMinute).isEqualTo(660)
    }

    @Test
    fun thresholdBoundaryStillCountsAsDirectlyBelow() {
        val gap = findGapBelowActivity(
            clickedMinute = 636, // exakt 36 Minuten unter dem Ende
            sessions = listOf(session("a", 540, 600), session("b", 660, 720)),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap?.startMinute).isEqualTo(600)
        assertThat(gap?.endMinute).isEqualTo(660)
    }

    @Test
    fun tapTooFarBelowActivityKeepsDefaultRange() {
        val gap = findGapBelowActivity(
            clickedMinute = 645, // 45 Minuten unter dem Ende
            sessions = listOf(session("a", 540, 600), session("b", 660, 720)),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap).isNull()
    }

    @Test
    fun lastActivityOfDayHasNoNextActivity() {
        val gap = findGapBelowActivity(
            clickedMinute = 735, // direkt unter dem 12:00-Ende
            sessions = listOf(session("a", 540, 600), session("b", 660, 720)),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap).isNull()
    }

    @Test
    fun tapInsideAnActivityIsNotAGap() {
        val gap = findGapBelowActivity(
            clickedMinute = 570, // mitten in 09:00-10:00
            sessions = listOf(session("a", 540, 600), session("b", 660, 720)),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap).isNull()
    }

    @Test
    fun tapAboveFirstActivityHasNoPreviousActivity() {
        val gap = findGapBelowActivity(
            clickedMinute = 300,
            sessions = listOf(session("a", 540, 600), session("b", 660, 720)),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap).isNull()
    }

    @Test
    fun overlappingActivityCoveringTheTapBlocksTheGap() {
        val gap = findGapBelowActivity(
            clickedMinute = 610, // liegt in der überlappenden 09:50-10:50-Activity
            sessions = listOf(
                session("a", 540, 600),
                session("overlap", 590, 650),
                session("b", 700, 760)
            ),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap).isNull()
    }

    @Test
    fun latestEndingBlockAboveTheTapIsTheStartBoundary() {
        val gap = findGapBelowActivity(
            clickedMinute = 620,
            sessions = listOf(
                session("a", 540, 600),
                session("overlap", 580, 610),
                session("b", 700, 760)
            ),
            maxDistanceBelowMinutes = 36
        )

        // Nicht das frühere 10:00-Ende, sondern das späteste Ende 10:10
        // verhindert eine Überlagerung mit der zweiten Aufzeichnung.
        assertThat(gap?.startMinute).isEqualTo(610)
        assertThat(gap?.endMinute).isEqualTo(700)
    }

    @Test
    fun nextActivityIsTheEarliestStartAfterTheGap() {
        val gap = findGapBelowActivity(
            clickedMinute = 615,
            sessions = listOf(
                session("a", 540, 600),
                session("b", 660, 720),
                session("c", 700, 760)
            ),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap?.startMinute).isEqualTo(600)
        assertThat(gap?.endMinute).isEqualTo(660)
    }

    @Test
    fun midnightClippedActivityCanBeTheUpperBoundary() {
        val gap = findGapBelowActivity(
            clickedMinute = 500,
            sessions = listOf(
                session("night", 0, 480), // im Tag sichtbarer Rest 00:00-08:00
                session("work", 540, 600)
            ),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap?.startMinute).isEqualTo(480)
        assertThat(gap?.endMinute).isEqualTo(540)
    }

    @Test
    fun wholeGapIsFilledEvenWhenItIsLong() {
        val gap = findGapBelowActivity(
            clickedMinute = 80,
            sessions = listOf(session("a", 0, 60), session("b", 600, 660)),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap?.startMinute).isEqualTo(60)
        assertThat(gap?.endMinute).isEqualTo(600)
    }

    @Test
    fun zeroThresholdDisablesGapPrefill() {
        val gap = findGapBelowActivity(
            clickedMinute = 610,
            sessions = listOf(session("a", 540, 600), session("b", 660, 720)),
            maxDistanceBelowMinutes = 0
        )

        assertThat(gap).isNull()
    }

    @Test
    fun clickOutsideTheDayIsIgnored() {
        val gap = findGapBelowActivity(
            clickedMinute = 1440,
            sessions = listOf(session("a", 540, 600), session("b", 660, 720)),
            maxDistanceBelowMinutes = 36
        )

        assertThat(gap).isNull()
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
}
