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
import de.devondroste.aevum.automation.gap.GapDetectionEngine
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
    private val gapDetectionEngine: GapDetectionEngine,
    private val saveManualActivityUseCase: SaveManualActivityUseCase,
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

    /**
     * M15: Konvertiert einen Gap-Candidate in eine echte Session mit der
     * gewählten Category/ActivityType. Nutzt [SaveManualActivityUseCase],
     * das gleichzeitig den Candidate-Status auf EDITED setzt — sauberer
     * Single-Write ohne zweite Mutation.
     *
     * M16.4: activityTypeId ist jetzt Pflicht (statt optional), weil der
     * User im neuen ActivityPicker eine konkrete Auswahl trifft.
     */
    fun convertGapToSession(candidateId: String, categoryId: String, activityTypeId: String) {
        viewModelScope.launch {
            try {
                val candidate = candidateRepository.getById(candidateId).first() ?: return@launch
                val request = ManualActivityRequest(
                    id = null, // Neue Session anlegen
                    sourceCandidateId = candidate.id,
                    title = titleForCategory(categoryId, activityTypeId),
                    categoryId = categoryId,
                    activityTypeId = activityTypeId,
                    tagIds = emptyList(),
                    tags = emptyList(),
                    startAt = candidate.startAt,
                    endAt = candidate.endAt,
                    timezoneId = zoneId.id,
                    description = "Aus Gap-Detection übernommen"
                )
                saveManualActivityUseCase(request)
            } catch (_: Exception) { /* defensive */ }
        }
    }

    private fun titleForCategory(categoryId: String, activityTypeId: String?): String {
        // M16.4: Wenn der User eine konkrete Activity gewählt hat, nimm deren
        // Namen statt eines generischen Kategorie-Titels. Das macht die
        // Session für den User sofort identifizierbar.
        if (activityTypeId != null && activityTypeId in activityTypeFriendlyNames) {
            return activityTypeFriendlyNames[activityTypeId]!!
        }
        // Fallback für die alten Schnellauswahl-Buttons (categoryId only)
        return when (categoryId) {
            "social" -> "Freunde treffen"
            "learning" -> "Lernen"
            "household" -> "Einkaufen"
            "work" -> "Arbeit"
            "leisure" -> "Freizeit"
            "sport" -> "Bewegung"
            "health" -> "Gesundheit"
            "transport" -> "Unterwegs"
            else -> "Aktivität"
        }
    }

    private val activityTypeFriendlyNames: Map<String, String> = mapOf(
        "work" to "Arbeit",
        "deep_work" to "Deep Work",
        "sleep" to "Schlaf",
        "fitness" to "Fitness",
        "learning" to "Lernen",
        "reading" to "Lesen",
        "meditation" to "Meditation",
        "eating" to "Essen",
        "social" to "Soziales",
        "household" to "Haushalt",
        "driving" to "Autofahren",
        "transport" to "Transport",
        "digital" to "Digital",
        "leisure" to "Freizeit",
        "other" to "Sonstiges"
    )

    /**
     * M15: Manueller Trigger für die Gap-Detection. Wird vom "Lücken
     * prüfen"-Button in der Timeline aufgerufen und läuft einmalig.
     */
    fun runGapDetectionNow() {
        viewModelScope.launch {
            try {
                gapDetectionEngine.detectGapsForDay(selectedDate.value, zoneId)
            } catch (_: Exception) { /* defensive: keine UI-Crash */ }
        }
    }

    /**
     * Loescht einen Trigger-Eintrag anhand seiner ID.
     * Wird vom Trash-Button in der EventListTimeline aufgerufen.
     * Da triggerEventRepository.delete eine suspend-Funktion ist,
     * muss der Aufruf in viewModelScope.launch gewrappt werden.
     */
    fun deleteTrigger(id: String) {
        viewModelScope.launch {
            try {
                triggerEventRepository.delete(id)
            } catch (_: Exception) { /* defensive: keine UI-Crash */ }
        }
    }

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
        //
        // M16.5: Mitternacht-Clipping. Eine Session kann über zwei Tage gehen
        // (z.B. Schlaf 23:30–08:30). Im aktuellen Tag zeigen wir nur den
        // sichtbaren Ausschnitt [dayStart, dayEnd]. Session selbst bleibt
        // unverändert in der DB — das Clipping ist rein für die Anzeige.
        val filteredSessions = allSessions
            .filter { it.deletedAt == null && SessionTimeValidator.rangesOverlap(dayStart, dayEnd, it.startAt, it.endAt) }
            .sortedBy { it.startAt }

        // M16.5: Pro Session den sichtbaren Tagesausschnitt berechnen.
        // Für reine Tagessessions ist das identisch zu startAt/endAt.
        // Für Mitternacht-Sessions wird auf den Tag geclippt:
        //   Schlaf 23:30 (gestern) → 08:30 (heute), Tag = heute
        //     → dayClippedStartMs = max(23:30_gestern, 00:00_heute) = 00:00_heute
        //     → dayClippedEndMs   = min(08:30_heute, 24:00_heute) = 08:30_heute
        //   → sichtbare Dauer im Tag = 8h30min (statt 9h gesamt).
        val clippedSessions = filteredSessions.map { session ->
            val clippedStart = maxOf(session.startAt, dayStart)
            val clippedEnd = minOf(session.endAt ?: System.currentTimeMillis(), dayEnd)
            TimelineClip(
                session = session,
                clippedStartMs = clippedStart,
                clippedEndMs = clippedEnd,
                // Auch im Fall "läuft noch" (endAt=null) clippen wir auf now/dayEnd,
                // damit die Berechnung konsistent bleibt.
            )
        }

        // M12.2: Auto-Dedup gegen Trigger-Doppel.
        // Trigger mit geofenceId + type + occurredAt, die in einem 90s-Fenster
        // dupliziert vorkommen, werden zu einem zusammengefasst.
        val dayTriggers = allTriggers
            .filter { it.occurredAt >= dayStart && it.occurredAt < dayEnd }
            .sortedBy { it.occurredAt }
            .distinctBy { tripleKey(it.geofenceId, it.type, it.occurredAt / 60_000L) }
        val rows = clippedSessions.map { clip ->
            val session = clip.session
            // M16.5: Minuten werden aus den clipped Werten berechnet, damit
            // eine Mitternacht-Session im Starttag als 23:30–24:00 und im
            // Folgetag als 00:00–08:30 erscheint (nicht 23:30–08:30).
            val clippedStartMin = TimeFormatting.minutesOfDay(clip.clippedStartMs, zoneId)
            val clippedEndMin = TimeFormatting.minutesOfDay(clip.clippedEndMs, zoneId)
            val visibleDurationMs = (clip.clippedEndMs - clip.clippedStartMs).coerceAtLeast(0L)
            // M18.23-FIX: Kategorie-Fallback. Wenn die Session keine
            // categoryId hat (z.B. wegen Race Condition beim Live-Start:
            // ActivityType wurde gerade erstellt, defaultCategoryId war
            // noch nicht im Cache), dann die defaultCategoryId des
            // ActivityTypes als Fallback verwenden.
            val effectiveCategoryId = session.categoryId
                ?: typeMap[session.activityTypeId]?.defaultCategoryId
            TimelineSessionUi(
                id = session.id,
                title = session.title,
                categoryId = effectiveCategoryId,
                categoryName = categoryMap[effectiveCategoryId]?.name ?: "Sonstiges",
                activityTypeName = typeMap[session.activityTypeId]?.name ?: "Freie Aktivität",
                time = TimeFormatting.formatTime(clip.clippedStartMs, zoneId),
                // M16.5: Range spiegelt den sichtbaren Tagesausschnitt wider.
                range = "${TimeFormatting.formatTime(clip.clippedStartMs, zoneId)}–${TimeFormatting.formatTime(clip.clippedEndMs, zoneId)}",
                // Dauer im Tag, nicht Gesamt-Dauer (relevant für Mitternacht-Schlaf).
                duration = TimeFormatting.formatDuration(visibleDurationMs),
                source = session.sourceType,
                // M12.2: Schlaf-Sessions sind per Konfiguration auto-erfasst.
                isAuto = session.sourceType in AUTO_SOURCES,
                // M16.5: Minuten-Of-Day aus clipped Werten, damit Mitternacht
                // korrekt als 00:00–08:30 im Folgetag gerendert wird.
                startMinuteOfDay = clippedStartMin,
                endMinuteOfDay = clippedEndMin,
                isRunning = session.endAt == null,
                isOverlapping = filteredSessions.any { other -> other.id != session.id && SessionTimeValidator.rangesOverlap(session.startAt, session.endAt, other.startAt, other.endAt) },
                // M18.5: Positivitäts-Score für die Farbcodierung der
                // Timeline-Zeilen (grün = gut, rot = schlecht).
                positivityScore = typeMap[session.activityTypeId]?.positivityScore ?: 50,
                // M18.13: Icon + custom Farbe der Aktivität für die Timeline.
                activityIcon = typeMap[session.activityTypeId]?.icon ?: "•",
                activityColor = typeMap[session.activityTypeId]?.color ?: 0L
            )
        }
        // M16.5: totalMs und categoryDurations basieren auf dem sichtbaren
        // Tagesausschnitt. So summiert sich die Tagesstatistik konsistent
        // zur angezeigten Timeline.
        val totalMs = clippedSessions.sumOf { (it.clippedEndMs - it.clippedStartMs).coerceAtLeast(0L) }
        val categoryDurations = clippedSessions
            .groupBy { it.session.categoryId ?: "unknown" }
            .mapValues { entry ->
                entry.value.sumOf { (it.clippedEndMs - it.clippedStartMs).coerceAtLeast(0L) }
            }
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
                    confidence = (candidate.confidence * 100).toInt(),
                    // M15: Lücken-Candidates bekommen isGap=true, damit die
                    // UI sie speziell rendert (gestrichelt, Schnellauswahl).
                    isGap = candidate.createdBy == "GAP_DETECTION_V1",
                    startAt = candidate.startAt,
                    endAt = candidate.endAt
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

