package com.d_drostes_apps.aevum.domain.trigger

import java.time.LocalDate
import java.time.ZoneId

/**
 * M5.5 architecture seed for punctual trigger events.
 *
 * Trigger events are zero-duration points in time (home left, work entered,
 * motorcycle started, gym entered, ...). They are not ActivitySessions and
 * should later be sourced from geofences, Activity Recognition, vehicle states,
 * Health/Usage imports or manual rules. The Activity Editor can use them as
 * magnetic snap markers for start/end time selection.
 */
data class TriggerEventMarker(
    val id: String,
    val label: String,
    val occurredAt: Long,
    val kind: TriggerEventKind,
    val source: String = "PLANNED",
    // M18.93: Optionales Icon (z.B. ActivityType-Emoji bei Session-Ankern);
    // leer = die UI nutzt ein generisches Trigger-Glyph.
    val icon: String = ""
)

enum class TriggerEventKind {
    HOME_LEFT,
    HOME_ARRIVED,
    WORK_ENTERED,
    WORK_LEFT,
    MOTORCYCLE_STARTED,
    MOTORCYCLE_ENDED,
    GYM_ENTERED,
    GYM_LEFT,
    CUSTOM
}

object TriggerEventPreviewProvider {
    fun previewMarkersFor(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<TriggerEventMarker> = listOf(
        marker("home-left", "Zuhause verlassen", date, 8, 12, TriggerEventKind.HOME_LEFT, zoneId),
        marker("gym-enter", "Fitnessstudio betreten", date, 8, 41, TriggerEventKind.GYM_ENTERED, zoneId),
        marker("gym-left", "Fitnessstudio verlassen", date, 9, 58, TriggerEventKind.GYM_LEFT, zoneId),
        marker("home-arrive", "Zuhause angekommen", date, 10, 30, TriggerEventKind.HOME_ARRIVED, zoneId)
    )

    private fun marker(
        id: String,
        label: String,
        date: LocalDate,
        hour: Int,
        minute: Int,
        kind: TriggerEventKind,
        zoneId: ZoneId
    ): TriggerEventMarker = TriggerEventMarker(
        id = id,
        label = label,
        occurredAt = date.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli(),
        kind = kind
    )
}
