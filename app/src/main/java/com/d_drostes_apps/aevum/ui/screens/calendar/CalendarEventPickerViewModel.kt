package com.d_drostes_apps.aevum.ui.screens.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CalendarEventPinRepository
import com.d_drostes_apps.aevum.data.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * M18.131: ViewModel für die Termin-Auswahl (Kalender → Activity).
 *
 * DER ABLAUF, den diese Seite abbildet:
 *  1. Der Nutzer sieht seine echten Kalender-Termine als Liste.
 *  2. Er tippt einen Termin an und wählt die Aktivität, die aufgezeichnet
 *     werden soll.
 *  3. Aevum zeichnet ab Terminbeginn bis Terminende auf — genau für diesen
 *     einen Termin (nicht für die ganze Serie, nicht per Textregel).
 *
 * WARUM DIESES FEATURE NICHT ÜBER REGELN LÄUFT:
 * Regeln matchen über Textmuster und erfassen damit zwangsläufig jedes
 * Vorkommen (siehe CalendarEventPin-Doku). Hier geht es um den einzelnen
 * Termin.
 *
 * Die Seite ist bewusst NUR die Auswahl: das Aufzeichnen selbst macht der
 * bestehende [com.d_drostes_apps.aevum.automation.calendar.CalendarAutoRunWorker].
 * So gibt es genau eine Stelle, die Aufzeichnungen startet und stoppt.
 */
@HiltViewModel
class CalendarEventPickerViewModel @Inject constructor(
    application: Application,
    private val calendarRepository: CalendarRepository,
    private val pinRepository: CalendarEventPinRepository,
    private val activityTypeRepository: ActivityTypeRepository
) : AndroidViewModel(application) {

    private val zoneId: ZoneId = ZoneId.systemDefault()

    /** Gewählter Tag (Tages-Navigation). */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    /** Aktuell geöffneter Termin (Dialog). null = geschlossen. */
    private val _editingEvent = MutableStateFlow<CalendarEventCache?>(null)
    val editingEvent: StateFlow<CalendarEventCache?> = _editingEvent

    /**
     * M18.131: Vorbelegung des Dialogs für den geöffneten Termin.
     *
     * WARUM DAS AUS DEM VIEWMODEL KOMMT UND NICHT AUS DER UI:
     * Aktivität, eigener Titel und Overlap-Verhalten eines bereits
     * markierten Termins stehen in der [CalendarEventPin]-Zeile — nicht im
     * Termin selbst und nicht in der Listen-Darstellung. Der Dialog braucht
     * sie als Startwerte, damit „öffnen → speichern" eine bestehende
     * Zuordnung nicht stillschweigend zurücksetzt.
     */
    private val _editingPin = MutableStateFlow<CalendarEventPin?>(null)
    val editingPin: StateFlow<CalendarEventPin?> = _editingPin

    /** Bestätigungs-Meldung nach dem Speichern/Entfernen (transient). */
    private val _message = MutableStateFlow<PickerMessage?>(null)
    val message: StateFlow<PickerMessage?> = _message

    val uiState: StateFlow<CalendarEventPickerUiState> = combine(
        _selectedDate,
        calendarRepository.getEvents(),
        pinRepository.getAll(),
        activityTypeRepository.getAll()
    ) { date: LocalDate, events: List<CalendarEventCache>, pins: List<CalendarEventPin>, types: List<ActivityType> ->
        buildState(date, events, pins, types)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CalendarEventPickerUiState()
    )

    fun previousDay() { _selectedDate.value = _selectedDate.value.minusDays(1) }
    fun nextDay() { _selectedDate.value = _selectedDate.value.plusDays(1) }
    fun today() { _selectedDate.value = LocalDate.now() }
    fun selectDate(date: LocalDate) { _selectedDate.value = date }

    /**
     * Öffnet den Dialog für einen Termin und lädt eine eventuell
     * vorhandene Markierung als Vorbelegung.
     *
     * Der Termin muss vollständig vorliegen (nicht nur seine ID), weil der
     * Dialog Titel, Kalendername und Zeitraum anzeigt — deshalb übergibt
     * die Liste den kompletten Termin aus dem Zustand.
     */
    fun openEvent(event: CalendarEventCache) {
        _editingPin.value = null // erst leeren, damit kein alter Pin durchscheint
        _editingEvent.value = event
        viewModelScope.launch {
            // Asynchron nachladen — der Dialog öffnet sofort und bekommt
            // die Vorbelegung, sobald sie da ist (kein UI-Blockieren).
            _editingPin.value = pinRepository.getByIdOnce(event.eventId)
        }
    }

    fun closeEvent() {
        _editingEvent.value = null
        _editingPin.value = null
    }

    /**
     * Markiert einen Termin zur Aufzeichnung.
     *
     * Die Zeitangaben werden MITKOPIERT (nicht nur der Schlüssel): der Pin
     * muss lesbar und stoppbar bleiben, auch wenn der Termin später aus dem
     * Sync-Fenster fällt (siehe CalendarEventPin-Doku).
     */
    fun pinEvent(
        event: CalendarEventCache,
        activityTypeId: String,
        customTitle: String?,
        overlapPolicy: String
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = pinRepository.getByIdOnce(event.eventId)
            pinRepository.upsert(
                CalendarEventPin(
                    eventId = event.eventId,
                    activityTypeId = activityTypeId,
                    defaultTitle = customTitle?.trim()?.takeIf { it.isNotEmpty() },
                    eventStartAt = event.startAt,
                    eventEndAt = event.endAt,
                    eventTitle = event.title,
                    calendarName = event.calendarName,
                    overlapPolicy = overlapPolicy,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
            _editingEvent.value = null
            _message.value = PickerMessage.Saved
        }
    }

    /** Entfernt die Markierung — es wird dann nichts mehr aufgezeichnet. */
    fun unpinEvent(eventId: String) {
        viewModelScope.launch {
            pinRepository.remove(eventId)
            _editingEvent.value = null
            _message.value = PickerMessage.Removed
        }
    }

    fun openCalendarSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + getApplication<Application>().packageName)
        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        getApplication<Application>().startActivity(intent)
    }

    fun consumeMessage() { _message.value = null }

    /**
     * Baut den Anzeigezustand für einen Tag.
     *
     * Die Termine werden auf den gewählten Tag gefiltert (echter Overlap,
     * damit ein über Mitternacht laufender Termin an beiden Tagen sichtbar
     * ist) und mit ihrer Markierung zusammengeführt. Reine Funktion im
     * Hinblick auf die Eingaben — dadurch ist die Zuordnung testbar, ohne
     * Android zu brauchen.
     */
    private fun buildState(
        date: LocalDate,
        events: List<CalendarEventCache>,
        pins: List<CalendarEventPin>,
        types: List<ActivityType>
    ): CalendarEventPickerUiState {
        val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val pinByEventId = pins.associateBy { it.eventId }
        val typeById = types.associateBy { it.id }
        val now = System.currentTimeMillis()

        val dayEvents = events
            .filter { it.startAt < dayEnd && it.endAt > dayStart }
            .sortedBy { it.startAt }
            .map { event ->
                val pin = pinByEventId[event.eventId]
                val type = pin?.activityTypeId?.let { typeById[it] }
                CalendarEventRowUi(
                    eventId = event.eventId,
                    title = event.title.ifBlank { "" },
                    calendarName = event.calendarName,
                    startAt = event.startAt,
                    endAt = event.endAt,
                    allDay = event.allDay,
                    isPinned = pin != null,
                    pinnedActivityName = type?.name,
                    pinnedActivityIcon = type?.icon,
                    pinnedActivityColor = type?.color ?: 0L,
                    // Eine Markierung, deren Aktivität gelöscht wurde, ist
                    // inert — die UI muss das ehrlich zeigen statt eine
                    // Aufzeichnung zu suggerieren, die nicht stattfindet.
                    pinActivityMissing = pin != null && pin.activityTypeId == null,
                    isPast = event.endAt <= now,
                    isRunning = event.startAt <= now && event.endAt > now,
                    // Der Dialog braucht den vollständigen Termin (Titel,
                    // Kalendername, Zeitraum) — deshalb reist er mit. So
                    // muss die UI ihn nicht aus Einzelfeldern rekonstruieren.
                    event = event
                )
            }

        return CalendarEventPickerUiState(
            selectedDate = date,
            events = dayEvents,
            activityTypes = types.sortedBy { it.name.lowercase() },
            totalEventsInCache = events.size,
            pinnedCount = pins.size,
            dayStartMs = dayStart,
            dayEndMs = dayEnd
        )
    }
}

