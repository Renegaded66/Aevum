package com.d_drostes_apps.aevum.ui.screens.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.automation.calendar.CalendarReader
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CalendarEventPinRepository
import com.d_drostes_apps.aevum.data.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 *
 * M18.132: [refreshFromCalendar] hält den Cache beim Öffnen frisch —
 * vorher konnte die Liste stundenalt sein (periodischer Sync: 6 h),
 * was als „meine Termine fehlen" wahrgenommen wurde.
 */
@HiltViewModel
class CalendarEventPickerViewModel @Inject constructor(
    application: Application,
    private val calendarRepository: CalendarRepository,
    private val pinRepository: CalendarEventPinRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    /** M18.132: für den Öffnungs-Sync (Feature-Schalter lesen). */
    private val settingsDao: com.d_drostes_apps.aevum.data.db.AutomationSettingsDao,
    /** M18.132: für den Öffnungs-Sync (Kalender lesen). */
    private val calendarReader: com.d_drostes_apps.aevum.automation.calendar.CalendarReader
) : AndroidViewModel(application) {

    companion object {
        /**
         * M18.132: Ein Cache, der jünger ist, wird beim Öffnen NICHT neu
         * gelesen. 15 Minuten decken den Praxisfall ab („gerade im Handy
         * angelegt → App öffnen → soll da sein"), ohne bei jedem Öffnen
         * den ContentResolver zu bemühen.
         */
        const val STALE_AFTER_MS = 15L * 60 * 1000

        /**
         * M18.132: Länge der Termin-Auswahl-Ansicht — der Auftrag:
         * „eine Kalender-Ansicht ab dem aktuellen Zeitpunkt für die
         * nächsten 7 Tage". Der Sync des Readers liefert 9 Tage Fenster
         * (heute−1 bis heute+8), deckelt die Ansicht also zu jedem
         * Zeitpunkt vollständig.
         */
        const val DAYS_TO_SHOW = 7
    }

    private val zoneId: ZoneId = ZoneId.systemDefault()

    /**
     * M18.132: Anker der 7-Tage-Ansicht — der Tag, an dem die Seite
     * geöffnet wurde.
     *
     * WARUM ANKER STATT TAGES-NAVIGATION: Der Auftrag will „eine
     * Kalender-Ansicht ab dem aktuellen Zeitpunkt für die nächsten 7
     * Tage" — alle Termine der Woche untereinander, anklickbar, wie ein
     * Google-Kalender. Vor-/Zurück-Navigation einzelner Tage wäre ein
     * anderes (umständlicheres) Bedienmodell.
     *
     * WARUM NICHT LocalDate.now() BEI JEDER BEREchnung: sitzt die Seite
     * über Mitternacht offen, würde sich die Liste unter dem Nutzer
     * verschieben (Tag 7 fällt hinten raus, „heute" wandert). Der
     * Anker friert die Sicht ein; beim nächsten Öffnen wird er neu
     * gesetzt (das ViewModel gehört zum Navigation-Eintrag und wird
     * mit jedem Öffnen neu erstellt).
     */
    private val _anchorDate = MutableStateFlow(LocalDate.now())

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

    /**
     * M18.132: Sync-Status für die Kopfzeile („Termine werden synchroni-
     * siert …"), damit der Nutzer versteht, warum die Liste kurz leer ist.
     */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /**
     * M18.132: Wird beim Öffnen der Seite gestartet — hält den Cache
     * FRISCH, ohne einen Button zu brauchen.
     *
     * WARUM DAS NÖTIG IST: Der Picker liest nur den Cache. Der periodische
     * Sync läuft nur alle 6 h (Default) — nach einem Termin, der gerade
     * erst im Handy-Kalender angelegt wurde, hätte die Liste ihn erst
     * Stunden später gezeigt (Symptom: „meine echten Termine werden gar
     * nicht angezeigt"). Ein Sync beim Öffnen schließt diese Lücke, ohne
     * den Akku zu belasten: EIN Lesen pro Seitenöffnung, nicht im Takt.
     *
     * GATES (bewusst alle in der Reihenfolge): Feature aktiviert?
     * Permission? Cache überhaupt da / älter als [STALE_AFTER_MS]?
     * Sonst wird das Lesen gespart — ein frischer Cache (z. B. durch
     * den manuellen Sync auf der Einstellungs-Seite) bleibt unangetastet.
     */
    fun refreshFromCalendar() {
        if (_syncing.value) return
        viewModelScope.launch {
            // Gate 1: Feature-Schalter — ist der Kalender aus, hat der
            // Nutzer hier nichts zu suchen und die Seite zeigt ihren
            // Aktivierungs-Hinweis.
            val settings = try {
                settingsDao.getSettingsSync()
            } catch (e: Exception) {
                null
            }
            if (settings?.calendarSyncEnabled != true) return@launch
            // Gate 2: Permission (Widerruf jederzeit möglich).
            if (!calendarReader.hasPermission()) return@launch
            // Gate 3: Nur wenn nötig — Cache leer oder älter als 5 min.
            // Die UI ist reaktiv: sobald der Sync schreibt, erscheinen
            // die Termine von selbst.
            val lastSync = try {
                calendarRepository.getLastSyncedAt()
            } catch (e: Exception) {
                null
            }
            val stale = lastSync == null || System.currentTimeMillis() - lastSync > STALE_AFTER_MS
            if (!stale) return@launch

            _syncing.value = true
            try {
                val result = withContext(Dispatchers.IO) { calendarReader.readEvents() }
                if (result is CalendarReader.CalendarReadResult.Success) {
                    val (from, _) = calendarReader.window()
                    calendarRepository.replaceWindow(result.events, pruneBefore = from)
                    calendarRepository.markSynced(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // Der Cache bleibt, was er ist — die Seite funktioniert
                // weiter, nur eben mit altem Stand. Kein Crash.
                android.util.Log.w("CalendarEventPickerVM", "Auto-Sync fehlgeschlagen: ${e.message}")
            } finally {
                _syncing.value = false
            }
        }
    }

    val uiState: StateFlow<CalendarEventPickerUiState> = combine(
        _anchorDate,
        calendarRepository.getEvents(),
        pinRepository.getAll(),
        activityTypeRepository.getAll()
    ) { anchor: LocalDate, events: List<CalendarEventCache>, pins: List<CalendarEventPin>, types: List<ActivityType> ->
        buildState(anchor, events, pins, types)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CalendarEventPickerUiState()
    )
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
     * Baut den Anzeigezustand für die 7-Tage-Ansicht ab dem Anker-Tag.
     *
     * Die Termine werden pro Tag gefiltert (echter Overlap, damit ein
     * über Mitternacht laufender Termin an beiden Tagen sichtbar ist)
     * und mit ihrer Markierung zusammengeführt. Reine Funktion im
     * Hinblick auf die Eingaben — dadurch ist die Zuordnung testbar,
     * ohne Android zu brauchen.
     */
    private fun buildState(
        anchor: LocalDate,
        events: List<CalendarEventCache>,
        pins: List<CalendarEventPin>,
        types: List<ActivityType>
    ): CalendarEventPickerUiState {
        val pinByEventId = pins.associateBy { it.eventId }
        val typeById = types.associateBy { it.id }
        val now = System.currentTimeMillis()

        fun rowFor(event: CalendarEventCache): CalendarEventRowUi {
            val pin = pinByEventId[event.eventId]
            val type = pin?.activityTypeId?.let { typeById[it] }
            return CalendarEventRowUi(
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

        val days = (0 until DAYS_TO_SHOW).map { offset ->
            val date = anchor.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            CalendarDaySectionUi(
                date = date,
                isToday = date == LocalDate.now(zoneId),
                events = events
                    .filter { it.startAt < dayEnd && it.endAt > dayStart }
                    .sortedBy { it.startAt }
                    .map(::rowFor)
            )
        }

        return CalendarEventPickerUiState(
            anchorDate = anchor,
            days = days,
            activityTypes = types.sortedBy { it.name.lowercase() },
            totalEventsInCache = events.size,
            pinnedCount = pins.size
        )
    }
}

/** M18.132: Zustand der Termin-Auswahl-Seite (7-Tage-Ansicht ab heute). */
data class CalendarEventPickerUiState(
    val anchorDate: LocalDate = LocalDate.now(),
    /** Die 7 Tages-Sektionen ab dem Anker-Tag (inkl. heute). */
    val days: List<CalendarDaySectionUi> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val totalEventsInCache: Int = 0,
    val pinnedCount: Int = 0
)

/**
 * M18.132: Eine Tages-Sektion in der 7-Tage-Ansicht — der Kalender-
 * Header (Datum) plus alle Termine dieses Tags (echter Overlap).
 */
data class CalendarDaySectionUi(
    val date: LocalDate,
    /** true = dieser Abschnitt ist „heute" (visuell hervorgehoben). */
    val isToday: Boolean,
    val events: List<CalendarEventRowUi>
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

