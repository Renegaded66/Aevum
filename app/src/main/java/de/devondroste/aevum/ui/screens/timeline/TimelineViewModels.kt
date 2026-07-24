package de.devondroste.aevum.ui.screens.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.model.Tag
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.TagRepository
import de.devondroste.aevum.data.repository.TriggerEventRepository
import de.devondroste.aevum.domain.automation.ReviewCandidateUseCase
import de.devondroste.aevum.domain.activity.ManualActivityRequest
import de.devondroste.aevum.domain.activity.SaveManualActivityResult
import de.devondroste.aevum.domain.activity.SaveManualActivityUseCase
import de.devondroste.aevum.domain.activity.SessionTimeValidator
import de.devondroste.aevum.domain.activity.SessionValidationResult
import de.devondroste.aevum.domain.seed.EnsureDefaultDataUseCase
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.domain.trigger.TriggerEventMarker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class TimelineViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val activityRepository: ActivityRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val triggerEventRepository: TriggerEventRepository,
    private val reviewCandidateUseCase: ReviewCandidateUseCase,
    categoryRepository: CategoryRepository,
    activityTypeRepository: ActivityTypeRepository,
    tagRepository: TagRepository,
    private val ensureDefaultData: EnsureDefaultDataUseCase
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val selectedDate = MutableStateFlow(
        savedStateHandle.get<Long>("date")
            ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            ?: LocalDate.now()
    )

    init { viewModelScope.launch { ensureDefaultData() } }

    private val timelineBase = combine(
        selectedDate,
        activityRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        triggerEventRepository.getAll()
    ) { date: LocalDate, sessions: List<ActivitySession>, candidates: List<ActivityCandidate>, triggers: List<TriggerEvent> ->
        TimelineBase(date, sessions, candidates, triggers)
    }

    // M12.2: Stufenloser Pinch-to-Zoom — pixelsPerHour ist die einzige Quelle
    // der Wahrheit für die Timeline-Höhe. Statt eines enum-basierten
    // 3-Stufen-Modells wird ein Float gespeichert, der via detectTransformGestures
    // zwischen MIN_PIXELS_PER_HOUR (18) und MAX_PIXELS_PER_HOUR (120) skaliert wird.
    private val pixelsPerHour = MutableStateFlow(TimelineUiState.DEFAULT_PIXELS_PER_HOUR)

    val uiState: StateFlow<TimelineUiState> = combine(
        timelineBase,
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        tagRepository.getAll(),
        pixelsPerHour
    ) { base: TimelineBase, categories: List<Category>, types: List<ActivityType>, tags: List<Tag>, pph: Float ->
        buildTimelineState(base.date, base.sessions, base.candidates, base.triggers, categories, types, tags)
            .copy(pixelsPerHour = pph)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    fun previousDay() = selectedDate.update { it.minusDays(1) }
    fun nextDay() = selectedDate.update { it.plusDays(1) }
    fun today() = selectedDate.update { LocalDate.now() }

    /**
     * M12.2: Stufenloser Zoom.
     * Wird vom Pinch-Handler in der Timeline-UI aufgerufen, sobald der User
     * zwei Finger zusammenzieht oder auseinanderzieht. Der neue Wert wird
     * auf den erlaubten Bereich begrenzt.
     */
    fun setPixelsPerHour(pph: Float) {
        pixelsPerHour.value = pph.coerceIn(
            TimelineUiState.MIN_PIXELS_PER_HOUR,
            TimelineUiState.MAX_PIXELS_PER_HOUR
        )
    }

    /**
     * M12.2: Multiplikativer Zoom-Update für den Pinch-Handler.
     * Übergibt einen Skalierungsfaktor > 0 (z. B. 1.1f für "auseinander",
     * 0.9f für "zusammen"). Intern auf MIN/MAX begrenzt.
     */
    fun zoomBy(factor: Float) {
        setPixelsPerHour(pixelsPerHour.value * factor)
    }
    fun acceptCandidate(candidateId: String) { viewModelScope.launch { reviewCandidateUseCase.accept(candidateId) } }
    fun dismissCandidate(candidateId: String) { viewModelScope.launch { reviewCandidateUseCase.dismiss(candidateId) } }

    private fun buildTimelineState(
        date: LocalDate,
        allSessions: List<ActivitySession>,
        allCandidates: List<ActivityCandidate>,
        allTriggers: List<TriggerEvent>,
        categories: List<Category>,
        types: List<ActivityType>,
        tags: List<Tag>
    ): TimelineUiState {
        val dayStart = TimeFormatting.startOfDayMillis(date, zoneId)
        val dayEnd = TimeFormatting.endOfDayMillis(date, zoneId)
        val categoryMap = categories.associateBy { it.id }
        val typeMap = types.associateBy { it.id }

        // M12.2: Vereinheitlichte Timeline.
        // Sessions, Trigger, Schlaf-Sessions und Fahrt-Candidates werden
        // zusammen in einer Lane-basierten Darstellung gezeigt.
        //   - Sessions sind die primären Einträge (voller Inhalt, klickbar)
        //   - Trigger sind Punkte am Zeitstrahl (klein, Marker)
        //   - Schlaf-Sessions (activityTypeId == "sleep") laufen über dieselbe Pipeline
        //   - Fahrt-Candidates (activityTypeId == "driving") werden mit aufgenommen
        //     (Status ACCEPTED → Session-Eintrag, PENDING → Candidate-Marker)
        val filteredSessions = allSessions
            .filter { it.deletedAt == null && SessionTimeValidator.rangesOverlap(dayStart, dayEnd, it.startAt, it.endAt) }
            .sortedBy { it.startAt }

        // M12.2: Auto-Dedup gegen Trigger-Doppel.
        // Trigger mit geofenceId + type + occurredAt, die in einem 90s-Fenster
        // dupliziert vorkommen, werden zu einem zusammengefasst.
        val dayTriggers = allTriggers
            .filter { it.occurredAt >= dayStart && it.occurredAt < dayEnd }
            .sortedBy { it.occurredAt }
            .distinctBy { tripleKey(it.geofenceId, it.type, it.occurredAt / 60_000L) }
        val rows = filteredSessions.map { session ->
            TimelineSessionUi(
                id = session.id,
                title = session.title,
                categoryId = session.categoryId,
                categoryName = categoryMap[session.categoryId]?.name ?: "Sonstiges",
                activityTypeName = typeMap[session.activityTypeId]?.name ?: "Freie Aktivität",
                time = TimeFormatting.formatTime(session.startAt, zoneId),
                range = "${TimeFormatting.formatTime(session.startAt, zoneId)}–${session.endAt?.let { TimeFormatting.formatTime(it, zoneId) } ?: "läuft"}",
                duration = TimeFormatting.formatDuration((session.endAt ?: System.currentTimeMillis()) - session.startAt),
                source = session.sourceType,
                // M12.2: Schlaf-Sessions sind per Konfiguration auto-erfasst.
                // Wir zeigen das via isAuto, damit die UI es konsistent rendert.
                isAuto = session.sourceType in AUTO_SOURCES,
                startMinuteOfDay = TimeFormatting.minutesOfDay(session.startAt, zoneId),
                endMinuteOfDay = session.endAt?.let { TimeFormatting.minutesOfDay(it, zoneId) }
                    ?: TimeFormatting.minutesOfDay(System.currentTimeMillis(), zoneId).coerceIn(0, 1440),
                isRunning = session.endAt == null,
                // M12.2: Overlap-Logik behält die alte Semantik: zwei Sessions
                // mit zeitlicher Überschneidung. Schlaf wird hier bewusst
                // NICHT ausgeschlossen, da parallele Einträge möglich sind
                // (z. B. Schlaf + kurze Erfassung).
                isOverlapping = filteredSessions.any { other -> other.id != session.id && SessionTimeValidator.rangesOverlap(session.startAt, session.endAt, other.startAt, other.endAt) }
            )
        }
        val totalMs = filteredSessions.sumOf { (it.endAt ?: System.currentTimeMillis()) - it.startAt }
        val categoryDurations = filteredSessions.groupBy { it.categoryId ?: "unknown" }
            .mapValues { entry -> entry.value.sumOf { (it.endAt ?: System.currentTimeMillis()) - it.startAt } }
        val triggers = dayTriggers.map { trigger ->
            val geofenceName = extractGeofenceName(trigger.metadataJson)
            val isEnter = trigger.type.contains("ENTER") || trigger.type.contains("ARRIVED")
            val label = if (geofenceName != null) {
                if (isEnter) "$geofenceName betreten" else "$geofenceName verlassen"
            } else {
                trigger.type.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
            }
            TriggerEventUi(
                id = trigger.id,
                label = label,
                time = TimeFormatting.formatTime(trigger.occurredAt, zoneId),
                minuteOfDay = TimeFormatting.minutesOfDay(trigger.occurredAt, zoneId),
                confidence = (trigger.confidence * 100).toInt(),
                source = trigger.source
            )
        }
        val candidates = allCandidates
            .filter { it.startAt < dayEnd && it.endAt > dayStart }
            .sortedBy { it.startAt }
            .map { candidate ->
                CandidateReviewUi(
                    id = candidate.id,
                    title = candidate.suggestedTitle,
                    timeRange = "${TimeFormatting.formatTime(candidate.startAt, zoneId)}–${TimeFormatting.formatTime(candidate.endAt, zoneId)}",
                    duration = TimeFormatting.formatDuration(candidate.endAt - candidate.startAt),
                    reason = candidate.reason ?: "Automatisch erkannt",
                    confidence = (candidate.confidence * 100).toInt()
                )
            }
        return TimelineUiState(
            selectedDate = date,
            dayTitle = TimeFormatting.formatDayTitle(date),
            formattedDate = TimeFormatting.formatDate(date),
            sessions = rows,
            totalTracked = TimeFormatting.formatDuration(totalMs),
            sessionCount = filteredSessions.size,
            categories = categories,
            activityTypes = types,
            tags = tags,
            categoryDurations = categoryDurations,
            triggerEvents = triggers,
            candidates = candidates,
            hasOverlaps = rows.any { it.isOverlapping }
        )
    }

    /**
     * M12.2: Hilfsfunktion für den Auto-Dedup der Trigger.
     * Drei identische Trigger im selben Minuten-Fenster werden zusammengefasst.
     */
    private fun tripleKey(a: String?, b: String, c: Long): String = "${a.orEmpty()}|${b}|${c}"
}