/** M18.131: Zustand der Termin-Auswahl-Seite. */
data class CalendarEventPickerUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val events: List<CalendarEventRowUi> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val totalEventsInCache: Int = 0,
    val pinnedCount: Int = 0,
    val dayStartMs: Long = 0L,
    val dayEndMs: Long = 0L
)

/** M18.131: Ein Termin in der Liste, mit seiner Markierung (falls vorhanden). */
data class CalendarEventRowUi(
    val eventId: String,
    val title: String,
    val calendarName: String,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean,
    val isPinned: Boolean,
    val pinnedActivityName: String?,
    val pinnedActivityIcon: String?,
    val pinnedActivityColor: Long,
    val pinActivityMissing: Boolean,
    val isPast: Boolean,
    val isRunning: Boolean,
    /**
     * Der vollständige Termin — nötig, damit der Auswahl-Dialog Titel,
     * Kalendername und Zeitraum zeigen kann, ohne dass die UI die Felder
     * wieder zusammensetzen muss (fehleranfällig und doppelte Wahrheit).
     */
    val event: CalendarEventCache
)

/** M18.131: Transiente Rückmeldungen der Seite. */
enum class PickerMessage {
    Saved,
    Removed
}

/**
 * M18.131: Standard-Overlap-Policy für neu markierte Termine.
 *
 * OVERRIDE (Aevum-Standard): der Termin übernimmt, eine laufende
 * Aufzeichnung wird an der Termingrenze abgeschnitten. Das entspricht der
 * Erwartung „dieser Termin wird aufgezeichnet" — ONLY_IF_IDLE würde bei
 * einer zufällig laufenden Aufzeichnung stillschweigend nichts tun, was
 * den Nutzer im Unklaren ließe, dass sein Termin nicht erfasst wird.
 */
val DEFAULT_PIN_OVERLAP_POLICY: String = CalendarOverlapPolicy.OVERRIDE

/** M18.131: Millisekunden eines Zeitpunkts für die Anzeige (nur Formatierung). */
internal fun pickerIsSameDay(millis: Long, other: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(other).atZone(zone).toLocalDate()
