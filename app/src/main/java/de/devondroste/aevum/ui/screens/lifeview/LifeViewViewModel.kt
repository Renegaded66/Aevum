package de.devondroste.aevum.ui.screens.lifeview

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.DailyAllowance
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.DailyAllowanceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * M18.35: LifeView — die Lebenszeit-Ansicht.
 *
 * Konzept (hinterfragt & reflektiert):
 * Der User will "Angst machen" — aber nicht mit Worten, sondern mit
 * nackten Zahlen und Grafiken. Die ehrlichste und wirkungsvollste
 * Visualisierung ist die klassische "Life Calendar": JEDER MONAT des
 * Lebens ist eine Zelle. Verbrauchte Monate leuchten, verbleibende
 * sind dunkel. Dazu kommen Hochrechnungen:
 *
 *  - Schlaf: Tagesdurchschnitt aus den letzten 14 Tagen MIT Schlaf-
 *    Daten (nur Tage mit Daten, sonst verzerrt der Durchschnitt),
 *    hochgerechnet auf die verbleibenden Jahre.
 *  - Autofahren/Transport: echter Tagesdurchschnitt ueber 14 Tage
 *    (0-Tage zaehlen — man faehrt nicht jeden Tag).
 *  - Tagespauschalen: enabled Allowances × Minuten/Tag.
 *  - Alle anderen erfassten Aktivitaeten: Rest.
 *
 * Erwartetes Alter: 80 (konfigurierbar, Default 80).
 * Geburtstag: wird in SharedPreferences persistiert.
 */
