package com.d_drostes_apps.aevum.ui.screens.placetimeline

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.data.repository.UnknownPlaceSessionRepository
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceDaySummary
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceDaySummaryCalculator
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceTimelineEngine
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceVisit
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * M18.83: Place Timeline — "Wo war ich wann?" als Google-Maps-artige
 * Zeitachse. Liest die EXISTIERENDE Evidenz (Sessions mit Trigger-Link,
 * Roh-Trigger-Paare, benannte Orte) und leitet daraus Visits ab.
 * Read-only: keine Schreibpfade, keine Migrations-Risiken (ADR in
 * PlaceTimelineModels.kt).
 *
 * Screen-Strings werden in der UI via stringResource() lokalisiert;
 * dieser ViewModel-Kreis braucht darum KEINE Language-FlatMap — alle
 * Anzeigetexte sind formatierte Zahlen/Zeiten (gebietsschema-Parameter
 * kommen aus TimeFormatting) oder Geofence-Namen (User-Daten).
 */
@HiltViewModel
class PlaceTimelineViewModel @Inject constructor(
    activityRepository: ActivityRepository,
    geofenceRepository: PlaceGeofenceRepository,
    triggerRepository: TriggerEventRepository,
    unknownPlaceRepository: UnknownPlaceSessionRepository,
    // M18.86: Track-Punkte für echte Strecken auf der Karte (ADR-0030).
    private val trackPointRepository: com.d_drostes_apps.aevum.data.repository.LocationTrackPointRepository,
    // M18.88: Live-Zone als letzte Instanz — jeder Tag zeigt mind. den
    // aktuellen Aufenthalt ("Ich bin immer irgendwo", kein "heute leer").
    private val zoneProvider: com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider
) : ViewModel() {

    private val zoneId: ZoneId = ZoneId.systemDefault()

    // ── Tag-Auswahl ──
    private val _selectedDate = MutableStateFlow(LocalDate.now())

    fun previousDay() = _selectedDate.update { it.minusDays(1) }
    fun nextDay() = _selectedDate.update { it.plusDays(1) }
    fun today() = _selectedDate.update { LocalDate.now() }

    private data class Inputs(
        val date: LocalDate,
        val sessions: List<ActivitySession>,
        val triggers: List<TriggerEvent>,
        val geofences: List<PlaceGeofence>,
        val namedPlaces: List<UnknownPlaceSession>
    )

    // 60s-Ticker (M18.42-Muster): Room-Flows emittieren nur bei DB-Änderungen —
    // laufende Visits (isOngoing) und die "Unterwegs"-Offenheit zwischen den
    // Orten sollen aber mit der Uhrzeit weiterwachsen.
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    // combine-Operator-Limit: 6 Flows pragmatisch über verschachteltes combine
    // (M18.42/57-Muster, Paar-Entpackung im Lambda).
    private val sessionsAndTriggers = combine(
        activityRepository.getAll(),
        triggerRepository.getAll()
    ) { sessions, triggers -> sessions to triggers }

    // M18.86: Track-Punkte asynchron nachladen (suspend, einmal pro Tag-
    private val _trackPoints = MutableStateFlow<List<com.d_drostes_apps.aevum.data.model.LocationTrackPoint>>(emptyList())
    val trackPoints: StateFlow<List<com.d_drostes_apps.aevum.data.model.LocationTrackPoint>> = _trackPoints.asStateFlow()

    private suspend fun loadTrackPoints(date: LocalDate) {
        try {
            val zone = ZoneId.systemDefault()
            val dayStart = TimeFormatting.startOfDayMillis(date, zone)
            val dayEnd = dayStart + MILLIS_PER_DAY
            // Puffer: Strecken, die kurz vor/nach Mitternacht laufen,
            // gehören visuell zu beiden Tagen — ±30 Min Fenster.
            _trackPoints.value = trackPointRepository.getByTimeRange(
                dayStart - 30 * 60 * 1000L,
                dayEnd + 30 * 60 * 1000L
            )
        } catch (e: Exception) {
            Log.w("PlaceTimelineVM", "M18.86: Track-Punkte laden fehlgeschlagen: ${e.message}")
            _trackPoints.value = emptyList()
        }
    }

    init {
        // Beim Start + bei jedem Tagwechsel die Track-Punkte nachladen.
        viewModelScope.launch {
            _selectedDate.collect { date ->
                loadTrackPoints(date)
            }
        }
    }

    private val selectedDate: MutableStateFlow<LocalDate> = _selectedDate
    // M18.88: Live-Zone als StateFlow — der kombinierte inputs-Flow re-
    // emittiert, wenn sich die Zone ändert (Zonenwechsel während der
    // Timeline offen ist → Visit-Enden/Starts aktualisieren sich live).
    private val currentZoneId: kotlinx.coroutines.flow.Flow<String?> = zoneProvider.currentZone
        .map { it?.geofence?.id }

    // M18.88: Bestätigungszeitpunkt der Zone (ZoneInfo.updatedAt) — Start-
    // anker der Live-Zone-Station (verhindert wandernde Startzeit).
    private val currentZoneSince: kotlinx.coroutines.flow.Flow<Long?> =
        zoneProvider.currentZone.map { it?.updatedAt }

    // M18.88: Zone + Ticker + Track-Punkte als inneres Paar — combine-Operator-
    // Limit (5 Flows) pragmatisch verschachtelt (M18.42/57-Muster).
    private data class Inputs2(
        val date: LocalDate,
        val sessions: List<ActivitySession>,
        val triggers: List<TriggerEvent>,
        val geofences: List<PlaceGeofence>,
        val namedPlaces: List<UnknownPlaceSession>,
        val zoneGeofenceId: String?,
        val zoneSinceMs: Long?,
        val trackPoints: List<com.d_drostes_apps.aevum.data.model.LocationTrackPoint>
    )

    private val zoneTimeTracks = combine(
        currentZoneId,
        currentZoneSince,
        ticker,
        _trackPoints
    ) { zoneId, zoneSince, nowMs, tracks -> listOf(zoneId, zoneSince, nowMs, tracks) }

    private val inputs: kotlinx.coroutines.flow.Flow<Inputs2> = combine(
        selectedDate,
        sessionsAndTriggers,
        geofenceRepository.getAll(),
        unknownPlaceRepository.getAll(),
        zoneTimeTracks
    ) { date: LocalDate,
        pair: Pair<List<ActivitySession>, List<TriggerEvent>>,
        geofences: List<PlaceGeofence>,
        namedPlaces: List<UnknownPlaceSession>,
        z: List<Any?> ->
        @Suppress("UNCHECKED_CAST")
        Inputs2(
            date, pair.first, pair.second, geofences, namedPlaces,
            z[0] as String?,
            z[1] as Long?,
            z[3] as List<com.d_drostes_apps.aevum.data.model.LocationTrackPoint>
        )
    }

    val uiState: StateFlow<PlaceTimelineUiState> = inputs
        .map { i -> buildState(i.date, i.sessions, i.triggers, i.geofences, i.namedPlaces, i.zoneGeofenceId, i.zoneSinceMs, i.trackPoints, System.currentTimeMillis()) }
        .catch { e ->
            Log.e("PlaceTimelineVM", "uiState combine() failed — emitting default state", e)
            emitAll(flowOf(PlaceTimelineUiState()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceTimelineUiState())

    private fun buildState(
        date: LocalDate,
        sessions: List<ActivitySession>,
        triggers: List<TriggerEvent>,
        geofences: List<PlaceGeofence>,
        namedPlaces: List<UnknownPlaceSession>,
        currentZoneGeofenceId: String?,
        currentZoneSinceMs: Long?,
        trackPoints: List<com.d_drostes_apps.aevum.data.model.LocationTrackPoint>,
        nowMs: Long
    ): PlaceTimelineUiState {
        val dayStart = TimeFormatting.startOfDayMillis(date, zoneId)
        val dayEnd = dayStart + MILLIS_PER_DAY
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = dayStart,
            dayEnd = dayEnd,
            sessions = sessions,
            triggers = triggers,
            geofences = geofences.filter { it.deletedAt == null },
            namedPlaces = namedPlaces,
            nowMs = nowMs,
            trackPoints = trackPoints,
            currentZoneGeofenceId = currentZoneGeofenceId,
            currentZoneSinceMs = currentZoneSinceMs
        )
        return PlaceTimelineUiState(
            selectedDate = date,
            dayTitle = TimeFormatting.formatDayTitle(date),
            visits = visits,
            summary = PlaceDaySummaryCalculator.calculate(visits, dayStart, dayEnd),
            hasData = visits.isNotEmpty(),
            isToday = date == LocalDate.now()
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * UI-State der Place Timeline. Rohwerte + vorformatierte Strings — die UI
 * macht keine Business-Logik.
 */
data class PlaceTimelineUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val dayTitle: String = "",
    val visits: List<PlaceVisit> = emptyList(),
    val summary: PlaceDaySummary? = null,
    val hasData: Boolean = false,
    val isToday: Boolean = false
)