@HiltViewModel
class ActivityEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val activityRepository: ActivityRepository,
    private val activityCandidateRepository: ActivityCandidateRepository,
    categoryRepository: CategoryRepository,
    activityTypeRepository: ActivityTypeRepository,
    tagRepository: TagRepository,
    triggerEventRepository: TriggerEventRepository,
    private val saveManualActivity: SaveManualActivityUseCase,
    private val ensureDefaultData: EnsureDefaultDataUseCase
) : ViewModel() {
    private val sessionId: String? = savedStateHandle["sessionId"]
    private val candidateId: String? = savedStateHandle["candidateId"]
    private val dateArg: Long? = savedStateHandle["date"]
    private val zoneId = ZoneId.systemDefault()
    private val form = MutableStateFlow(ActivityEditorForm())
    private val savedId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            ensureDefaultData()
            initialiseForm()
        }
    }

    private val editorBase = combine(
        form,
        categoryRepository.getAll(),
        activityTypeRepository.getAll()
    ) { formValue: ActivityEditorForm, categories: List<Category>, types: List<ActivityType> ->
        EditorBase(formValue, categories, types)
    }

    val uiState: StateFlow<ActivityEditorUiState> = combine(
        editorBase,
        tagRepository.getAll(),
        triggerEventRepository.getAll(),
        savedId
    ) { base: EditorBase, tags: List<Tag>, triggers: List<TriggerEvent>, saved: String? ->
        val formValue = base.form
        val dayStart = TimeFormatting.startOfDayMillis(formValue.date, zoneId)
        val dayEnd = TimeFormatting.endOfDayMillis(formValue.date, zoneId)
        ActivityEditorUiState(
            isEditing = sessionId != null,
            form = formValue,
            categories = base.categories,
            activityTypes = base.types,
            tags = tags,
            duration = TimeFormatting.formatDuration((formValue.endAt ?: formValue.startAt) - formValue.startAt),
            validation = SessionTimeValidator.validate(formValue.title, formValue.startAt, formValue.endAt, emptyList(), sessionId),
            triggerMarkers = triggers.filter { it.occurredAt in dayStart until dayEnd }.map {
                val gfName = extractGeofenceName(it.metadataJson)
                val isEnter = it.type.contains("ENTER") || it.type.contains("ARRIVED")
                TriggerEventMarker(
                    id = it.id,
                    label = gfName?.let { n -> if (isEnter) "$n betreten" else "$n verlassen" }
                        ?: it.type.replace('_', ' '),
                    occurredAt = it.occurredAt,
                    kind = de.devondroste.aevum.domain.trigger.TriggerEventKind.CUSTOM,
                    source = it.source
                )
            },
            savedSessionId = saved
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityEditorUiState())

    fun setTitle(value: String) = form.update { it.copy(title = value) }
    fun setDescription(value: String) = form.update { it.copy(description = value) }
    fun setCategory(categoryId: String?) = form.update { it.copy(categoryId = categoryId) }
    fun setActivityType(type: ActivityType) = form.update { current ->
        current.copy(
            activityTypeId = type.id,
            categoryId = type.defaultCategoryId ?: current.categoryId,
            title = current.title.ifBlank { type.name }
        )
    }
    fun toggleTag(tagId: String) = form.update { current ->
        current.copy(selectedTagIds = if (tagId in current.selectedTagIds) current.selectedTagIds - tagId else current.selectedTagIds + tagId)
    }
    fun setStartHour(value: Int) = updateStart(hour = value)
    fun setStartMinute(value: Int) = updateStart(minute = value)
    fun setEndHour(value: Int) = updateEnd(hour = value)
    fun setEndMinute(value: Int) = updateEnd(minute = value)
    fun setStartMinuteOfDay(value: Int) = form.update { current ->
        val newStart = TimeFormatting.millisAtMinuteOfDay(current.date, value, zoneId)
        val duration = ((current.endAt ?: current.startAt + ONE_HOUR) - current.startAt).coerceAtLeast(15 * 60 * 1000L)
        current.copy(startAt = newStart, endAt = (newStart + duration).coerceAtMost(TimeFormatting.endOfDayMillis(current.date, zoneId)))
    }
    fun setEndMinuteOfDay(value: Int) = form.update { current ->
        current.copy(endAt = TimeFormatting.millisAtMinuteOfDay(current.date, value, zoneId))
    }
    fun snapStartTo(marker: TriggerEventMarker) = form.update { current ->
        val duration = ((current.endAt ?: current.startAt + ONE_HOUR) - current.startAt).coerceAtLeast(15 * 60 * 1000L)
        current.copy(startAt = marker.occurredAt, endAt = marker.occurredAt + duration)
    }
    fun snapEndTo(marker: TriggerEventMarker) = form.update { it.copy(endAt = marker.occurredAt) }

    fun save() {
        viewModelScope.launch {
            val state = uiState.value
            val selectedTags = state.tags.filter { it.id in state.form.selectedTagIds }
            when (val result = saveManualActivity(
                ManualActivityRequest(
                    id = sessionId,
                    sourceCandidateId = candidateId,
                    title = state.form.title,
                    categoryId = state.form.categoryId,
                    activityTypeId = state.form.activityTypeId,
                    tagIds = state.form.selectedTagIds,
                    tags = selectedTags,
                    startAt = state.form.startAt,
                    endAt = state.form.endAt,
                    timezoneId = zoneId.id,
                    description = state.form.description
                )
            )) {
                is SaveManualActivityResult.Success -> savedId.value = result.sessionId
                is SaveManualActivityResult.Failure -> form.update { it.copy(errorMessage = result.message) }
            }
        }
    }

    private suspend fun initialiseForm() {
        val id = sessionId
        if (id != null) {
            val session = activityRepository.getById(id).first() ?: return
            val tags = activityRepository.getTagIdsForSession(id).first()
            form.value = ActivityEditorForm(
                title = session.title,
                description = session.description.orEmpty(),
                categoryId = session.categoryId,
                activityTypeId = session.activityTypeId,
                selectedTagIds = tags,
                startAt = session.startAt,
                endAt = session.endAt ?: session.startAt + ONE_HOUR,
                date = TimeFormatting.millisToLocalDate(session.startAt, zoneId)
            )
        } else if (candidateId != null) {
            val candidate = activityCandidateRepository.getById(candidateId).first() ?: return
            form.value = ActivityEditorForm(
                title = candidate.suggestedTitle,
                categoryId = candidate.suggestedCategoryId,
                activityTypeId = candidate.activityTypeId,
                startAt = candidate.startAt,
                endAt = candidate.endAt,
                date = TimeFormatting.millisToLocalDate(candidate.startAt, zoneId)
            )
        } else {
            val date = dateArg?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() } ?: LocalDate.now()
            val start = TimeFormatting.parseHourMinuteToMillis(date, java.time.LocalTime.now().hour.coerceAtMost(22), 0, zoneId)
            form.value = ActivityEditorForm(startAt = start, endAt = start + ONE_HOUR, date = date)
        }
    }

    private fun updateStart(hour: Int? = null, minute: Int? = null) = form.update { current ->
        val local = Instant.ofEpochMilli(current.startAt).atZone(zoneId).toLocalTime()
        val newStart = TimeFormatting.parseHourMinuteToMillis(current.date, hour ?: local.hour, minute ?: local.minute, zoneId)
        val duration = ((current.endAt ?: current.startAt + ONE_HOUR) - current.startAt).coerceAtLeast(15 * 60 * 1000L)
        current.copy(startAt = newStart, endAt = newStart + duration)
    }

    private fun updateEnd(hour: Int? = null, minute: Int? = null) = form.update { current ->
        val local = Instant.ofEpochMilli(current.endAt ?: current.startAt).atZone(zoneId).toLocalTime()
        current.copy(endAt = TimeFormatting.parseHourMinuteToMillis(current.date, hour ?: local.hour, minute ?: local.minute, zoneId))
    }

    private companion object { const val ONE_HOUR = 60 * 60 * 1000L }
}

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val activityRepository: ActivityRepository,
    categoryRepository: CategoryRepository,
    activityTypeRepository: ActivityTypeRepository,
    tagRepository: TagRepository
) : ViewModel() {
    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private val deleted = MutableStateFlow(false)
    private val zoneId = ZoneId.systemDefault()
    private val tagIds: Flow<List<String>> = activityRepository.getTagIdsForSession(sessionId)

    private val detailBase = combine(
        activityRepository.getById(sessionId),
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        tagRepository.getAll(),
        tagIds
    ) { session: ActivitySession?, categories: List<Category>, types: List<ActivityType>, tags: List<Tag>, selectedTagIds: List<String> ->
        ActivityDetailUiState(
            session = session,
            category = categories.firstOrNull { it.id == session?.categoryId },
            activityType = types.firstOrNull { it.id == session?.activityTypeId },
            tags = tags.filter { it.id in selectedTagIds },
            range = session?.let { "${TimeFormatting.formatTime(it.startAt, zoneId)}–${it.endAt?.let { end -> TimeFormatting.formatTime(end, zoneId) } ?: "läuft"}" }.orEmpty(),
            duration = session?.let { TimeFormatting.formatDuration((it.endAt ?: System.currentTimeMillis()) - it.startAt) }.orEmpty()
        )
    }

    val uiState: StateFlow<ActivityDetailUiState> = detailBase
        .combine(deleted) { state, isDeleted -> state.copy(deleted = isDeleted) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityDetailUiState())

    fun delete() {
        viewModelScope.launch {
            activityRepository.softDelete(sessionId, System.currentTimeMillis())
            deleted.value = true
        }
    }
}

