package de.devondroste.aevum

import com.google.common.truth.Truth.assertThat
import de.devondroste.aevum.automation.geofence.GeofenceTransition
import de.devondroste.aevum.automation.rules.TriggerPairCandidateRuleEngine
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.domain.activity.SessionTimeValidator
import de.devondroste.aevum.domain.activity.SessionValidationResult
import de.devondroste.aevum.domain.time.TimeFormatting
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AevumUnitTest {
    @Test
    fun timeFormattingFormatsDuration() {
        assertThat(TimeFormatting.formatDuration(0)).isEqualTo("0m")
        assertThat(TimeFormatting.formatDuration(45 * 60_000L)).isEqualTo("45m")
        assertThat(TimeFormatting.formatDuration(2 * 60 * 60_000L + 15 * 60_000L)).isEqualTo("2h 15m")
    }

    @Test
    fun timeFormattingCreatesDayBoundaries() {
        val date = LocalDate.of(2026, 7, 18)
        val zone = ZoneId.of("Europe/Berlin")
        val start = TimeFormatting.startOfDayMillis(date, zone)
        val end = TimeFormatting.endOfDayMillis(date, zone)

        assertThat(end - start).isEqualTo(24 * 60 * 60 * 1000L)
        assertThat(TimeFormatting.millisToLocalDate(start, zone)).isEqualTo(date)
    }

    @Test
    fun timeFormattingMapsMinuteOfDayRoundTrip() {
        val date = LocalDate.of(2026, 7, 18)
        val zone = ZoneId.of("Europe/Berlin")
        val millis = TimeFormatting.millisAtMinuteOfDay(date, 8 * 60 + 15, zone)

        assertThat(TimeFormatting.minutesOfDay(millis, zone)).isEqualTo(8 * 60 + 15)
    }

    @Test
    fun validatorRejectsNegativeDurations() {
        val result = SessionTimeValidator.validate(
            title = "Deep Work",
            startAt = 2_000,
            endAt = 1_000
        )

        assertThat(result).isInstanceOf(SessionValidationResult.Invalid::class.java)
    }

    @Test
    fun validatorWarnsForOverlapsButAllowsSaving() {
        val result = SessionTimeValidator.validate(
            title = "Deep Work",
            startAt = 1_000,
            endAt = 3_000,
            existingSessions = listOf(
                ActivitySession(
                    id = "existing",
                    title = "Meeting",
                    categoryId = "work",
                    activityTypeId = "work",
                    startAt = 2_000,
                    endAt = 4_000
                )
            )
        )

        assertThat(result).isInstanceOf(SessionValidationResult.Warning::class.java)
    }

    @Test
    fun geofenceTransitionEnumKeepsEnterExitStates() {
        assertThat(GeofenceTransition.Enter.name).isEqualTo("Enter")
        assertThat(GeofenceTransition.Exit.name).isEqualTo("Exit")
    }

    @Test
    fun triggerPairRulesCreateTravelCandidateFromExitToDifferentEnter() {
        val engine = TriggerPairCandidateRuleEngine()
        val candidates = engine.evaluate(
            triggers = listOf(
                trigger("t1", 1_000, "HOME_LEFT", "home"),
                trigger("t2", 31_000 * 60, "CUSTOM_PLACE_ENTERED", "gym")
            ),
            geofences = listOf(
                geofence("home", "Zuhause", "household", "household"),
                geofence("gym", "Fitnessstudio", "fitness", "sport")
            ),
            now = 31_000 * 60 + 1
        )

        assertThat(candidates).hasSize(1)
        assertThat(candidates.first().suggestedTitle).isEqualTo("Fahrt: Zuhause → Fitnessstudio")
        assertThat(candidates.first().activityTypeId).isEqualTo("transport")
        assertThat(candidates.first().reason).contains("Zuhause verlassen")
    }

    @Test
    fun triggerPairRulesCreateStayCandidateFromEnterExitSamePlace() {
        val engine = TriggerPairCandidateRuleEngine()
        val candidates = engine.evaluate(
            triggers = listOf(
                trigger("t1", 1_000, "WORK_ENTERED", "work"),
                trigger("t2", 3_601_000, "WORK_LEFT", "work")
            ),
            geofences = listOf(geofence("work", "Arbeit", "work", "work")),
            now = 3_601_001
        )

        assertThat(candidates).hasSize(1)
        assertThat(candidates.first().suggestedTitle).isEqualTo("Arbeit")
        assertThat(candidates.first().activityTypeId).isEqualTo("work")
    }

    @Test
    fun triggerPairRulesKeepOpenExitWithoutDestinationUnresolved() {
        val engine = TriggerPairCandidateRuleEngine()
        val candidates = engine.evaluate(
            triggers = listOf(trigger("t1", 1_000, "HOME_LEFT", "home")),
            geofences = listOf(geofence("home", "Zuhause", "household", "household")),
            now = 2_000
        )

        assertThat(candidates).isEmpty()
    }

    private fun trigger(id: String, occurredAt: Long, type: String, geofenceId: String): TriggerEvent = TriggerEvent(
        id = id,
        occurredAt = occurredAt,
        type = type,
        source = "test",
        geofenceId = geofenceId
    )

    private fun geofence(id: String, name: String, activityTypeId: String?, categoryId: String?): PlaceGeofence = PlaceGeofence(
        id = id,
        name = name,
        latitude = 51.0,
        longitude = 7.0,
        radiusMeters = 120f,
        activityTypeId = activityTypeId,
        categoryId = categoryId
    )
}
