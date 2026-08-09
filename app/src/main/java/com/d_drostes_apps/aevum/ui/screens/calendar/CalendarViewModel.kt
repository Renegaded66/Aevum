package com.d_drostes_apps.aevum.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/**
 * M18.28: Kalender-ViewModel.
 *
 * Aggregiert pro Tag des sichtbaren Monats:
 *  - Gesamtdauer (inkl. Schlaf, geklippt auf den Tag)
 *  - Gewichtete Positivität: Summe(dauer * (score-50)) / Summe(dauer)
 *    -> -50..+50, 0 = neutral. Bestimmt die Heatmap-Farbe.
 *  - Erfasst-Flag (irgendeine Session vorhanden)
 *
 * Der Kalender ist eine Heatmap der Zeitqualität: ein Tag leuchtet
 * gruen, wenn viel positive Zeit erfasst wurde, rot bei viel negativer
 * Zeit (z.B. Digital), grau bei nichts.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    activityRepository: ActivityRepository,
    activityTypeRepository: ActivityTypeRepository
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()

    val selectedMonth = MutableStateFlow(YearMonth.now())
    val selectedDate = MutableStateFlow(LocalDate.now())

    val uiState: StateFlow<CalendarUiState> = combine(
        selectedMonth,
        selectedDate,
        activityRepository.getAll(),
        activityTypeRepository.getAll()
    ) { month, selected, sessions, types ->
        buildState(month, selected, sessions, types)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun previousMonth() {
        selectedMonth.update { it.minusMonths(1) }
        // Selektion in den neuen Monat ziehen, falls dort kein Tag liegt
        val m = selectedMonth.value
        val sel = selectedDate.value
        if (YearMonth.from(sel) != m) {
            val day = minOf(sel.dayOfMonth, m.lengthOfMonth())
            selectedDate.value = m.atDay(day)
        }
    }

    fun nextMonth() {
        selectedMonth.update { it.plusMonths(1) }
        val m = selectedMonth.value
        val sel = selectedDate.value
        if (YearMonth.from(sel) != m) {
            val day = minOf(sel.dayOfMonth, m.lengthOfMonth())
            selectedDate.value = m.atDay(day)
        }
    }

    fun today() {
        selectedMonth.value = YearMonth.now()
        selectedDate.value = LocalDate.now()
    }

    fun selectDate(date: LocalDate) {
        selectedMonth.value = YearMonth.from(date)
        selectedDate.value = date
    }

    private fun buildState(
        month: YearMonth,
        selected: LocalDate,
        sessions: List<ActivitySession>,
        types: List<ActivityType>
    ): CalendarUiState {
        val typeMap = types.associateBy { it.id }
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val rangeStartMs = TimeFormatting.startOfDayMillis(monthStart, zoneId)
        val rangeEndMs = TimeFormatting.endOfDayMillis(monthEnd, zoneId)

        // Sessions des Monats (inkl. Mitternacht-Sessions, die hereinschneiden)
        // M18.60-FIX (User: "In der kalenderansicht steht bei jedem
        // zukünftigen tag schon die aktivität, die ich gerade live am
        // aufzeichnen bin"): Eine LAUFENDE Session (endAt == null) darf
        // nur in Monaten erscheinen, die bereits begonnen haben — sonst
        // taucht sie in zukünftigen Monaten auf.
        val nowMs = System.currentTimeMillis()
        val monthSessions = sessions.filter {
            it.deletedAt == null && it.startAt < rangeEndMs && (
                if (it.endAt == null) rangeStartMs <= nowMs else it.endAt > rangeStartMs
                )
        }

        // Pro Tag aggregieren
        val days = mutableMapOf<LocalDate, CalendarDayAggregate>()
        var cursor = monthStart
        while (!cursor.isAfter(monthEnd)) {
            days[cursor] = CalendarDayAggregate(date = cursor)
            cursor = cursor.plusDays(1)
        }
        monthSessions.forEach { session ->
            // Sichtbaren Ausschnitt auf den jeweiligen Tag clippen
            val s = session.startAt
            val e = session.endAt ?: System.currentTimeMillis()
            var day = Instant.ofEpochMilli(s).atZone(zoneId).toLocalDate()
            var clipStart = s
            while (clipStart < e) {
                val dayEnd = TimeFormatting.endOfDayMillis(day, zoneId)
                val clipEnd = minOf(e, dayEnd)
                if (day in days) {
                    val agg = days[day]!!
                    val durationMs = (clipEnd - clipStart).coerceAtLeast(0L)
                    val score = typeMap[session.activityTypeId]?.positivityScore ?: 50
                    days[day] = agg.copy(
                        totalDurationMs = agg.totalDurationMs + durationMs,
                        weightedScoreSum = agg.weightedScoreSum + durationMs * (score - 50),
                        sessionCount = agg.sessionCount + 1
                    )
                }
                clipStart = clipEnd
                day = day.plusDays(1)
            }
        }

        val dayList = days.values.map { agg ->
            val score = if (agg.totalDurationMs > 0) {
                (agg.weightedScoreSum.toDouble() / agg.totalDurationMs).toFloat()
            } else 0f
            agg.copy(avgScore = score.coerceIn(-50f, 50f))
        }

        // Tages-Detail für die ausgewählte Selektion
        // M18.60-FIX: Laufende Session (endAt == null) nur am HEUTIGEN
        // Tag (oder früher) anzeigen — nie an zukünftigen Tagen.
        val selStart = TimeFormatting.startOfDayMillis(selected, zoneId)
        val selEnd = TimeFormatting.endOfDayMillis(selected, zoneId)
        val daySessions = sessions.filter {
            it.deletedAt == null && it.startAt < selEnd && (
                if (it.endAt == null) selStart <= nowMs else it.endAt > selStart
                )
        }.sortedBy { it.startAt }

        val dayDetails = daySessions.map { session ->
            val clipStart = maxOf(session.startAt, selStart)
            val clipEnd = minOf(session.endAt ?: System.currentTimeMillis(), selEnd)
            val type = typeMap[session.activityTypeId]
            CalendarDaySessionUi(
                sessionId = session.id,
                title = session.title ?: "Aktivität",
                activityTypeId = session.activityTypeId ?: "other",
                icon = type?.icon?.takeIf { it.isNotBlank() } ?: "•",
                color = type?.color?.takeIf { it != 0L } ?: 0L,
                positivityScore = type?.positivityScore ?: 50,
                startMinute = TimeFormatting.minutesOfDay(clipStart, zoneId),
                endMinute = TimeFormatting.minutesOfDay(clipEnd, zoneId),
                durationMs = (clipEnd - clipStart).coerceAtLeast(0L),
                startAt = clipStart
            )
        }

        // Wochen-Start: Montag (deutsche Konvention)
        val mondayBased = (monthStart.dayOfWeek.value + 6) % 7 // Mo=0
        val leadingEmpty = mondayBased

        return CalendarUiState(
            month = month,
            selectedDate = selected,
            days = dayList.associateBy { it.date },
            leadingEmptyCells = leadingEmpty,
            daySessions = dayDetails,
            weekDayLabels = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        )
    }
}

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val days: Map<LocalDate, CalendarDayAggregate> = emptyMap(),
    val leadingEmptyCells: Int = 0,
    val daySessions: List<CalendarDaySessionUi> = emptyList(),
    val weekDayLabels: List<String> = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"),
    val isLoading: Boolean = false
) {
    val totalTrackedMs: Long
        get() = days.values.sumOf { it.totalDurationMs }
}

data class CalendarDayAggregate(
    val date: LocalDate,
    val totalDurationMs: Long = 0,
    val weightedScoreSum: Long = 0,
    val sessionCount: Int = 0,
    val avgScore: Float = 0f
) {
    val isToday: Boolean get() = date == LocalDate.now()
    val isSelected: Boolean get() = false // wird in der UI verglichen
}

data class CalendarDaySessionUi(
    val sessionId: String,
    val title: String,
    val activityTypeId: String,
    val icon: String,
    val color: Long,
    val positivityScore: Int,
    val startMinute: Int,
    val endMinute: Int,
    val durationMs: Long,
    val startAt: Long
)