data class TimelineBase(
    val date: LocalDate,
    val sessions: List<ActivitySession>,
    val candidates: List<ActivityCandidate>,
    val triggers: List<TriggerEvent>
)

data class EditorBase(
    val form: ActivityEditorForm,
    val categories: List<Category>,
    val types: List<ActivityType>
)

data class TimelineUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val dayTitle: String = "Heute",
    val formattedDate: String = TimeFormatting.formatDate(LocalDate.now()),
    val sessions: List<TimelineSessionUi> = emptyList(),
    val totalTracked: String = "0m",
    val sessionCount: Int = 0,
    val categories: List<Category> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val categoryDurations: Map<String, Long> = emptyMap(),
    val triggerEvents: List<TriggerEventUi> = emptyList(),
    val candidates: List<CandidateReviewUi> = emptyList(),
    val hasOverlaps: Boolean = false,
    // M12.2: Stufenloser Pinch-to-Zoom.
    // pixelsPerHour ist die einzige Quelle der Wahrheit für die Timeline-Höhe.
    // Statt eines enum-basierten 3-Stufen-Modells wird ein Float gespeichert,
    // der via detectTransformGestures zwischen MIN_PPH und MAX_PPH skaliert wird.
    val pixelsPerHour: Float = DEFAULT_PIXELS_PER_HOUR
) {
    companion object {
        const val DEFAULT_PIXELS_PER_HOUR: Float = 40f
        const val MIN_PIXELS_PER_HOUR: Float = 18f   // 24h × 18 = 432dp — kompakter Tagesüberblick
        const val MAX_PIXELS_PER_HOUR: Float = 120f  // 24h × 120 = 2880dp — feine Minutenansicht
    }
}

