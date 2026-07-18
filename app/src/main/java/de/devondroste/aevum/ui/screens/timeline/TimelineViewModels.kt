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
    private val selectedDate = MutableStateFlow(LocalDate.now())

    init { viewModelScope.launch { ensureDefaultData() } }

    private val timelineBase = combine(
        selectedDate,
        activityRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        triggerEventRepository.getAll()
    ) { date: LocalDate, sessions: List<ActivitySession>, candidates: List<ActivityCandidate>, triggers: List<TriggerEvent> ->
        TimelineBase(date, sessions, candidates, triggers)
    }

    val uiState: StateFlow<TimelineUiState> = combine(
        timelineBase,
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        tagRepository.getAll()
    ) { base: TimelineBase, categories: List<Category>, types: List<ActivityType>, tags: List<Tag> ->
        buildTimelineState(base.date, base.sessions, base.candidates, base.triggers, categories, types, tags)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    fun previousDay() = selectedDate.update { it.minusDays(1) }
    fun nextDay() = selectedDate.update { it.plusDays(1) }
    fun today() = selectedDate.update { LocalDate.now() }
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
        val sessions = allSessions
            .filter { it.deletedAt == null && SessionTimeValidator.rangesOverlap(dayStart, dayEnd, it.startAt, it.endAt) }
            .sortedBy { it.startAt }
        val rows = sessions.map { session ->
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
                startMinuteOfDay = TimeFormatting.minutesOfDay(session.startAt, zoneId),
                endMinuteOfDay = session.endAt?.let { TimeFormatting.minutesOfDay(it, zoneId) } ?: (24 * 60),
                isRunning = session.endAt == null,
                isOverlapping = sessions.any { other -> other.id != session.id && SessionTimeValidator.rangesOverlap(session.startAt, session.endAt, other.startAt, other.endAt) }
            )
        }
        val totalMs = sessions.sumOf { (it.endAt ?: System.currentTimeMillis()) - it.startAt }
        val categoryDurations = sessions.groupBy { it.categoryId ?: "unknown" }
            .mapValues { entry -> entry.value.sumOf { (it.endAt ?: System.currentTimeMillis()) - it.startAt } }
        val triggers = allTriggers
            .filter { it.occurredAt >= dayStart && it.occurredAt < dayEnd }
            .sortedBy { it.occurredAt }
            .map { trigger ->
                TriggerEventUi(
                    id = trigger.id,
                    label = trigger.type.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() },
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
            sessionCount = sessions.size,
            categories = categories,
            activityTypes = types,
            tags = tags,
            categoryDurations = categoryDurations,
            triggerEvents = triggers,
            candidates = candidates,
            hasOverlaps = rows.any { it.isOverlapping }
        )
    }
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
                TriggerEventMarker(
                    id = it.id,
                    label = it.type.replace('_', ' '),
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
    val hasOverlaps: Boolean = false
)

data class TriggerEventUi(
    val id: String,
    val label: String,
    val time: String,
    val minuteOfDay: Int,
    val confidence: Int,
    val source: String
)

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
    val isOverlapping: Boolean
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