/**
 * M16.5: Hilfs-Datenklasse für die Timeline-Anzeige. Hält pro Session den
 * sichtbaren Tagesausschnitt, damit Mitternacht-Sessions in beiden Tagen
 * korrekt dargestellt werden (Starttag: 23:30–24:00, Folgetag: 00:00–08:30).
 *
 * Die Session selbst wird NICHT verändert — das Clipping ist rein für die
 * Anzeige.
 */
private data class TimelineClip(
    val session: ActivitySession,
    val clippedStartMs: Long,
    val clippedEndMs: Long
)

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
        val oldDuration = ((current.endAt ?: current.startAt + ONE_HOUR) - current.startAt).coerceAtLeast(15 * 60 * 1000L)
        // M16.2: Wenn das Ende vor dem neuen Start liegt (Mitternacht-Überschreitung),
        // berechne das Ende als newStart + oldDuration. Das Ende liegt dann
        // automatisch am nächsten Tag, falls die Dauer über Mitternacht geht.
        val newEnd = newStart + oldDuration
        current.copy(startAt = newStart, endAt = newEnd)
    }
    fun setEndMinuteOfDay(value: Int) = form.update { current ->
        val newEnd = TimeFormatting.millisAtMinuteOfDay(current.date, value, zoneId)
        // M16.2: Mitternacht-Überquerung. Wenn das Ende vor dem Start liegt
        // (z.B. Start 23:30, Ende 10:00), interpretieren wir das Ende als
        // am nächsten Tag. Das ist für Schlaf und alle übernachtenden
        // Aktivitäten korrekt. Dauer = Ende - Start + 24h.
        val fixedEnd = if (newEnd <= current.startAt) {
            newEnd + 24L * 60 * 60 * 1000  // nächster Tag
        } else {
            newEnd
        }
        current.copy(endAt = fixedEnd)
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
        val oldDuration = ((current.endAt ?: current.startAt + ONE_HOUR) - current.startAt).coerceAtLeast(15 * 60 * 1000L)
        // M16.2: Ende als newStart + oldDuration berechnen, ohne auf den
        // aktuellen Tag zu begrenzen. Bei übernachtenden Aktivitäten
        // (z.B. Schlaf 23:30→10:00) darf das Ende am nächsten Tag liegen.
        current.copy(startAt = newStart, endAt = newStart + oldDuration)
    }

    private fun updateEnd(hour: Int? = null, minute: Int? = null) = form.update { current ->
        val local = Instant.ofEpochMilli(current.endAt ?: current.startAt).atZone(zoneId).toLocalTime()
        val newEnd = TimeFormatting.parseHourMinuteToMillis(current.date, hour ?: local.hour, minute ?: local.minute, zoneId)
        // M16.2: Mitternacht-Überquerung. Wenn das Ende vor dem Start liegt,
        // interpretieren wir das Ende als am nächsten Tag. Das gilt für Schlaf
        // und alle übernachtenden Aktivitäten (Start 23:30, Ende 10:00).
        val fixedEnd = if (newEnd <= current.startAt) {
            newEnd + 24L * 60 * 60 * 1000  // nächster Tag
        } else {
            newEnd
        }
        current.copy(endAt = fixedEnd)
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
            category = categories.firstOrNull { it.id == (session?.categoryId ?: types.firstOrNull { t -> t.id == session?.activityTypeId }?.defaultCategoryId) },
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
    val confidence: Int,
    // M15: isGap = true markiert Lücken-Candidates aus der Gap-Detection.
    // Die Timeline rendert sie mit gestricheltem grauem Rand und einer
    // Schnellauswahl (Freunde / Lernen / Einkaufen / Arbeit) statt der
    // normalen Übernehmen/Bearbeiten/Verwerfen-Buttons.
    val isGap: Boolean = false,
    val startAt: Long = 0L,
    val endAt: Long = 0L
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
    val isAuto: Boolean = false,
    // M18.5: Positivitäts-Score (0-100) — für Farbcodierung in der Timeline.
    val positivityScore: Int = 50,
    // M18.13: Icon + custom Farbe der Aktivität.
    val activityIcon: String = "•",
    val activityColor: Long = 0L
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