/**
 * M12.2: SourceTypes, die als "automatisch erfasst" gelten.
 * Vereinheitlicht GEOFENCE_AUTO (M11), HEALTH_SLEEP_AUTO (M12.2) und
 * ACTIVITY_RECOGNITION_AUTO (M12.2, für Fahrten). Diese Konstante
 * ist die Single Source of Truth für "ist diese Session vom System
 * erkannt worden?" und wird in Timeline, Dashboard, LiveActivity-Card
 * und Foreground-Notification gleichermaßen genutzt.
 */
val AUTO_SOURCES: Set<String> = setOf(
    "GEOFENCE_AUTO",
    "HEALTH_SLEEP_AUTO",
    "ACTIVITY_RECOGNITION_AUTO"
)

data class TriggerEventUi(
    val id: String,
    val label: String,
    val time: String,
    val minuteOfDay: Int,
    val confidence: Int,
    val source: String
)

/**
 * M11.2: Extrahiert den Geofence-Namen aus dem metadataJson eines TriggerEvents.
 * Das metadataJson hat die Form: {"geofenceName":"Arbeitsplatz","activityTypeId":"..."}
 */
internal fun extractGeofenceName(metadataJson: String?): String? {
    if (metadataJson.isNullOrBlank()) return null
    return try {
        val regex = """"geofenceName"\s*:\s*"([^"]+)"""".toRegex()
        regex.find(metadataJson)?.groupValues?.getOrNull(1)
    } catch (_: Exception) {
        null
    }
}

