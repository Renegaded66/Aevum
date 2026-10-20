package com.d_drostes_apps.aevum.domain.digital

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * M24: Digital-Balance-Tageswechsel.
 *
 * Regression für Devons Befund: Beim Klick auf einen Balken wechselten die
 * App-Werte, aber die große Zahl oben (und der Stundenverlauf) blieben die
 * Werte von HEUTE. Ursache: `buildState` holte `todayDetail()` unabhängig
 * vom gewählten Tag. `detailForDay(date)` muss Gesamtzeit, Unlocks und
 * Stundenbuckets für genau diesen Tag liefern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppUsageAggregatorDetailTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val packageName = "com.test.learning"

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun addForeground(from: Long, toExclusive: Long) {
        val shadow = Shadows.shadowOf(
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        )
        shadow.addEvent(packageName, from, UsageEvents.Event.MOVE_TO_FOREGROUND)
        shadow.addEvent(packageName, toExclusive, UsageEvents.Event.MOVE_TO_BACKGROUND)
    }

    @Test
    fun `detailForDay liefert Gesamtzeit Unlocks und Stunden des gewaehlten Tages`() = runBlocking {
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val todayStart = at(today, 0)
        val yesterdayStart = at(yesterday, 0)

        // Gestern: 10:00–11:30 (90 min). Für heute ein Fenster, das
        // garantiert in der Vergangenheit liegt (Test kann kurz nach
        // Mitternacht laufen): letzte 30 min bis vor 2 min.
        addForeground(at(yesterday, 10), at(yesterday, 11, 30))
        val now = System.currentTimeMillis()
        val todayEventEnd = now - 2 * 60_000L
        val todayEventStart = maxOf(todayEventEnd - 30 * 60_000L, todayStart + 60_000L)
        val expectedTodayMs = todayEventEnd - todayEventStart
        addForeground(todayEventStart, todayEventEnd)

        val aggregator = AppUsageAggregator(context)

        val y = aggregator.detailForDay(yesterday)
        assertThat(y.totalMs).isEqualTo(90 * 60_000L)
        assertThat(y.unlockCount).isEqualTo(1)
        assertThat(y.hourlyMs[10]).isEqualTo(60 * 60_000L)
        assertThat(y.hourlyMs[11]).isEqualTo(30 * 60_000L)
        assertThat(y.hourlyMs.sum()).isEqualTo(90 * 60_000L)

        val t = aggregator.detailForDay(today)
        assertThat(t.totalMs).isEqualTo(expectedTodayMs)
        assertThat(t.unlockCount).isEqualTo(1)
        assertThat(t.hourlyMs.sum()).isEqualTo(expectedTodayMs)

        // Der Kern des Bugs: gestern darf nicht die heutigen 30 min liefern.
        assertThat(y.totalMs).isNotEqualTo(t.totalMs)
        // Und heute darf nicht die gestrigen 90 min liefern.
        assertThat(t.totalMs).isNotEqualTo(y.totalMs)
        assertThat(yesterdayStart).isLessThan(todayStart)
    }
}
