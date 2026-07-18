package de.devondroste.aevum

import com.google.common.truth.Truth.assertThat
import de.devondroste.aevum.data.model.ActivitySession
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
}