@HiltViewModel
class LifeViewViewModel @Inject constructor(
    private val application: Application,
    private val activityRepository: ActivityRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val dailyAllowanceRepository: DailyAllowanceRepository
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()
    private val prefs = application.getSharedPreferences("aevum_lifeview", android.content.Context.MODE_PRIVATE)

    val expectedAge: Int
        get() = prefs.getInt(KEY_EXPECTED_AGE, 80)

    val birthday: LocalDate?
        get() = prefs.getString(KEY_BIRTHDAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun saveBirthday(date: LocalDate) {
        prefs.edit().putString(KEY_BIRTHDAY, date.toString()).apply()
    }

    fun saveExpectedAge(age: Int) {
        prefs.edit().putInt(KEY_EXPECTED_AGE, age.coerceIn(60, 100)).apply()
    }

    val uiState: StateFlow<LifeViewUiState> = combine(
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        dailyAllowanceRepository.getAll()
    ) { sessions, types, allowances ->
        buildState(sessions, types, allowances)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LifeViewUiState())

    private fun buildState(
        sessions: List<ActivitySession>,
        types: List<ActivityType>,
        allowances: List<DailyAllowance>
    ): LifeViewUiState {
        val today = LocalDate.now()
        val bday = birthday
        val age = bday?.let { Period.between(it, today).years } ?: 0
        val remainingYears = (expectedAge - age).coerceAtLeast(0)
        val remainingDays = (remainingYears * 365.25).roundToInt()
        val totalMonths = expectedAge * 12
        val livedMonths = (age * 12 + (bday?.let { Period.between(it, today).months } ?: 0)).coerceIn(0, totalMonths)

        val typeMap = types.associateBy { it.id }
        val active = sessions.filter { it.deletedAt == null }

        // --- Tagesdurchschnitte aus den letzten 14 Tagen ---
        val days = 14
        val startMs = today.minusDays(days.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val recent = active.filter { it.endAt != null && it.endAt > startMs && it.startAt < today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() }

        // Schlaf: nur Tage MIT Schlaf-Daten (Durchschnitt der Schlaf-Naechte)
        val sleepSessions = recent.filter { it.activityTypeId == "sleep" || it.categoryId == "sleep" }
        val sleepDaysWithData = sleepSessions.map { it.startAt }.map { java.time.Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }.distinct().size
        val sleepMsPerDay = if (sleepDaysWithData >= 3) {
            sleepSessions.sumOf { (it.endAt ?: it.startAt) - it.startAt } / sleepDaysWithData
        } else {
            // Nicht genug Daten -> Default 8h (realistischer Schätzwert)
            8L * 60 * 60 * 1000
        }

        // Autofahren/Transport: echter Tagesdurchschnitt (0-Tage zaehlen)
        val drivingSessions = recent.filter { it.activityTypeId == "driving" || it.activityTypeId == "transport" }
        val drivingMsPerDay = drivingSessions.sumOf { (it.endAt ?: it.startAt) - it.startAt } / days

        // Pauschalen: enabled Allowances
        val allowanceMsPerDay = allowances.filter { it.enabled }.sumOf { it.minutesPerDay * 60_000L }

        // Alle anderen erfassten Aktivitaeten
        val otherSessions = recent.filter {
            it.activityTypeId != "sleep" && it.categoryId != "sleep" &&
                it.activityTypeId != "driving" && it.activityTypeId != "transport"
        }
        val otherMsPerDay = otherSessions.sumOf { (it.endAt ?: it.startAt) - it.startAt } / days

        // --- Hochrechnung auf das verbleibende Leben ---
        fun yearsFor(msPerDay: Long): Double = msPerDay * remainingDays / (365.25 * 24 * 60 * 60 * 1000.0)

        val sleepYears = yearsFor(sleepMsPerDay)
        val drivingYears = yearsFor(drivingMsPerDay)
        val allowanceYears = yearsFor(allowanceMsPerDay)
        val otherYears = yearsFor(otherMsPerDay)
        val accountedYears = sleepYears + drivingYears + allowanceYears + otherYears
        val freeYears = (remainingYears - accountedYears).coerceAtLeast(0.0)

        val breakdown = listOf(
            LifeSlice("Schlaf", sleepYears, 0xFF8B7CFF, "😴"),
            LifeSlice("Autofahren", drivingYears, 0xFFF59E0B, "🚗"),
            LifeSlice("Pauschalen", allowanceYears, 0xFF2DD4BF, "⏱"),
            LifeSlice("Erfasst", otherYears, 0xFF66BB6A, "📊"),
            LifeSlice("Frei", freeYears, 0xFF3A3F52, "✨")
        )

        // Aktivitaets-Details (Top 5 nach Lebensjahren)
        val activityYears = active
            .filter { it.endAt != null }
            .groupBy { it.activityTypeId ?: "other" }
            .map { (typeId, list) ->
                val ms = list.sumOf { (it.endAt ?: it.startAt) - it.startAt }
                val perDay = ms / days
                val type = typeMap[typeId]
                LifeActivityDetail(
                    typeId = typeId,
                    name = type?.name ?: "Sonstiges",
                    icon = type?.icon?.takeIf { it.isNotBlank() } ?: "•",
                    color = type?.color?.takeIf { it != 0L } ?: 0xFF66BB6A,
                    years = yearsFor(perDay),
                    minutesPerDay = (perDay / 60_000.0).roundToInt()
                )
            }
            .sortedByDescending { it.years }
            .take(6)

        return LifeViewUiState(
            birthday = bday,
            age = age,
            expectedAge = expectedAge,
            remainingYears = remainingYears,
            remainingDays = remainingDays,
            totalMonths = totalMonths,
            livedMonths = livedMonths,
            sleepYears = sleepYears,
            drivingYears = drivingYears,
            allowanceYears = allowanceYears,
            otherYears = otherYears,
            freeYears = freeYears,
            breakdown = breakdown,
            activityDetails = activityYears,
            hasBirthday = bday != null
        )
    }

    companion object {
        private const val KEY_BIRTHDAY = "birthday"
        private const val KEY_EXPECTED_AGE = "expected_age"
    }
}

data class LifeViewUiState(
    val birthday: LocalDate? = null,
    val age: Int = 0,
    val expectedAge: Int = 80,
    val remainingYears: Int = 0,
    val remainingDays: Int = 0,
    val totalMonths: Int = 960,
    val livedMonths: Int = 0,
    val sleepYears: Double = 0.0,
    val drivingYears: Double = 0.0,
    val allowanceYears: Double = 0.0,
    val otherYears: Double = 0.0,
    val freeYears: Double = 0.0,
    val breakdown: List<LifeSlice> = emptyList(),
    val activityDetails: List<LifeActivityDetail> = emptyList(),
    val hasBirthday: Boolean = false
)

data class LifeSlice(
    val label: String,
    val years: Double,
    val color: Long,
    val icon: String
)

data class LifeActivityDetail(
    val typeId: String,
    val name: String,
    val icon: String,
    val color: Long,
    val years: Double,
    val minutesPerDay: Int
)