data class CandidateReviewUi(
    val id: String,
    val title: String,
    val timeRange: String,
    val duration: String,
    val reason: String,
    val confidence: Int
)

data class TimelineSessionUi(
    val id: String,
    val title: String,
    val categoryId: String?,
    val categoryName: String,
    val activityTypeName: String,
    val time: String,
    val range: String,
    val duration: String,
    val source: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val isRunning: Boolean,
    val isOverlapping: Boolean,
    // M12.2: isAuto = true für GEOFENCE_AUTO, HEALTH_SLEEP_AUTO, ACTIVITY_RECOGNITION_AUTO.
    // Die Timeline-UI zeigt diese Einträge mit einer dezenten "Auto"-Markierung
    // und nutzt sie im Lane-Layout konsistent mit allen anderen Auto-Quellen.
    val isAuto: Boolean = false
)

data class ActivityEditorForm(
    val title: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val activityTypeId: String? = null,
    val selectedTagIds: List<String> = emptyList(),
    val startAt: Long = System.currentTimeMillis(),
    val endAt: Long? = System.currentTimeMillis() + 60 * 60 * 1000,
    val date: LocalDate = LocalDate.now(),
    val errorMessage: String? = null
)

data class ActivityEditorUiState(
    val isEditing: Boolean = false,
    val form: ActivityEditorForm = ActivityEditorForm(),
    val categories: List<Category> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val duration: String = "1h",
    val validation: SessionValidationResult = SessionValidationResult.Valid,
    val triggerMarkers: List<TriggerEventMarker> = emptyList(),
    val savedSessionId: String? = null
)

data class ActivityDetailUiState(
    val session: ActivitySession? = null,
    val category: Category? = null,
    val activityType: ActivityType? = null,
    val tags: List<Tag> = emptyList(),
    val range: String = "",
    val duration: String = "",
    val deleted: Boolean = false
)
