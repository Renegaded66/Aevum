package com.d_drostes_apps.aevum.ui.screens.timeline
import com.d_drostes_apps.aevum.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Tag
import com.d_drostes_apps.aevum.domain.activity.SessionValidationResult
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.domain.trigger.TriggerEventMarker
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.AevumTimePicker
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.CategoryChip
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.components.QualityOverrideDialog
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.ui.components.categoryColor
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    onCreateActivity: (Long) -> Unit,
    onEditActivity: (String) -> Unit,
    onEditCandidate: (String) -> Unit,
    onOpenActivity: (String) -> Unit,
    // M18.61: Kalender-Icon in der Timeline → öffnet die Kalenderansicht
    onOpenCalendar: () -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    // M18.66-FIX14: Wochenansicht-Modus aus dem ViewModel
    val weekView by viewModel.weekView.collectAsState()
    // AEVUM-3: Lang-Druck auf eine Session → Güte-Slider für diese Aufzeichnung.
    // Der Override wird NUR auf diese Session geschrieben — die Einstellung
    // des ActivityTypes bleibt unverändert, am nächsten Tag gilt wieder die
    // automatische Berechnung.
    var qualityTarget by remember { mutableStateOf<TimelineSessionUi?>(null) }
    // M18.73: New-Recording-Dialog-Zustände aus dem ViewModel
    val showNewRecording by viewModel.newRecordingOpen.collectAsState()
    val newForm by viewModel.newRecordingForm.collectAsState()
    val newError by viewModel.newRecordingError.collectAsState()
    val newSaving by viewModel.newRecordingSaving.collectAsState()
    val savedNewId by viewModel.newRecordingSavedId.collectAsState()
    // M18.73: Nach erfolgreichem Speichern im New-Recording-Dialog zum Detail-Screen.
    LaunchedEffect(savedNewId) {
        savedNewId?.let { id ->
            viewModel.consumeNewRecordingSavedId()
            onOpenActivity(id)
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // M18.73: Plus-Button öffnet den New-Recording-Dialog mit
                // drei Modi (Start & Ende / Offenes Ende / Nur Dauer).
                onClick = viewModel::openNewRecording,
                text = { Text(stringResource(R.string.timeline_fab_activity)) },
                icon = { Text("+") }
            )
        }
    ) { padding ->
        // M18.26: KEINE aeußere LazyColumn mehr fuer die Tag-Ansicht!
        // Vorher: LazyColumn mit item { DayCalendarTimeline(...) } — die
        // Tag-Ansicht (ZoomableDayTimeline) hatte einen eigenen
        // verticalScroll mit fester Hoehe (560dp). Ergebnis: Nested-Scroll-
        // Konflikt — beim Wischen scrollte mal die Timeline, mal das
        // Fragment (genau der gemeldete Usability-Bug).
        //
        // Jetzt: Column mit fixem Header/Summary, und die Timeline bekommt
        // einen EIGENEN Viewport (weight 1f). Es gibt NUR NOCH einen
        // Scroll-Container — die Timeline selbst. Das Fragment scrollt
        // nie mehr mit.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
        ) {
            TimelineHeader(
                state,
                viewModel::previousDay,
                viewModel::nextDay,
                viewModel::today,
                viewModel::runGapDetectionNow,
                onOpenCalendar = onOpenCalendar
            )
            SummaryCard(state)
            if (state.candidates.isNotEmpty()) {
                CandidateReviewCard(
                    candidates = state.candidates,
                    activityTypes = state.activityTypes,
                    onAccept = viewModel::acceptCandidate,
                    onEdit = onEditCandidate,
                    onDismiss = viewModel::dismissCandidate,
                    onConvertGap = viewModel::convertGapToSession
                )
            }
            // M18.58: Slide-Animation beim Tag-Wechsel (nur über die
            // Pfeil-Buttons — KEIN Gesten-Swipe).
            // M18.59-FIX (User: "zurück = nach rechts swipen, vor = nach
            // links swipen"): Die Paare waren vertauscht — nextDay nutzte
            // slideLeft+exitRight (beide von rechts), previousDay das
            // Gegenteil. Jetzt klassische Blätter-Metapher:
            //   vor (nextDay)     → neuer Tag von RECHTS rein, alter nach LINKS raus
            //   zurück (prevDay)  → neuer Tag von LINKS rein, alter nach RECHTS raus
            androidx.compose.animation.AnimatedContent(
                targetState = state.selectedDate,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val slideLeft = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val slideRight = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { -it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val exitLeft = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { -it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    val exitRight = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    if (targetState > initialState) {
                        // Vorwärts (nextDay): nach links swipen
                        (slideLeft togetherWith exitLeft).using(androidx.compose.animation.SizeTransform(clip = false))
                    } else {
                        // Rückwärts (previousDay): nach rechts swipen
                        (slideRight togetherWith exitRight).using(androidx.compose.animation.SizeTransform(clip = false))
                    }
                },
                label = "day-slide"
            ) { date ->
                if (state.sessions.isEmpty() && state.triggerEvents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(
                            title = stringResource(R.string.timeline_empty_title),
                            message = stringResource(R.string.timeline_empty_message),
                            actionLabel = stringResource(R.string.timeline_empty_action),
                            onActionClick = { onCreateActivity(TimeFormatting.startOfDayMillis(date)) }
                        )
                    }
                } else {
                    DayCalendarTimeline(
                        sessions = state.sessions,
                        triggers = state.triggerEvents,
                        onOpen = onOpenActivity,
                        onEdit = onEditActivity,
                        onDeleteTrigger = viewModel::deleteTrigger,
                        onDeleteSession = viewModel::deleteSession,
                        // AEVUM-3: Lang-Druck auf eine Session → Güte anpassen.
                        onAdjustQuality = { qualityTarget = it },
                        // M18.66-FIX18 (User: "wenn man auf die Timeline klickt,
                        // öffnet sich ein Popup — das sollte nicht sein.
                        // Stattdessen dieselbe Seite wie beim Plus-Button, mit
                        // Startzeit = geklickte Zeit, Endzeit = +1h"): Kein
                        // QuickCreateDialog mehr — direkte Navigation zum
                        // ActivityEditor mit der geklickten Minute als
                        // Startzeit (Endzeit = Start + 1h im Editor-Default).
                        onCreateAt = { minute ->
                            val date = state.selectedDate
                            val startMillis = TimeFormatting.parseHourMinuteToMillis(
                                date, minute / 60, minute % 60
                            )
                            onCreateActivity(startMillis)
                        },
                        weekView = weekView,
                        weekSessions = state.weekSessions,
                        onSetWeekView = viewModel::setWeekView,
                        onSelectDay = viewModel::selectDate,
                        // M18.83: Zoom aus dem ViewModel (SharedPreferences-persistiert).
                        // Slider + Pinch-to-Zoom schreiben zurück → der Zoom
                        // überlebt Ansichtwechsel (Liste↔Tag↔Woche) und App-Restarts.
                        pixelsPerHour = state.pixelsPerHour,
                        onPixelsPerHourChange = viewModel::setPixelsPerHour,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AevumSpacing.md)
                    )
                }
            }
            // M18.36: KEIN Spacer mehr hier! Der 88dp-Spacer erzeugte
            // toten Platz UNTER der Timeline. Der FAB-Schutz liegt jetzt
            // im internen Scroll-Container der Listenansicht (Bottom-
            // Padding dort), die Tag-Ansicht scrollt intern und braucht
            // kein Padding.
        }
    }
    // AEVUM-3: Güte-Dialog beim Lang-Druck auf eine Session.
    qualityTarget?.let { target ->
        QualityOverrideDialog(
            title = stringResource(R.string.common_adjust_quality),
            message = stringResource(
                R.string.timeline_quality_message_range,
                target.title,
                target.time,
                target.range.substringAfter("–")
            ),
            initialScore = target.positivityScore,
            hasOverride = target.hasQualityOverride,
            onDismiss = { qualityTarget = null },
            onSave = { score ->
                viewModel.setSessionQualityOverride(target.id, score)
                qualityTarget = null
            }
        )
    }
    // M18.66-FIX18: QuickCreateDialog entfernt — Tap auf leere Zeitstelle
    // navigiert jetzt direkt zum ActivityEditor (Startzeit = Klick-Zeit,
    // Endzeit = +1h). Das Popup war der User-Beschwerdepunkt.

    // M18.73: New-Recording-Dialog mit drei Modi (Plus-Button auf der
    // Timeline): Start & End Time (Standard), Start Time/Open End und
    // Flat-rate Time (nur Tagesstatistik, keine Timeline-Zeile).
    if (showNewRecording) {
        NewRecordingDialog(
            form = newForm,
            activityGroups = state.activityGroups,
            errorMessage = newError,
            saving = newSaving,
            onModeChange = viewModel::setNewRecordingMode,
            onDateChange = viewModel::setNewRecordingDate,
            onStartHourChange = viewModel::setNewRecordingStartHour,
            onStartMinuteChange = viewModel::setNewRecordingStartMinute,
            onEndHourChange = viewModel::setNewRecordingEndHour,
            onEndMinuteChange = viewModel::setNewRecordingEndMinute,
            onDurationChange = viewModel::setNewRecordingDurationMinutes,
            onActivityTypeChange = viewModel::setNewRecordingActivityType,
            onSave = viewModel::saveNewRecording,
            onDismiss = viewModel::closeNewRecording
        )
    }
}

/**
 * M18.73 + M18.74: New-Recording-Dialog vom Timeline-Plus-Button.
 * Drei Modi (Segment-UI, "Start & End Time" ist beim Öffnen vorausgewählt):
 *  - FIXED:     Datum + Start- & Endzeit → fester Eintrag auf der Timeline
 *  - OPEN_END:  Datum + Startzeit → laufender Eintrag (endAt = null)
 *  - FLAT_RATE: Datum + Dauer → nur Tagesstatistik, keine Timeline-Zeile
 *
 * M18.74: Die Aktivitäts-Auswahl ist Pflicht — der Speichern-Button bleibt
 * deaktiviert, bis genau eine Aktivität gewählt ist. Es gibt kein Freitext-
 * Titel-/Beschreibungsfeld mehr; persistiert wird ausschließlich die gewählte
 * Aktivität (Titel = Aktivitätsname).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewRecordingDialog(
    form: NewRecordingForm,
    activityGroups: List<CategoryGroup>,
    errorMessage: String?,
    saving: Boolean,
    onModeChange: (NewRecordingMode) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onStartHourChange: (Int) -> Unit,
    onStartMinuteChange: (Int) -> Unit,
    onEndHourChange: (Int) -> Unit,
    onEndMinuteChange: (Int) -> Unit,
    onDurationChange: (Int) -> Unit,
    onActivityTypeChange: (ActivityType) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    // M18.74: Expandierte Kategorie-Gruppen (alle offen, bis der User
    // kollabiert — so sieht man die Auswahl beim Öffnen sofort).
    var expandedCategories by remember { mutableStateOf<Set<String?>>(emptySet()) }
    val duration = form.durationMinutes
    val durHours = duration / 60
    val durMinutes = duration % 60
    // M18.74: Pflicht-Auswahl — Save erst mit genau einer Aktivität.
    val canSave = form.activityTypeId != null && !saving
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AevumSpacing.lg)
                .padding(bottom = AevumSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Text(stringResource(R.string.timeline_new_recording_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.timeline_new_recording_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Modus-Auswahl (Start & End Time ist standardmäßig vorausgewählt)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SegmentButton(stringResource(R.string.timeline_mode_fixed), form.mode == NewRecordingMode.FIXED, Modifier.weight(1f)) { onModeChange(NewRecordingMode.FIXED) }
                SegmentButton(stringResource(R.string.timeline_mode_open_end), form.mode == NewRecordingMode.OPEN_END, Modifier.weight(1f)) { onModeChange(NewRecordingMode.OPEN_END) }
                SegmentButton(stringResource(R.string.timeline_mode_duration_only), form.mode == NewRecordingMode.FLAT_RATE, Modifier.weight(1f)) { onModeChange(NewRecordingMode.FLAT_RATE) }
            }

            // Datum (alle Modi)
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(TimeFormatting.formatDate(form.date))
            }

            when (form.mode) {
                NewRecordingMode.FIXED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            AevumTimePicker(
                                initialHour = form.startHour,
                                initialMinute = form.startMinute,
                                accent = Color(0xFFF5A623),
                                onTimeChange = { h, m -> onStartHourChange(h); onStartMinuteChange(m) },
                                label = stringResource(R.string.timeline_time_start_label),
                                showDigitalDisplay = true
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            AevumTimePicker(
                                initialHour = form.endHour,
                                initialMinute = form.endMinute,
                                accent = MaterialTheme.colorScheme.primary,
                                onTimeChange = { h, m -> onEndHourChange(h); onEndMinuteChange(m) },
                                label = stringResource(R.string.timeline_time_end_label),
                                showDigitalDisplay = true
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.timeline_fixed_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NewRecordingMode.OPEN_END -> {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AevumTimePicker(
                            initialHour = form.startHour,
                            initialMinute = form.startMinute,
                            accent = Color(0xFFF5A623),
                            onTimeChange = { h, m -> onStartHourChange(h); onStartMinuteChange(m) },
                            label = stringResource(R.string.timeline_time_start_label),
                            showDigitalDisplay = true
                        )
                    }
                    Text(
                        stringResource(R.string.timeline_open_end_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NewRecordingMode.FLAT_RATE -> {
                    Text(
                        stringResource(R.string.timeline_duration_only_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // R20-v3: Fancy Duration-Ring statt hässlicher ±-Buttons
                    FancyDurationRing(
                        minutes = duration,
                        onMinutesChange = onDurationChange
                    )
                }
            }

            // R20-v3: Fancy Activity-Picker mit Live-Suchleiste (ersetzt
            // kollabierbare Kategorie-Gruppen — flach, durchsuchbar, modern).
            FancyNewRecordingActivityPicker(
                groups = activityGroups,
                selectedActivityId = form.activityTypeId,
                onSelectActivity = onActivityTypeChange
            )

            errorMessage?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        saving -> stringResource(R.string.timeline_save_saving)
                        form.activityTypeId == null -> stringResource(R.string.timeline_save_select_activity)
                        else -> stringResource(R.string.timeline_save_recording)
                    }
                )
            }
        }
    }

    if (showDatePicker) {
        val initialMillis = form.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            onDateChange(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())
                        }
                        showDatePicker = false
                    }
                ) { Text(stringResource(R.string.common_apply)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) } }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * R20-v3: Fancy Activity-Picker mit Live-Suchleiste für den
 * NewRecordingDialog. Flach, durchsuchbar, ohne Kategorisierung.
 * - Große Suchleiste filtert live (case-insensitive)
 * - Ergebnisliste mit Icon + Farbe + Name
 * - Selected-State mit ✓-Badge
 * - Empty-State bei keinem Treffer
 */
@Composable
private fun FancyNewRecordingActivityPicker(
    groups: List<CategoryGroup>,
    selectedActivityId: String?,
    onSelectActivity: (ActivityType) -> Unit
) {
    var query by remember { mutableStateOf("") }
    // Alle Aktivitäten flach aus allen Gruppen extrahieren
    val allTypes = remember(groups) {
        groups.flatMap { it.activities }.distinctBy { it.id }
    }
    val filtered = remember(query, allTypes) {
        if (query.isBlank()) allTypes
        else allTypes.filter { it.name.contains(query, ignoreCase = true) }
    }
    val selected = allTypes.firstOrNull { it.id == selectedActivityId }

    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        // Ausgewählte Aktivität anzeigen oder "noch nichts gewählt"
        if (selected != null) {
            val selColor = runCatching { Color(selected.color.toInt()) }
                .getOrDefault(MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AevumRadius.lg))
                    .background(selColor.copy(alpha = 0.12f))
                    .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(selColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(selected.icon?.takeIf { it.isNotBlank() } ?: "•", fontSize = 16.sp)
                }
                Text(
                    selected.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text("✓", fontSize = 16.sp, color = selColor, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(
                stringResource(R.string.timeline_no_activity_selected),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Live-Suchleiste
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.timeline_search_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(AevumRadius.lg),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
            ),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        )

        // Ergebnisliste
        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = AevumSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔍", fontSize = 36.sp)
                Spacer(Modifier.height(AevumSpacing.xs))
                Text(
                    stringResource(R.string.timeline_no_results, query),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                if (filtered.size == 1) {
                    stringResource(R.string.timeline_activity_count_singular, filtered.size)
                } else {
                    stringResource(R.string.timeline_activity_count_plural, filtered.size)
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.id }) { type ->
                    val isSelected = type.id == selectedActivityId
                    val typeColor = runCatching { Color(type.color.toInt()) }
                        .getOrDefault(MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) typeColor.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                            .clickable { onSelectActivity(type) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(typeColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(type.icon?.takeIf { it.isNotBlank() } ?: "•", fontSize = 15.sp)
                        }
                        Text(
                            type.name,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(typeColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * R20-v3: Fancy Duration-Ring für die "Nur Dauer"-Eingabe.
 * Canvas-Ring mit 270°-Bogen, Sweep-Gradient + große zentrale Anzeige.
 */
@Composable
private fun FancyDurationRing(
    minutes: Int,
    onMinutesChange: (Int) -> Unit
) {
    val displayHours = minutes / 60
    val displayMins = minutes % 60
    val progress = (minutes - 5f).coerceAtLeast(0f) / (240f - 5f)
    // Farben VOR dem Canvas holen
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVarColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 14.dp.toPx()
                val arcSize = size.minDimension - strokeWidth
                val topLeft = androidx.compose.ui.geometry.Offset(
                    (size.width - arcSize) / 2f,
                    (size.height - arcSize) / 2f
                )
                drawArc(
                    color = trackColor,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.6f),
                            primaryColor,
                            tertiaryColor
                        )
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
            // Zentrale Dauer-Anzeige
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        if (displayHours > 0) "$displayHours" else "$displayMins",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = onSurfaceColor
                    )
                    Text(
                        if (displayHours > 0) "h" else "m",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = onSurfaceVarColor,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                if (displayHours > 0) {
                    Text(
                        "${displayMins}m",
                        fontSize = 14.sp,
                        color = onSurfaceVarColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    stringResource(R.string.timeline_editor_per_day),
                    fontSize = 10.sp,
                    color = onSurfaceVarColor.copy(alpha = 0.7f)
                )
            }
        }
        // Slider
        Slider(
            value = minutes.toFloat(),
            onValueChange = { onMinutesChange(it.toInt()) },
            valueRange = 5f..240f,
            steps = 0,
            modifier = Modifier.fillMaxWidth().padding(horizontal = AevumSpacing.sm)
        )
        // Quick-Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(15, 30, 60, 90, 120, 180).forEach { quick ->
                val quickLabel = when {
                    quick >= 60 && quick % 60 == 0 -> "${quick / 60}h"
                    quick >= 60 -> "${quick / 60}h${quick % 60}m"
                    else -> "${quick}m"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AevumRadius.md))
                        .background(
                            if (minutes == quick) primaryColor.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                        .clickable { onMinutesChange(quick) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        quickLabel,
                        fontSize = 12.sp,
                        fontWeight = if (minutes == quick) FontWeight.Bold else FontWeight.Normal,
                        color = if (minutes == quick) primaryColor else onSurfaceVarColor
                    )
                }
            }
        }
    }
}

/**
 * M18.74: Kollabierbare Kategorie-Gruppen mit den Aktivitäten.
 * - Gruppen-Header: Icon, Name, Aktivitätsanzahl, Expand/Collapse-Pfeil.
 * - Aktivität-Zeilen: Icon, Name, Auswahl-Radio; Tap wählt die Aktivität.
 * - Header-Tap togglet die Gruppe (Pfeil + Inhalt folgen).
 */
@Composable
private fun ActivityPickerSection(
    groups: List<CategoryGroup>,
    selectedActivityId: String?,
    expandedCategories: Set<String?>,
    onToggleCategory: (String?) -> Unit,
    onSelectActivity: (ActivityType) -> Unit
) {
    Text(
        stringResource(R.string.timeline_editor_activity_required),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (groups.isEmpty()) {
        Text(
            stringResource(R.string.timeline_editor_no_activities),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        groups.forEach { group ->
            val expanded = group.categoryId in expandedCategories
            val accent = runCatching { Color(android.graphics.Color.parseColor(group.categoryColor)) }
                .getOrDefault(Color(0xFF94A3B8))
            // Header (tap = expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AevumRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .clickable { onToggleCategory(group.categoryId) }
                    .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(AevumRadius.sm))
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (group.categoryIcon.isBlank()) "•" else group.categoryIcon,
                        fontSize = 13.sp,
                        color = accent
                    )
                }
                Spacer(Modifier.width(AevumSpacing.sm))
                Text(
                    group.categoryName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${group.activities.size}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(AevumSpacing.sm))
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) stringResource(R.string.timeline_cd_collapse)
                    else stringResource(R.string.timeline_cd_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Inhalt (nur wenn expandiert)
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AevumSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    group.activities.forEach { type ->
                        val selected = type.id == selectedActivityId
                        val typeAccent = runCatching { Color(type.color.toInt()) }
                            .getOrDefault(MaterialTheme.colorScheme.primary)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AevumRadius.md))
                                .background(
                                    if (selected) accent.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                                )
                                .clickable { onSelectActivity(type) }
                                .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(typeAccent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (type.icon.isBlank()) "•" else type.icon,
                                    fontSize = 12.sp,
                                    color = typeAccent
                                )
                            }
                            Spacer(Modifier.width(AevumSpacing.sm))
                            Text(
                                type.name,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            // Auswahl-Indikator (Radio-artig)
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) accent
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: ActivityEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.savedSessionId) { state.savedSessionId?.let(onSaved) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { EditorHeader(state.isEditing, state.duration, onBack, viewModel::save) }
            item { BasicFields(state, viewModel::setTitle, viewModel::setDescription) }
            item { UnifiedActivitySelector(state.activityTypes, state.form.activityTypeId, viewModel::setActivityType) }
            item {
                VisualTimeEditorCard(
                    state = state,
                    onStartMinute = viewModel::setStartMinuteOfDay,
                    onEndMinute = viewModel::setEndMinuteOfDay,
                    onStartHour = viewModel::setStartHour,
                    onStartQuarter = viewModel::setStartMinute,
                    onEndHour = viewModel::setEndHour,
                    onEndQuarter = viewModel::setEndMinute,
                    onSnapStart = viewModel::snapStartTo,
                    onSnapEnd = viewModel::snapEndTo,
                    openEnded = state.form.endAt == null,
                    onOpenEndedChange = viewModel::setOpenEnded,
                    durationOnly = state.form.durationOnlyMinutes != null,
                    durationOnlyMinutes = state.form.durationOnlyMinutes,
                    onDurationOnlyModeChange = viewModel::setDurationOnlyMode,
                    onDurationOnlyMinutesChange = viewModel::setDurationOnly
                )
            }
            item { ValidationCard(state.validation, state.form.errorMessage) }
            item {
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.isEditing) stringResource(R.string.common_save_changes)
                        else stringResource(R.string.timeline_editor_save_new)
                    )
                }
            }
            item { Spacer(Modifier.height(AevumSpacing.xxl)) }
        }
    }
}

@Composable
fun ActivityDetailScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: ActivityDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    // R20-v2: Güte-Anpassungs-Dialog
    var showQualityDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val session = state.session
        if (session == null) {
            // Leerezustand — Aktivitaet nicht gefunden
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(AevumSpacing.lg),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyState(
                    title = stringResource(R.string.timeline_detail_not_found_title),
                    message = stringResource(R.string.timeline_detail_not_found_message),
                    actionLabel = stringResource(R.string.common_back),
                    onActionClick = onBack
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                // Zurueck-Button
                item {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(R.string.timeline_detail_back))
                    }
                }

                // Grosser Header-Bereich mit Aktivitaets-Icon, Name und Kategorie
                item { DetailHeaderCard(session, state) }

                // Zeit-Range als grosse Monospace-Anzeige
                item { DetailTimeRangeCard(state.range, session) }

                // Dauer als hervorgehobene Statistik-Karte
                item { DetailDurationCard(state.duration, state.activityType?.color ?: 0L) }

                // Statistik-Karten: Startzeit, Endzeit, Quelle
                item { DetailStatsGrid(session, state) }

                // Positivitaets-Score als farbiger Balken (falls verfuegbar)
                state.activityType?.let { type ->
                    if (type.positivityScore != 50) {
                        item { DetailPositivityCard(type.positivityScore) }
                    }
                }

                // Beschreibungstext falls vorhanden
                session.description?.let { desc ->
                    if (desc.isNotBlank()) {
                        item { DetailDescriptionCard(desc) }
                    }
                }

                // Bearbeiten / Güte / Loeschen Buttons am Ende
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        // R20-v2: Güte anpassen — sichtbarer Button statt nur Lang-Druck
                        OutlinedButton(
                            onClick = { showQualityDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = AevumSpacing.md)
                        ) {
                            Text(
                                stringResource(
                                    R.string.timeline_detail_quality_button,
                                    if (session.manualQualityOverride != null) "✎" else ""
                                ),
                                fontSize = 16.sp
                            )
                        }
                        Button(
                            onClick = { onEdit(session.id) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = AevumSpacing.md)
                        ) { Text(stringResource(R.string.common_edit), fontSize = 16.sp) }
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            ),
                            contentPadding = PaddingValues(vertical = AevumSpacing.md)
                        ) { Text(stringResource(R.string.common_delete), fontSize = 16.sp) }
                    }
                }
            }
        }
    }
    // Loesch-Bestaetigungsdialog
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.timeline_detail_delete_title)) },
            text = { Text(stringResource(R.string.timeline_detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
    // R20-v2: Güte-Anpassungs-Dialog im Detail-Screen
    if (showQualityDialog) {
        val session = state.session
        if (session != null) {
            QualityOverrideDialog(
                title = stringResource(R.string.common_adjust_quality),
                message = stringResource(R.string.timeline_quality_message, session.title),
                initialScore = session.manualQualityOverride ?: state.activityType?.positivityScore ?: 50,
                hasOverride = session.manualQualityOverride != null,
                onDismiss = { showQualityDialog = false },
                onSave = { score ->
                    viewModel.setQualityOverride(score)
                    showQualityDialog = false
                }
            )
        }
    }
}

/**
 * Header-Karte: grosses Aktivitaets-Icon in farbigem Kreis,
 * Aktivitaetsname gross, Kategorie als Chip.
 */
@Composable
private fun DetailHeaderCard(session: ActivitySession, state: ActivityDetailUiState) {
    val activityColor = if (state.activityType?.color != null && state.activityType.color != 0L) {
        Color(state.activityType.color)
    } else {
        categoryColor(state.category?.name ?: stringResource(R.string.common_other))
    }
    val icon = state.activityType?.icon?.takeIf { it.isNotBlank() } ?: "\u2022"

    AevumCard(variant = CardVariant.Gradient) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Aktivitaets-Icon in farbigem Kreis
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(activityColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    fontSize = 36.sp,
                    color = activityColor
                )
            }
            // Aktivitaetsname gross
            Text(
                session.title,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            // Kategorie als Chip
            CategoryChip(categoryId = state.category?.name ?: stringResource(R.string.common_other))
        }
    }
}

/**
 * Zeit-Range-Karte: Start - Ende als grosse Monospace-Anzeige.
 */
@Composable
private fun DetailTimeRangeCard(range: String, session: ActivitySession) {
    AevumCard(variant = CardVariant.Filled) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                range,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // Datum der Aktivitaet
            val dateStr = remember(session.startAt) {
                val ldt = Instant.ofEpochMilli(session.startAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                ldt.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM"))
            }
            Text(
                dateStr,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Dauer-Karte: hervorgehobene Statistik in einem farbigen Kasten.
 */
@Composable
private fun DetailDurationCard(duration: String, activityColorLong: Long) {
    val accentColor = if (activityColorLong != 0L) Color(activityColorLong) else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.lg),
        color = accentColor.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    stringResource(R.string.timeline_detail_duration),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    duration,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = accentColor
                )
            }
            // Stundenglas-Emoji als visuelles Element
            Text("\u23F1", fontSize = 40.sp)
        }
    }
}

/**
 * Statistik-Grid: 2x2 Kacheln mit Startzeit, Endzeit, Dauer, Quelle.
 */
@Composable
private fun DetailStatsGrid(session: ActivitySession, state: ActivityDetailUiState) {
    // Formatiere Start- und Endzeit
    val startTimeStr = remember(session.startAt) {
        val lt = Instant.ofEpochMilli(session.startAt)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        lt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
    val endTimeStr = remember(session.endAt) {
        session.endAt?.let { end ->
            val lt = Instant.ofEpochMilli(end)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
            lt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
    }
    // Quell-Icon und Label bestimmen
    val (sourceIcon, sourceLabel) = when (session.sourceType) {
        "MANUAL" -> "\u270F\uFE0F" to stringResource(R.string.timeline_detail_source_manual)
        "GEOFENCE_AUTO" -> "\uD83D\uDCCD" to stringResource(R.string.timeline_detail_source_geofence)
        "HEALTH_SLEEP_AUTO" -> "\uD83D\uDE34" to stringResource(R.string.timeline_detail_source_sleep_auto)
        "ACTIVITY_RECOGNITION_AUTO" -> "\uD83D\uDEB4" to stringResource(R.string.timeline_detail_source_movement_auto)
        else -> "\uD83D\uDCE5" to session.sourceType
    }

    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        // Reihe 1: Startzeit und Endzeit
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            DetailStatCard(
                icon = "\uD83D\uDD51",
                label = stringResource(R.string.timeline_detail_start),
                value = startTimeStr,
                modifier = Modifier.weight(1f)
            )
            DetailStatCard(
                icon = "\uD83D\uDD52",
                label = if (session.endAt == null) stringResource(R.string.timeline_detail_running)
                else stringResource(R.string.timeline_detail_end),
                value = endTimeStr ?: stringResource(R.string.timeline_detail_open),
                modifier = Modifier.weight(1f)
            )
        }
        // Reihe 2: Dauer und Quelle
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            DetailStatCard(
                icon = "\u23F1",
                label = stringResource(R.string.timeline_detail_duration),
                value = state.duration,
                modifier = Modifier.weight(1f)
            )
            DetailStatCard(
                icon = sourceIcon,
                label = stringResource(R.string.timeline_detail_source),
                value = sourceLabel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Einzelne Statistik-Kachel mit Icon, Label und Wert.
 */
@Composable
private fun DetailStatCard(
    icon: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    AevumCard(modifier = modifier, variant = CardVariant.Elevated, contentPadding = PaddingValues(AevumSpacing.md)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
            horizontalAlignment = Alignment.Start
        ) {
            Text(icon, fontSize = 22.sp)
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Positivitaets-Score als farbiger Balken mit Prozentangabe.
 */
@Composable
private fun DetailPositivityCard(score: Int) {
    val barColor = positivityColor(score)
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.timeline_detail_positivity), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "$score%",
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = barColor
                )
            }
            // Farbigere Hintergrundbahn
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                // Gefuellter Balken
                Box(
                    modifier = Modifier
                        .fillMaxWidth(score / 100f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(AevumRadius.full))
                        .background(barColor)
                )
            }
        }
    }
}

/**
 * Beschreibungskarte mit Text.
 */
@Composable
private fun DetailDescriptionCard(description: String) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
            Text(stringResource(R.string.timeline_detail_description), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(description, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Hilfsfunktion: Hex-Color-String (z.B. "#FF6366F1" oder "FF6366F1") zu Compose Color.
 * Gibt null zurueck, wenn der String nicht geparst werden kann.
 */
private fun parseHexColorOrNull(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    return try {
        Color(cleaned.toLong(16))
    } catch (_: NumberFormatException) {
        null
    }
}

@Composable
private fun TimelineHeader(
    state: TimelineUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onRunGapDetection: () -> Unit = {},
    // M18.61: Kalender-Icon → öffnet die Kalenderansicht
    onOpenCalendar: () -> Unit = {}
) {
    // M18.36: Header radikal kompakt — EINE Zeile, kein Overlap moeglich.
    // Vorher: zwei Zeilen (Titel/Datum + Chips) — der Datumstext konnte
    // mit dem Heute-Button kollidieren. Jetzt: [‹] [Titel+Datum] [Heute] [›]
    // in einer einzigen Row. "Luecken pruefen"-Button entfernt (User-Wunsch).
    AevumCard(variant = CardVariant.Gradient, contentPadding = PaddingValues(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousDay, modifier = Modifier.size(36.dp)) {
                Text("‹", fontSize = 24.sp)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    state.dayTitle.ifEmpty { stringResource(R.string.common_today) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.formattedDate,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Heute-Chip kompakt zwischen den Pfeilen
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onToday)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    stringResource(R.string.common_today),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onNextDay, modifier = Modifier.size(36.dp)) {
                Text("›", fontSize = 24.sp)
            }
            // M18.61: Kalender-Icon — öffnet die Kalenderansicht
            // (User: "in der timeline ein kalender icon haben und wenn man
            // darauf klickt kommt man zur kalender ansicht")
            IconButton(onClick = onOpenCalendar, modifier = Modifier.size(36.dp)) {
                Text("📅", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun SummaryCard(state: TimelineUiState) {
    // M18.32: Kompaktere Summary — kleinere Werte, weniger vertikaler Platz.
    AevumCard(contentPadding = PaddingValues(vertical = AevumSpacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SummaryValue(stringResource(R.string.common_captured), state.totalTracked)
            SummaryValue(stringResource(R.string.timeline_summary_entries), state.sessionCount.toString())
            SummaryValue(
                stringResource(R.string.timeline_summary_conflicts),
                if (state.hasOverlaps) stringResource(R.string.timeline_summary_conflicts_check)
                else stringResource(R.string.common_none)
            )
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CandidateReviewCard(
    candidates: List<CandidateReviewUi>,
    activityTypes: List<ActivityType>,
    onAccept: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onConvertGap: (String, String, String) -> Unit = { _, _, _ -> }
) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            // M15: Lücken-Candidates bekommen eine eigene Karte.
            val gapCandidates = candidates.filter { it.isGap }
            if (gapCandidates.isNotEmpty()) {
                Text(stringResource(R.string.timeline_gaps_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                gapCandidates.forEach { candidate ->
                    GapCandidateCard(
                        candidate = candidate,
                        activityTypes = activityTypes,
                        onConvert = onConvertGap,
                        onDismiss = onDismiss
                    )
                }
            }
            val regularCandidates = candidates.filter { !it.isGap }
            if (regularCandidates.isNotEmpty()) {
                Text(stringResource(R.string.timeline_detected_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                regularCandidates.forEach { candidate ->
                    AevumCard(variant = CardVariant.Filled) {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(candidate.title, fontWeight = FontWeight.SemiBold)
                                Text("${candidate.confidence}%", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                            }
                            Text("${candidate.timeRange} · ${candidate.duration}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(candidate.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // M16.6: Buttons gleichberechtigt sichtbar — drei
                            // Spalten mit weight(1f), damit "Verwerfen" nicht
                            // durch Text-Wrapping oder Layout-Quetschung
                            // unsichtbar wird. "Verwerfen" rot gerändert für
                            // sofortige Unterscheidung von "Bearbeiten".
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                            ) {
                                Button(
                                    onClick = { onAccept(candidate.id) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 4.dp, vertical = 10.dp
                                    )
                                ) { Text(stringResource(R.string.common_apply), fontSize = 12.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { onEdit(candidate.id) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 4.dp, vertical = 10.dp
                                    )
                                ) { Text(stringResource(R.string.common_edit), fontSize = 12.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { onDismiss(candidate.id) },
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 4.dp, vertical = 10.dp
                                    )
                                ) { Text(stringResource(R.string.common_discard), fontSize = 12.sp, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * M15: Spezielle Karte für Gap-Candidates (gestrichelter grauer Rand,
 * Schnellauswahl der häufigsten Kategorien). Wird vom User typischerweise
 * antippt, wenn er eine Lücke im Tag bemerkt und schnell schließen will.
 *
 * M16.4: Erweitert um "Andere Aktivität wählen"-Button, der einen
 * vollständigen ActivityType-Picker öffnet. Wenn die richtige Aktivität
 * nicht in den Schnellauswahl-Buttons ist, kann der User sie trotzdem
 * schnell finden.
 */
@Composable
private fun GapCandidateCard(
    candidate: CandidateReviewUi,
    activityTypes: List<ActivityType>,
    onConvert: (String, String, String) -> Unit,
    onDismiss: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Gestrichelter Rand: Linie mit dash-Intervallen
                drawRect(
                    color = borderColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = size,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(12f, 8f), 0f
                        )
                    )
                )
            }
            .padding(AevumSpacing.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.timeline_gap_unknown_time), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(candidate.timeRange + " · " + candidate.duration, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(
                    onClick = { onDismiss(candidate.id) },
                    label = { Text(stringResource(R.string.common_discard), fontSize = 11.sp) }
                )
            }
            Text(stringResource(R.string.timeline_gap_question), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 4 Schnellauswahl-Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                AssistChip(
                    onClick = { onConvert(candidate.id, "social", "social") },
                    label = { Text(stringResource(R.string.timeline_gap_friends), fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onConvert(candidate.id, "learning", "learning") },
                    label = { Text(stringResource(R.string.common_learning), fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onConvert(candidate.id, "household", "household") },
                    label = { Text(stringResource(R.string.timeline_gap_shopping), fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onConvert(candidate.id, "work", "work") },
                    label = { Text(stringResource(R.string.common_work), fontSize = 11.sp) }
                )
            }
            // M16.4: Button zum Öffnen des vollständigen ActivityPickers
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.timeline_gap_other_activity), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showPicker) {
        GapActivityPickerDialog(
            activityTypes = activityTypes,
            onDismiss = { showPicker = false },
            onPicked = { pickedType ->
                showPicker = false
                onConvert(candidate.id, pickedType.defaultCategoryId ?: "unknown", pickedType.id)
            }
        )
    }
}

/**
 * M16.4: Modal-Dialog mit allen verfügbaren ActivityTypes für die
 * Gap-Erfassung. Gefiltert auf sichtbare (enabled) Typen, gruppiert
 * nach Kategorie für schnelles Finden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GapActivityPickerDialog(
    activityTypes: List<ActivityType>,
    onDismiss: () -> Unit,
    onPicked: (ActivityType) -> Unit
) {
    val visibleTypes = remember(activityTypes) {
        // M16.4: Alle ActivityTypes sind aktuell aktiv (kein enabled-Flag
        // im Schema). Falls in Zukunft ein enabled-Flag eingeführt wird,
        // hier filtern.
        activityTypes.sortedBy { it.name }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timeline_picker_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                if (visibleTypes.isEmpty()) {
                    Text(
                        stringResource(R.string.timeline_picker_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                } else {
                    visibleTypes.forEach { type ->
                        ActivityTypeRow(type = type, onClick = { onPicked(type) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun ActivityTypeRow(type: ActivityType, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(AevumRadius.md),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text(type.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("→", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * M18.44: Quick-Create-Dialog (Google-Calendar-Prinzip).
 *
 * Erscheint, wenn der User in der Tagesansicht auf eine LEERE Zeitstelle
 * tippt. Enthält:
 *  - die getippte Uhrzeit (Startzeit, groß + farbig)
 *  - eine Aktivitäts-Auswahl (Icon + Name, scrollbar)
 *  - einen kleinen AevumTimePicker für die ENDZEIT (Standard: +1h)
 *  - zwei Aktionen: "Erstellen" (fixe Session) ODER "Jetzt aufzeichnen"
 *    (Session läuft ab der getippten Zeit weiter — endAt=null)
 */
@Composable
private fun QuickCreateDialog(
    minuteOfDay: Int,
    types: List<ActivityType>,
    onDismiss: () -> Unit,
    onCreate: (typeId: String, startMinute: Int, endMinute: Int) -> Unit,
    onStartNow: (typeId: String, startMinute: Int) -> Unit
) {
    var selectedTypeId by remember { mutableStateOf<String?>(null) }
    // M18.45 (User: "start und zielzeit manuell festlegen können"):
    // Die getippte Zeit ist nur der VORSCHLAG — der User kann die
    // Startzeit im Dialog frei anpassen.
    var startMinute by remember(minuteOfDay) { mutableStateOf(minuteOfDay.coerceIn(0, 1439)) }
    // Über Mitternacht darf die Endzeit kleiner als die Startzeit sein. Die
    // ViewModel-Schicht interpretiert das korrekt als Folgetag.
    var endMinute by remember(minuteOfDay) { mutableStateOf((minuteOfDay + 60) % 1440) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    // M18.45 (User: "oder statt zielzeit die auswahl, dass die app von
    // der startzeit aufgezeichnet werden sollte und weiter läuft"):
    // Segment-Umschalter: "Mit Endzeit" (fixe Session) oder
    // "Weiter aufzeichnen" (endAt = null, läuft ab Startzeit).
    var continueMode by remember { mutableStateOf(false) }
    val startLabel = "%02d:%02d".format(startMinute / 60, startMinute % 60)
    val endLabel = "%02d:%02d".format(endMinute / 60, endMinute % 60)
    val visibleTypes = remember(types) { types.sortedBy { it.name } }

    val hasValidFixedRange = endMinute != startMinute

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.timeline_editor_title_new), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                // M18.45: Beide Zeiten sind antippbar (öffnen den Picker).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStartPicker = !showStartPicker; showEndPicker = false },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Text(
                        stringResource(R.string.timeline_quick_start, startLabel),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF5A623)
                    )
                    Text(
                        if (continueMode) stringResource(R.string.timeline_quick_continue)
                        else stringResource(R.string.timeline_quick_end, endLabel),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                // M18.49 (User: "Das Popup hat immer noch nicht die
                // Funktionalität, Start und Zielzeit anzupassen bevor man
                // speichert, obwohl sie das haben sollte"): Die Picker waren
                // hinter kleinen TextButtons ("Startzeit ändern") unter der
                // langen Activity-Liste versteckt — bei vielen Aktivitäten
                // musste man erst scrollen und der 220dp-Picker quetschte
                // sich neben die Liste. Jetzt: Sobald ein Picker geöffnet
                // ist, wird die Activity-Liste ausgeblendet und der Picker
                // bekommt den kompletten Dialog-Platz. Ein Zurück-Button
                // ("← Aktivität wählen") führt zur Liste zurück.
                if (showStartPicker || showEndPicker) {
                    // ── Picker-Modus: volle Breite für die Zeitwahl ────
                    TextButton(onClick = {
                        showStartPicker = false
                        showEndPicker = false
                    }) { Text(stringResource(R.string.timeline_quick_back_to_activity)) }
                    if (showStartPicker) {
                        AevumTimePicker(
                            initialHour = startMinute / 60,
                            initialMinute = startMinute % 60,
                            accent = Color(0xFFF5A623),
                            onTimeChange = { h, m -> startMinute = (h * 60 + m).coerceIn(0, 1439) },
                            label = stringResource(R.string.timeline_time_start_label_long),
                            showDigitalDisplay = true
                        )
                    }
                    if (showEndPicker) {
                        AevumTimePicker(
                            initialHour = endMinute / 60,
                            initialMinute = endMinute % 60,
                            accent = MaterialTheme.colorScheme.primary,
                            onTimeChange = { h, m -> endMinute = (h * 60 + m).coerceIn(0, 1439) },
                            label = stringResource(R.string.timeline_time_end_label_long),
                            showDigitalDisplay = true
                        )
                    }
                } else {
                    // ── Normal-Modus: Modus-Umschalter + Aktivitäts-Wahl ──
                    // ── Modus-Umschalter (fancy Segment) ──────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AevumRadius.md))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        SegmentButton(
                            label = stringResource(R.string.timeline_editor_segment_fixed),
                            selected = !continueMode,
                            modifier = Modifier.weight(1f)
                        ) { continueMode = false }
                        SegmentButton(
                            label = stringResource(R.string.timeline_quick_segment_continue),
                            selected = continueMode,
                            modifier = Modifier.weight(1f)
                        ) { continueMode = true }
                    }
                    // ── Aktivitäts-Auswahl (Icon + Name) ─────────────────
                    if (visibleTypes.isEmpty()) {
                        Text(stringResource(R.string.timeline_picker_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    } else {
                        visibleTypes.forEach { type ->
                            val selected = type.id == selectedTypeId
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTypeId = type.id },
                                shape = RoundedCornerShape(AevumRadius.md),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                                ) {
                                    if (type.icon.isNotBlank()) {
                                        Text(type.icon, fontSize = 20.sp)
                                    }
                                    Text(type.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    if (selected) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    // ── Zeit-Buttons: öffnen den Picker-Modus ──────────
                    // M18.49: Die Zeitzeile oben im Titel öffnet ebenfalls
                    // den Picker (showStartPicker/showEndPicker togglen).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                    ) {
                        OutlinedButton(
                            onClick = { showStartPicker = true; showEndPicker = false },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.common_change_start)) }
                        if (!continueMode) {
                            OutlinedButton(
                                onClick = { showEndPicker = true; showStartPicker = false },
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.timeline_quick_end_change)) }
                        }
                    }
                    if (continueMode) {
                        Text(
                            stringResource(R.string.timeline_quick_continue_hint, startLabel),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                if (continueMode) {
                    // M18.45: "Weiter aufzeichnen" — Session ab Startzeit, endAt = null
                    Button(
                        onClick = { selectedTypeId?.let { onStartNow(it, startMinute) } },
                        enabled = selectedTypeId != null
                    ) { Text(stringResource(R.string.timeline_quick_record), color = Color.White, fontWeight = FontWeight.Bold) }
                } else {
                    // Feste Session mit Start- UND Endzeit
                    Button(
                        onClick = { selectedTypeId?.let { onCreate(it, startMinute, endMinute) } },
                        enabled = selectedTypeId != null && hasValidFixedRange
                    ) { Text(stringResource(R.string.common_create)) }
                }
            }
        }
    )
}

/** M18.45: Segment-Button für den Modus-Umschalter (fancy, aktiv = Primary). */
@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(AevumRadius.md)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 6.dp)
        )
    }
}

@Composable
private fun DayCalendarTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDeleteTrigger: (String) -> Unit,
    // M18.48: Löschen einer Session aus der Liste (mit Bestätigungsdialog).
    onDeleteSession: (String) -> Unit,
    // AEVUM-3: Lang-Druck auf eine Session → Güte dieser Aufzeichnung anpassen.
    onAdjustQuality: (TimelineSessionUi) -> Unit,
    // M18.44: Quick-Create aus der Tagesansicht (leere Stelle antippen)
    onCreateAt: (Int) -> Unit,
    // M18.66-FIX14: Wochenansicht
    weekView: Boolean,
    weekSessions: Map<LocalDate, List<TimelineSessionUi>>,
    onSetWeekView: (Boolean) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    // M18.83: Zoom-Persistierung — pixelsPerHour lebt im ViewModel (SharedPreferences-
    // persistiert). Vorher hielt dieses Composable den Zoom in einem lokalen
    // remember { mutableStateOf } → beim Ansichtwechsel (Liste↔Tag, Wochenansicht,
    // App-Restart) sprang der Zoom auf Default, obwohl M18.66-FIX14 die
    // Persistierung im ViewModel bereits gebaut hatte (toter UI-State,
    // M18.36-Muster). Jetzt: ViewModel-Wert rein, jede Änderung zurück ins VM.
    pixelsPerHour: Float,
    onPixelsPerHourChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var isListMode by remember { mutableStateOf(false) }
    // M18.58: Slide-Animation beim Wechsel Liste ↔ Tag. Die Richtung folgt
    // dem Ziel-Modus: Wechsel zu "Tag" schiebt von rechts rein (wie
    // Vorwärtsblättern), Wechsel zu "Liste" von links.
    val modeTransition = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Left
    val lanes = remember(sessions) { assignTimelineLanes(sessions) }
    val maxLane = (lanes.values.maxOrNull() ?: 0).coerceAtLeast(0)
    val laneCount = maxLane + 1

    // M18.26: Karte füllt den kompletten Viewport. Der Inhalt (Liste
    // ODER Tag-Ansicht) scrollt eigenständig — es gibt keinen
    // Nested-Scroll-Konflikt mehr mit einer äußeren LazyColumn.
    AevumCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Header: title + view mode toggle + zoom controls (fix)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.timeline_day_calendar), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isListMode) stringResource(R.string.timeline_day_calendar_sub_list)
                        else stringResource(R.string.timeline_day_calendar_sub_day),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                    ModeToggleButton(stringResource(R.string.common_list), isListMode) { isListMode = true; onSetWeekView(false) }
                    ModeToggleButton(stringResource(R.string.common_day), !isListMode && !weekView) { isListMode = false; onSetWeekView(false) }
                    // M18.66-FIX14: Tag/Woche-Icons rechts neben den Liste/Tag-Toggles.
                    // CalendarViewDay = Tagesansicht, DateRange = Wochenansicht.
                    IconButton(
                        onClick = { isListMode = false; onSetWeekView(false) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarViewDay,
                            contentDescription = stringResource(R.string.timeline_cd_day_view),
                            tint = if (!isListMode && !weekView) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { isListMode = false; onSetWeekView(true) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = stringResource(R.string.timeline_cd_week_view),
                            tint = if (weekView) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            // M18.66-FIX15: Icon vergrößert (18→24dp) — das
                            // 7-Tage-Icon war zu klein neben den Tag-Toggles.
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            // Zoom slider (in Tag- und Wochenansicht, nicht in Listenansicht)
            if (!isListMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AevumSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Text("−", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = pixelsPerHour,
                        onValueChange = onPixelsPerHourChange,
                        valueRange = TimelineUiState.MIN_PIXELS_PER_HOUR..TimelineUiState.MAX_PIXELS_PER_HOUR,
                        modifier = Modifier.weight(1f)
                    )
                    Text("+", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // M18.58: Slide-Animation beim Wechsel Liste ↔ Tag (nur über den
            // Umschalter — KEIN Gesten-Swipe). Wechsel zu "Tag" schiebt von
            // rechts rein (wie Vorwärtsblättern), Wechsel zu "Liste" von links.
            androidx.compose.animation.AnimatedContent(
                targetState = isListMode,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val slideInFromRight = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val slideInFromLeft = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { -it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val exitToLeft = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { -it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    val exitToRight = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    if (targetState) {
                        // → Liste: Liste kommt von links, Tag geht nach rechts
                        (slideInFromLeft togetherWith exitToRight).using(androidx.compose.animation.SizeTransform(clip = false))
                    } else {
                        // → Tag: Tag kommt von rechts, Liste geht nach links
                        (slideInFromRight togetherWith exitToLeft).using(androidx.compose.animation.SizeTransform(clip = false))
                    }
                },
                label = "mode-slide"
            ) { listMode ->
                if (listMode) {
                    // M18.22+: Sichtbare und ziehbare Scrollbar.
                    // Die EventListTimeline bekommt den vollen Viewport und
                    // verticalScroll. Zusaetzlich wird ein echter Scrollbar-Thumb
                    // rechts neben der Liste gerendert, dessen Position vom
                    // ScrollState abhaengt.
                    val listScrollState = rememberScrollState()
                    // M18.26: Viewport-Höhe dynamisch messen (statt hart 520dp),
                    // damit der Scrollbar-Thumb immer korrekt skaliert.
                    var viewportHeightPx by remember { mutableStateOf(0) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportHeightPx = it.height }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(listScrollState)
                                .padding(horizontal = AevumSpacing.md)
                                // M18.36: Bottom-Padding NUR hier — der FAB
                                // ueberdeckt den letzten Eintrag nicht, aber
                                // es entsteht kein toter Platz unter der
                                // Tag-Ansicht (die scrollt intern).
                                .padding(bottom = 88.dp)
                        ) {
                            EventListTimeline(
                                sessions = sessions,
                                triggers = triggers,
                                onOpen = onOpen,
                                onEdit = onEdit,
                                onDeleteTrigger = onDeleteTrigger,
                                onDeleteSession = onDeleteSession,
                                onAdjustQuality = onAdjustQuality
                            )
                        }
                        // M18.23: Sichtbarer Scrollbar-Thumb rechts neben der Liste.
                        // M18.25: EIN ScrollState fuer Column UND Thumb — vorher
                        // wurden zwei Instanzen erzeugt, der Thumb las den falschen.
                        val showScrollbar = listScrollState.maxValue > 0 && viewportHeightPx > 0
                        if (showScrollbar) {
                            val viewportPx = viewportHeightPx.toFloat()
                            val density = LocalDensity.current
                            val thumbHeight = (viewportPx * (viewportPx / (viewportPx + listScrollState.maxValue)))
                                .coerceAtLeast(with(density) { 24.dp.toPx() })
                            val thumbOffset = (listScrollState.value.toFloat() / listScrollState.maxValue.coerceAtLeast(1)) *
                                (viewportPx - thumbHeight)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 2.dp)
                                    .width(4.dp)
                                    .height(with(density) { thumbHeight.toDp() })
                                    .offset(y = with(density) { thumbOffset.toDp() })
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            )
                        }
                    }
                } else {
                    if (weekView) {
                        WeekTimeline(
                            weekSessions = weekSessions,
                            pixelsPerHour = pixelsPerHour,
                            onColumnTap = { day ->
                                onSetWeekView(false)
                                onSelectDay(day)
                            }
                        )
                    } else {
                        ZoomableDayTimeline(
                            sessions = sessions,
                            triggers = triggers,
                            lanes = lanes,
                            pixelsPerHour = pixelsPerHour,
                            onPixelsPerHourChange = onPixelsPerHourChange,
                            onOpen = onOpen,
                            onEdit = onEdit,
                            onAdjustQuality = onAdjustQuality,
                            onCreateAt = onCreateAt
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .padding(horizontal = AevumSpacing.sm, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * M13: Event-List Timeline.
 * Jeder Eintrag bekommt eine feste Höhe — keine proportionale Anzeige.
 * Damit sind nahe Ereignisse immer lesbar und klickbar.
 *
 * M18.8: Nutzerfreundlichkeits-Upgrade:
 *  - Tagesabschnitt-Header (Nacht/Morgen/Vormittag/Nachmittag/Abend)
 *    machen lange Listen scannbar — man findet eine Session sofort.
 *  - Laufende Sessions bekommen ein pulsierendes LIVE-Badge.
 *  - Größere Zeilen (12dp vertikal) = bessere Touch-Targets.
 */
@Composable
private fun EventListTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDeleteTrigger: (String) -> Unit = {},
    // M18.48: Löschen einer Session aus der Liste (mit Bestätigungsdialog).
    onDeleteSession: (String) -> Unit = {},
    // AEVUM-3: Lang-Druck auf eine Session → Güte dieser Aufzeichnung anpassen.
    onAdjustQuality: (TimelineSessionUi) -> Unit = {}
) {
    val merged = remember(sessions, triggers) {
        (sessions.map { TimelineEntry.Session(it) } + triggers.map { TimelineEntry.Trigger(it) })
            .sortedBy { entry ->
                when (entry) {
                    is TimelineEntry.Session -> entry.session.startMinuteOfDay
                    is TimelineEntry.Trigger -> entry.trigger.minuteOfDay
                }
            }
    }
    if (merged.isEmpty()) {
        Text(
            stringResource(R.string.timeline_list_empty),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = AevumSpacing.md)
        )
        return
    }

    // M18.8: Nach Tagesabschnitten gruppieren — sofort scannbar.
    val grouped = remember(merged) { groupByDayPart(merged) }
    // M18.48: Zu löschende Session (für den Sicherheitsdialog). Erst nach
    // Bestätigung wird onDeleteSession aufgerufen.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        grouped.forEach { (part, entries) ->
            // Abschnitts-Header
            Text(
                stringResource(part.labelRes).uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
            )
            entries.forEach { entry ->
                when (entry) {
                    is TimelineEntry.Session -> {
                        // M18.5: Positivitäts-Farbcodierung — die Timeline wird
                        // zum visuellen Tagebuch: grüner Punkt = gute Zeit,
                        // roter Punkt = schlechte Zeit (Instagram gucken).
                        // M18.13: Icon (Emoji) + custom Farbe der Aktivität.
                        EventListRow(
                            time = entry.session.time,
                            title = entry.session.title,
                            detail = "${entry.session.range} · ${entry.session.duration}",
                            accent = if (entry.session.activityColor != 0L) Color(entry.session.activityColor) else positivityColor(entry.session.positivityScore),
                            icon = entry.session.activityIcon,
                            kind = if (entry.session.isAuto) stringResource(R.string.timeline_kind_auto)
                            else stringResource(R.string.common_captured),
                            isLive = entry.session.isRunning,
                            // AEVUM-3: Override-Hinweis („Güte ✎") bei manuell
                            // angepasster Aufzeichnung.
                            qualityBadge = if (entry.session.hasQualityOverride) "${entry.session.positivityScore} ✎" else null,
                            onClick = { onOpen(entry.session.id) },
                            onEdit = { onEdit(entry.session.id) },
                            onDelete = { pendingDeleteId = entry.session.id },
                            onLongPress = { onAdjustQuality(entry.session) }
                        )
                    }
                    is TimelineEntry.Trigger -> {
                        // Trigger-Eintrag mit Loeschen-Button (Trash-Icon)
                        EventListRow(
                            time = entry.trigger.time,
                            title = "◆ ${entry.trigger.label}",
                            detail = stringResource(R.string.timeline_confidence, entry.trigger.confidence),
                            accent = MaterialTheme.colorScheme.secondary,
                            kind = stringResource(R.string.timeline_kind_trigger),
                            onClick = {},
                            onEdit = {},
                            onDelete = { onDeleteTrigger(entry.trigger.id) }
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                    thickness = 1.dp
                )
            }
        }
    }

    // M18.48: Sicherheitsdialog für das Löschen einer Session aus der Liste.
    // Der User muss die Löschung explizit bestätigen — kein Sofort-Löschen.
    pendingDeleteId?.let { id ->
        val session = sessions.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.timeline_delete_session_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.timeline_delete_session_message,
                        session?.title ?: stringResource(R.string.timeline_this_activity),
                        session?.range ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(id)
                        pendingDeleteId = null
                    }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** M18.8: Tagesabschnitte für scannbare Listen-Gruppierung. */
private enum class DayPart(val labelRes: Int, val startMin: Int, val endMin: Int) {
    Nacht(R.string.timeline_daypart_night, 0, 5 * 60),
    Morgen(R.string.timeline_daypart_morning, 5 * 60, 10 * 60),
    Vormittag(R.string.timeline_daypart_forenoon, 10 * 60, 13 * 60),
    Nachmittag(R.string.timeline_daypart_afternoon, 13 * 60, 17 * 60),
    Abend(R.string.timeline_daypart_evening, 17 * 60, 21 * 60),
    Spaet(R.string.timeline_daypart_late_evening, 21 * 60, 24 * 60);

    companion object {
        fun of(minute: Int): DayPart = entries.first { minute in it.startMin until it.endMin }
    }
}

/** M18.8: Entries nach Tagesabschnitt gruppieren (Reihenfolge beibehalten). */
private fun groupByDayPart(
    entries: List<TimelineEntry>
): List<Pair<DayPart, List<TimelineEntry>>> {
    return entries.groupBy { entry ->
        val minute = when (entry) {
            is TimelineEntry.Session -> entry.session.startMinuteOfDay
            is TimelineEntry.Trigger -> entry.trigger.minuteOfDay
        }
        DayPart.of(minute)
    }.toList().sortedBy { it.first.startMin }
}

private sealed class TimelineEntry {
    data class Session(val session: TimelineSessionUi) : TimelineEntry()
    data class Trigger(val trigger: TriggerEventUi) : TimelineEntry()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EventListRow(
    time: String,
    title: String,
    detail: String,
    accent: Color,
    kind: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    // M18.8: Laufende Session -> pulsierendes LIVE-Badge
    isLive: Boolean = false,
    // M18.13: Icon (Emoji) der Aktivität
    icon: String = "•",
    // Loeschen-Callback. Wenn gesetzt, wird ein Trash-Button angezeigt.
    onDelete: (() -> Unit)? = null,
    // AEVUM-3: Lang-Druck → Güte der Aufzeichnung anpassen (Quality-Slider).
    onLongPress: (() -> Unit)? = null,
    // AEVUM-3: Override-Badge („<Score> ✎"), wenn die Güte manuell angepasst wurde.
    qualityBadge: String? = null
) {
    // M18.20: Farbige Karte — Akzentbalken links, farbiger Hintergrund,
    // Icon-Kreis, farbiger Zeit-Chip. Jede Zeile ist jetzt ein buntes
    // Element statt einer nackten Textzeile.
    // M18.32: Kreativ-Upgrade — sanfter Farbverlauf statt flacher Flaeche,
    // Dauer-Chip rechts, dezenter Zeit-Pfeil. Die Zeile wirkt jetzt wie
    // eine kleine Karte mit Tiefe statt einer flachen Liste.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.16f),
                        accent.copy(alpha = 0.05f),
                        Color.Transparent
                    )
                )
            )
            .combinedClickable(
                onClick = onClick,
                // AEVUM-3: Lang-Druck öffnet den Güte-Slider für diese
                // Aufzeichnung (Override statt Dauer-Einstellung).
                onLongClick = onLongPress ?: {},
                onLongClickLabel = stringResource(R.string.common_adjust_quality)
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // Akzentbalken links — volle Aktivitätsfarbe
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(44.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                .background(accent)
        )
        // M18.13: Icon in farbigem Kreis (größer, kräftiger)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (icon.isBlank()) "•" else icon,
                fontSize = 18.sp
            )
        }
        // Zeit als farbiger Chip
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                .background(accent.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                time,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // M18.32: Dauer-Chip rechts (Monospace, farbig) — die Dauer war
        // vorher nur im Detail-Text versteckt, jetzt sofort lesbar.
        val durationText = detail.substringAfter("· ").ifEmpty { "" }
        if (durationText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    durationText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
            }
        }
        // M18.8: LIVE-Badge für laufende Sessions — sofort erkennbar
        if (isLive) {
            Row(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Text(
                    "LIVE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    letterSpacing = 0.8.sp
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    kind,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
            }
        }
        // AEVUM-3: Override-Badge — zeigt die manuell angepasste Güte an.
        qualityBadge?.let { badge ->
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                    .background(positivityColor(badge.substringBefore(" ").toIntOrNull() ?: 50).copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    badge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = positivityColor(badge.substringBefore(" ").toIntOrNull() ?: 50)
                )
            }
        }
        // Trash-Button fuer loeschbare Eintraege (z.B. Trigger)
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "\u2715",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * M13: Zoomable Day Timeline.
 * - Vertikaler Single-Finger-Scroll.
 * - Pinch-Zoom NUR via Slider (gesture-conflict-frei).
 * - Lane-System zur Vermeidung von Text-Overlap.
 */
@Composable
private fun ZoomableDayTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    lanes: Map<String, Int>,
    pixelsPerHour: Float,
    onPixelsPerHourChange: (Float) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    // AEVUM-3: Lang-Druck auf eine Session → Güte dieser Aufzeichnung anpassen.
    onAdjustQuality: (TimelineSessionUi) -> Unit,
    onCreateAt: (Int) -> Unit
) {
    val totalHeight = (24 * pixelsPerHour).dp
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val blockX = 56.dp
    val axisX = 48.dp
    val blockRightPadding = 12.dp
    val laneCount = (lanes.values.maxOrNull() ?: 0).coerceAtLeast(0) + 1
    // M18.15: TextMeasurer für Emoji-Icons im Canvas (drawText).
    val textMeasurer = rememberTextMeasurer()

    // Track now-time for the marker line — only computed at composition
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val nowMinute = TimeFormatting.minutesOfDay(now, zone).coerceIn(0, 1440)

    // M18.26: Voller Viewport statt fixe 560dp. Die Tag-Ansicht ist der
    // EINZIGE Scroll-Container des Screens — kein Nested-Scroll mehr.
    // M18.66-FIX21: Container-Breite für die horizontale Lane-Versetzung
    // der Labels (Google-Calendar-Prinzip).
    var containerWidthPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerWidthPx = it.width }
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Pinch-to-zoom area: vertikaler Scroll + zwei separate
            // Gesten-Handler für Zoom (wir machen es über den Slider,
            // nicht über Gesten — Konflikt-frei).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    // M18.44: Google-Calendar-Prinzip — Tap auf eine LEERE
                    // Zeitstelle öffnet den Quick-Create-Dialog. Tap auf
                    // einen Session-Block öffnet die Session (gleiche
                    // Logik wie die Text-Labels, nur über die volle
                    // Block-Fläche). Die Position kommt relativ zum Canvas
                    // (der Scroll-Versatz wird von Compose automatisch
                    // zurückgerechnet), daher kein scrollState nötig.
                    .pointerInput(sessions, pixelsPerHour, lanes, laneCount) {
                        detectTapGestures { offset ->
                            val pxHour = pixelsPerHour.dp.toPx()
                            // Canvas-Höhe = 24 * pxHour. Eine Stunde entspricht
                            // 60 Minuten; die alte Formel teilte zusätzlich durch
                            // 24 und ordnete fast den gesamten Tag den ersten
                            // 60 Minuten zu.
                            val minute = ((offset.y / pxHour) * 60f).toInt().coerceIn(0, 1439)
                            val hit = sessions.firstOrNull { s ->
                                val startMin = s.startMinuteOfDay.coerceIn(0, 1440)
                                val rawEnd = s.endMinuteOfDay
                                val endMin = when {
                                    rawEnd <= 0 -> startMin + 1
                                    rawEnd < startMin + 1 -> startMin + 1
                                    rawEnd > 1440 -> 1440
                                    else -> rawEnd
                                }
                                // M18.66-FIX17: Hit-Test mit IDENTISCHER Geometrie
                                // wie die Zeichnung (Lane-Verschiebung + 18dp
                                // Mindesthöhe). Vorher prüfte der Hit-Test nur
                                // die reale Blockhöhe OHNE Lane und OHNE
                                // Mindesthöhe — bei kurzen Activities (gezeichnet
                                // mit 18dp Minimum) war nur der obere Teil
                                // klickbar. Jetzt ist der GESAMTE sichtbare
                                // Block Trefferfläche.
                                val lane = lanes[s.id] ?: 0
                                val top = (startMin / 60f) * pxHour
                                val bottom = (endMin / 60f) * pxHour
                                val totalH = (bottom - top).coerceAtLeast(18.dp.toPx())
                                // M18.66-FIX21: Hit-Test mit IDENTISCHER Geometrie
                                // wie die Zeichnung — volle Blockhöhe, horizontale
                                // Lane-Versetzung (Google-Calendar-Prinzip).
                                val blockWidthPx = size.width - blockX.toPx() - blockRightPadding.toPx()
                                val laneWidth = blockWidthPx / laneCount.coerceAtLeast(1)
                                val blockXOffset = blockX.toPx() + lane * laneWidth
                                // Nur der Block-Bereich (rechts der Uhr-Achse)
                                // ist Trefferfläche — ein Tap auf der Achse
                                // oder daneben erzeugt eine neue Aktivität.
                                offset.x >= blockXOffset && offset.x <= blockXOffset + laneWidth &&
                                    offset.y >= top - 2f && offset.y <= top + totalH + 2f
                            }
                            if (hit != null) onOpen(hit.id) else onCreateAt(minute)
                        }
                    }
                ) {
                    val pxHour = pixelsPerHour.dp.toPx()
                    val axisXLocal = axisX.toPx()
                    val blockXLocal = blockX.toPx()
                    val blockRightPaddingLocal = blockRightPadding.toPx()
                    val blockWidth = size.width - blockXLocal - blockRightPaddingLocal

                    // M18.26: Tagesabschnitt-Tönung (Google-Calendar-Prinzip).
                    // Nacht = tiefblau, Morgen = warmes Gelb, Mittag = hell,
                    // Abend = Orange-Rot. Jede Zone ist ein sanfter Farbverlauf
                    // ueber die volle Breite — der Tag wird auf einen Blick
                    // lesbar und die aktuelle Uhrzeit gefuehlt.
                    val zoneGradient = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0xFF1E1B4B).copy(alpha = 0.55f), // 00:00 Nacht
                            0.21f to Color(0xFF1E1B4B).copy(alpha = 0.45f), // ~05:00 Nacht-Ende
                            0.24f to Color(0xFFF59E0B).copy(alpha = 0.14f), // ~06:00 Morgen
                            0.42f to Color(0xFFFDE68A).copy(alpha = 0.10f), // ~10:00 Vormittag
                            0.54f to Color(0xFFFEF3C7).copy(alpha = 0.08f), // ~13:00 Mittag
                            0.71f to Color(0xFFFDBA74).copy(alpha = 0.12f), // ~17:00 Nachmittag
                            0.79f to Color(0xFFFB7185).copy(alpha = 0.16f), // ~19:00 Abend
                            1.00f to Color(0xFF1E1B4B).copy(alpha = 0.55f)  // 24:00 Nacht
                        )
                    )
                    drawRect(brush = zoneGradient, size = Size(size.width, size.height))

                    // Hour grid + axis
                    drawLine(
                        color = Color.White.copy(alpha = 0.18f),
                        start = Offset(axisXLocal, 0f),
                        end = Offset(axisXLocal, size.height),
                        strokeWidth = 2f
                    )
                    for (hour in 0..24) {
                        val y = hour * pxHour
                        val isMajor = hour % 3 == 0
                        drawLine(
                            color = Color.White.copy(alpha = if (isMajor) 0.22f else 0.08f),
                            start = Offset(axisXLocal, y),
                            end = Offset(size.width, y),
                            strokeWidth = if (isMajor) 1.5f else 0.5f
                        )
                    }
                    // Triggers
                    triggers.forEach { trigger ->
                        val y = (trigger.minuteOfDay / 60f) * pxHour
                        drawCircle(
                            color = Color(0xFF2DD4BF),
                            radius = 4.dp.toPx(),
                            center = Offset(axisXLocal, y)
                        )
                        drawLine(
                            color = Color(0xFF2DD4BF).copy(alpha = 0.32f),
                            start = Offset(axisXLocal, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }
                    // Sessions — lane-aware rendering
                    sessions.forEach { session ->
                        // M16.5: Mitternacht-Schlaf hat startMinuteOfDay=0 und
                        // endMinuteOfDay=510 im Folgetag. Der alte
                        // coerceIn(startMin+5, 1440) hätte das Ende auf
                        // startMin+5 verschoben (bei Mitternacht auf 5 = 00:05)
                        // und die Session unsichtbar gemacht. Jetzt:
                        // endMin.coerceAtLeast(startMin+1) — nur floor,
                        // kein Ceiling bei 1440. Damit wird die korrekte
                        // Tagessichtbarkeit garantiert.
                        val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
                        val rawEnd = session.endMinuteOfDay
                        val endMin = when {
                            rawEnd <= 0 -> startMin + 1
                            rawEnd < startMin + 1 -> startMin + 1
                            rawEnd > 1440 -> 1440
                            else -> rawEnd
                        }
                        val lane = lanes[session.id] ?: 0
                        val topY = (startMin / 60f) * pxHour
                        val bottomY = (endMin / 60f) * pxHour
                        val totalH = (bottomY - topY).coerceAtLeast(20f)
                        // M18.66-FIX21 (User: "soziales 18:00–21:03 + Autofahrt
                        // 21:00–21:30 → soziales endet optisch bei ~19:30"):
                        // ROOT CAUSE — die alte Lane-Logik TEILTE die Blockhöhe
                        // durch die Lane-Anzahl (laneH = (totalH-4f)/laneCount).
                        // Bei 2 Lanes wurde ein 3h-Block nur 1.5h hoch gezeichnet.
                        // Fix: Google-Calendar-Prinzip — JEDER Block behält seine
                        // volle Höhe, überlappende Blöcke werden HORIZONTAL in
                        // Spalten versetzt (Lane 0 = volle Breite, Lane 1 = rechte
                        // Hälfte, Lane 2 = rechtes Drittel, ...).
                        val blockWidthPx = blockWidth.coerceAtLeast(0f)
                        val laneWidth = blockWidthPx / laneCount.coerceAtLeast(1)
                        val blockXOffset = blockXLocal + lane * laneWidth
                        val laneHeight = totalH
                        // M18.15: Custom-Farbe der Aktivität bevorzugen,
                        // sonst Kategorie-Farbe.
                        val color = if (session.activityColor != 0L) Color(session.activityColor) else categoryColor(session.categoryName)
                        // M18.48 (User: "die Farbe etwas prägnanter"): Der
                        // Block-Fill wurde von 0.42 auf 0.62 angehoben, damit
                        // die Aktivitätsfarbe klar erkennbar ist. Bei
                        // Überlappungen 0.8 (weiterhin leicht abgesetzt).
                        val fillAlpha = if (session.isOverlapping) 0.80f else 0.62f
                        drawRoundRect(
                            color = color.copy(alpha = fillAlpha),
                            topLeft = Offset(blockXOffset, topY),
                            size = Size(laneWidth, laneHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                        )
                        // M18.60-FIX (User: "der Strich ragt zur Hälfte über
                        // die Activity"): ROOT CAUSE — der Akzentbalken war
                        // eine drawLine mit dicker strokeWidth bei y=laneY+1.
                        // drawLine zentriert den Stroke um die y-Position:
                        // bei laneHeight=100 ging der Strich von laneY-48 bis
                        // laneY+51 — ragt ~50% über die Block-Oberkante und
                        // endet in der Block-Mitte. Fix: drawRoundRect mit
                        // exakt der Blockhöhe, bündig an der Oberkante.
                        drawRoundRect(
                            color = color.copy(alpha = 0.95f),
                            topLeft = Offset(blockXOffset, topY),
                            size = Size(4.dp.toPx(), laneHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                        )
                        drawLine(
                            color = color.copy(alpha = 0.85f),
                            start = Offset(blockXOffset, topY),
                            end = Offset(blockXOffset + laneWidth, topY),
                            strokeWidth = 1.5f
                        )
                        // M18.15: Icon (Emoji) der Aktivität im Block zeichnen.
                        // M18.21: Schwelle auf 16dp gesenkt — dank Mindesthöhe
                        // (18dp) haben auch kurze Aktivitäten ein sichtbares Icon.
                        // M18.48 (User: "Icons sollten sichtbar sein, aber bei
                        // kleinen Elementen nicht überladen"): Icon leicht größer
                        // (14sp) und auf einem kleinen weißen, halbtransparenten
                        // Pill-Hintergrund — dadurch hebt es sich von der Farbe
                        // ab und bleibt bei kurzen Blöcken lesbar, ohne den
                        // Block zu überladen.
                        if (laneHeight >= 18.dp.toPx() && session.activityIcon.isNotBlank() && session.activityIcon != "•") {
                            val iconSize = 14.dp.toPx()
                            val pillW = 24.dp.toPx()
                            val pillH = (iconSize + 4.dp.toPx())
                            val iconX = blockXOffset + 6.dp.toPx()
                            val iconY = topY + (laneHeight - pillH) / 2f
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.28f),
                                topLeft = Offset(iconX, iconY),
                                size = Size(pillW, pillH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f, pillH / 2f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = session.activityIcon,
                                topLeft = Offset(iconX + 3.dp.toPx(), iconY + (pillH - iconSize) / 2f),
                                style = TextStyle(fontSize = 14.sp)
                            )
                        }
                    }
                    // Now line
                    if (nowMinute in 0..1440) {
                        val nowY = (nowMinute / 60f) * pxHour
                        drawLine(
                            color = Color(0xFFEC4899),
                            start = Offset(axisXLocal, nowY),
                            end = Offset(size.width, nowY),
                            strokeWidth = 1.5f
                        )
                    }
                }

                // Hour labels overlaid (Compose Text, not Canvas) for proper typography
                for (hour in 0..24 step 2) {
                    Text(
                        "%02d:00".format(hour),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, top = (hour * pixelsPerHour - 6).dp.coerceAtLeast(0.dp))
                    )
                }

                // Session labels with click + edit (positioned by lane)
                // M18.66-FIX12: Labels zoom-abhängig ein-/ausblenden.
                // Bei weit herausgezoomten Blöcken (wenige Pixel pro Minute)
                // werden kurze Activities zu winzigen Balken — die Labels
                // überlappen dann. Jetzt: Label nur anzeigen, wenn die
                // Block-Höhe in Pixeln groß genug ist (>= 22dp). Das ist
                // abhängig vom Zoom (pixelsPerHour) und der Activity-Dauer.
                // Bei Reinzoomen erscheinen die Labels automatisch wieder.
                val minLabelHeightPx = with(LocalDensity.current) { 22.dp.toPx() }
                sessions.forEach { session ->
                    // M16.5: Mitternacht-sicheres Clipping (siehe oben).
                    val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
                    val rawEnd = session.endMinuteOfDay
                    val endMin = when {
                        rawEnd <= 0 -> startMin + 1
                        rawEnd < startMin + 1 -> startMin + 1
                        rawEnd > 1440 -> 1440
                        else -> rawEnd
                    }
                    val lane = lanes[session.id] ?: 0
                    val topY = (startMin / 60f) * pixelsPerHour
                    val totalH = (endMin / 60f - startMin / 60f) * pixelsPerHour
                    // M18.66-FIX21: Labels mit IDENTISCHER Geometrie wie die
                    // Zeichnung — volle Blockhöhe, horizontale Lane-Versetzung.
                    val blockWidthPx = (containerWidthPx - with(LocalDensity.current) { blockX.toPx() } - with(LocalDensity.current) { blockRightPadding.toPx() })
                        .coerceAtLeast(0f)
                    val laneWidthPx = blockWidthPx / laneCount.coerceAtLeast(1)
                    val laneX = with(LocalDensity.current) { blockX.toPx() } + lane * laneWidthPx
                    val laneHeightPx = totalH.coerceAtLeast(with(LocalDensity.current) { 18.dp.toPx() })

                    // M18.66-FIX12: Label nur anzeigen, wenn genug Platz.
                    // Bedingung: Block-Höhe >= 22dp (Label-Höhe + Padding).
                    // Bei pixelsPerHour=30 (sehr weit raus) = 0.5dp/min.
                    // Eine 5-min-Activity = 2.5dp → kein Label.
                    // Eine 60-min-Activity = 30dp → Label sichtbar.
                    // Bei pixelsPerHour=120 (rein) = 2dp/min.
                    // Eine 5-min-Activity = 10dp → kein Label.
                    // Eine 15-min-Activity = 30dp → Label sichtbar.
                    if (laneHeightPx >= minLabelHeightPx) {
                        Box(
                            modifier = Modifier
                                .padding(start = laneX.dp, top = topY.dp)
                                .pointerInput(session.id) {
                                    detectTapGestures(
                                        onTap = { onOpen(session.id) },
                                        // AEVUM-3: Lang-Druck → Güte der
                                        // Aufzeichnung anpassen (Override).
                                        onLongPress = { onAdjustQuality(session) }
                                    )
                                }
                        ) {
                            Text(
                                text = "${session.title} · ${session.duration}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Pinch-Indikator + Zoom-Slider ist bereits in DayCalendarTimeline
            // — hier nur der aktuelle Wert als Hinweis.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.sm, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.timeline_zoom_hint, "%.0f".format(pixelsPerHour)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * M18.66-FIX14: Wochenansicht — 7 Tage (Mo–So) nebeneinander.
 * Jede Spalte ist 1/7 der Breite und zeigt einen vertikalen Zeitstrahl
 * mit farbigen Activity-Blöcken (wie die Tagesansicht, aber schmaler
 * und ohne Labels — nur die farbigen Blöcke).
 *
 * Tap auf eine Spalte schaltet zurück in die Tagesansicht des getappten Tages.
 * Der Zoom (pixelsPerHour) ist derselbe wie in der Tagesansicht.
 * Vertikales Scrollen für 24h wie in der Tagesansicht.
 */
@Composable
private fun WeekTimeline(
    weekSessions: Map<LocalDate, List<TimelineSessionUi>>,
    pixelsPerHour: Float,
    onColumnTap: (LocalDate) -> Unit
) {
    val totalHeight = (24 * pixelsPerHour).dp
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val nowMinute = TimeFormatting.minutesOfDay(now, zone).coerceIn(0, 1440)
    val today = LocalDate.now()

    // M18.66-FIX14: Wochentage deutsch kurz (Mo, Di, Mi, Do, Fr, Sa, So)
    val dayLabels = listOf(
        stringResource(R.string.common_monday),
        stringResource(R.string.common_tuesday),
        stringResource(R.string.common_wednesday),
        stringResource(R.string.common_thursday),
        stringResource(R.string.common_friday),
        stringResource(R.string.common_saturday),
        stringResource(R.string.common_sunday)
    )
    // Sortiere die Map nach Datum (Mo zuerst)
    val sortedDays = weekSessions.entries.sortedBy { it.key }
    // Falls die Map leer ist (sollte nicht passieren), berechne die Tage
    // aus dem heutigen Datum als Fallback.
    val days = if (sortedDays.size == 7) {
        sortedDays.map { it.key to it.value }
    } else {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        (0..6).map { offset ->
            val day = monday.plusDays(offset.toLong())
            day to (weekSessions[day] ?: emptyList())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: 7 Wochentage + Datum nebeneinander
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                days.forEachIndexed { index, (day, _) ->
                    val isToday = day == today
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(AevumRadius.sm))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                dayLabels[index],
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isToday) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "%d.".format(day.dayOfMonth),
                                fontSize = 9.sp,
                                color = if (isToday) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // Scrollbarer Bereich mit 7 Spalten
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    days.forEachIndexed { _, (day, daySessions) ->
                        val isToday = day == today
                        WeekColumn(
                            daySessions = daySessions,
                            pixelsPerHour = pixelsPerHour,
                            isToday = isToday,
                            nowMinute = if (isToday) nowMinute else -1,
                            modifier = Modifier
                                .weight(1f)
                                .pointerInput(day) {
                                    detectTapGestures { onColumnTap(day) }
                                }
                        )
                    }
                }
            }
        }
    }
}

/**
 * M18.66-FIX14: Eine einzelne Wochenspalte — vertikaler Zeitstrahl
 * mit farbigen Session-Blöcken. Ohne Labels, ohne Trigger-Marker.
 * Nur die farbigen Blöcke, wie in der Tagesansicht aber schmaler.
 */
@Composable
private fun WeekColumn(
    daySessions: List<TimelineSessionUi>,
    pixelsPerHour: Float,
    isToday: Boolean,
    nowMinute: Int,
    modifier: Modifier = Modifier
) {
    val totalHeight = (24 * pixelsPerHour).dp
    // M18.66-FIX15: Activity-Icons in der Wochenansicht — wie in der
    // Tagesansicht zoom-abhängig: Icon nur wenn der Block hoch genug
    // ist (>= 18dp). Bei weit herausgezoomten kurzen Activities
    // verschwinden die Icons; beim Reinzoomen erscheinen sie wieder.
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minIconHeightPx = with(density) { 18.dp.toPx() }
    val iconSize = with(density) { 12.dp.toPx() }      // etwas kleiner als Tagesansicht (schmale Spalten)
    val pillW = with(density) { 20.dp.toPx() }
    val pillH = iconSize + with(density) { 4.dp.toPx() }
    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .height(totalHeight)
    ) {
        val pxHour = pixelsPerHour.dp.toPx()
        val w = size.width
        val h = size.height

        // Sanfter Hintergrund für "heute"
        if (isToday) {
            drawRect(
                color = Color.White.copy(alpha = 0.04f),
                size = Size(w, h)
            )
        }

        // Stundengitter (alle 3 Stunden etwas heller)
        for (hour in 0..24) {
            val y = hour * pxHour
            val isMajor = hour % 3 == 0
            drawLine(
                color = Color.White.copy(alpha = if (isMajor) 0.14f else 0.05f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (isMajor) 1f else 0.5f
            )
        }

        // Session-Blöcke — nur farbige Blöcke, keine Labels
        daySessions.forEach { session ->
            val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
            val rawEnd = session.endMinuteOfDay
            val endMin = when {
                rawEnd <= 0 -> startMin + 1
                rawEnd < startMin + 1 -> startMin + 1
                rawEnd > 1440 -> 1440
                else -> rawEnd
            }
            val topY = (startMin / 60f) * pxHour
            val bottomY = (endMin / 60f) * pxHour
            val blockH = (bottomY - topY).coerceAtLeast(3f)
            val blockW = (w - 2f).coerceAtLeast(0f)
            val color = if (session.activityColor != 0L) Color(session.activityColor) else categoryColor(session.categoryName)
            val fillAlpha = if (session.isOverlapping) 0.80f else 0.62f
            drawRoundRect(
                color = color.copy(alpha = fillAlpha),
                topLeft = Offset(1f, topY),
                size = Size(blockW, blockH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            // Akzentbalken links (wie Tagesansicht)
            drawRoundRect(
                color = color.copy(alpha = 0.95f),
                topLeft = Offset(1f, topY),
                size = Size(3.dp.toPx().coerceAtMost(blockW), blockH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
            // M18.66-FIX15: Activity-Icon im Block (zoom-abhängig).
            // Gleiche Logik wie Tagesansicht: Icon nur wenn Block-Höhe
            // >= 18dp. Eine 5-min-Activity bei pixelsPerHour=18 (weit
            // raus) = 1.5dp → kein Icon. Bei pixelsPerHour=180 (rein)
            // = 15dp → immer noch unter 18dp — erst ab ~7min Dauer
            // erscheint das Icon. Das verhindert Überlappung bei kurzen
            // Activities, genau wie der User es beschrieben hat.
            if (blockH >= minIconHeightPx &&
                session.activityIcon.isNotBlank() && session.activityIcon != "•"
            ) {
                val iconX = 5.dp.toPx()
                val iconY = topY + (blockH - pillH) / 2f
                // Pill-Hintergrund nur wenn er in die Spaltenbreite passt
                if (pillW <= w - 4.dp.toPx()) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.28f),
                        topLeft = Offset(iconX, iconY),
                        size = Size(pillW, pillH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f, pillH / 2f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = session.activityIcon,
                        topLeft = Offset(iconX + 2.dp.toPx(), iconY + (pillH - iconSize) / 2f),
                        style = TextStyle(fontSize = 12.sp)
                    )
                }
            }
        }

        // Jetzt-Linie (nur für "heute")
        if (isToday && nowMinute in 0..1440) {
            val nowY = (nowMinute / 60f) * pxHour
            drawLine(
                color = Color(0xFFEC4899),
                start = Offset(0f, nowY),
                end = Offset(w, nowY),
                strokeWidth = 1.5f
            )
        }
    }
}

@Composable
private fun EditorHeader(isEditing: Boolean, duration: String, onBack: () -> Unit, onSave: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isEditing) stringResource(R.string.timeline_editor_title_edit)
                    else stringResource(R.string.timeline_editor_title_new),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.timeline_editor_duration, duration),
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel)) }
                Button(onClick = onSave) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@Composable
private fun BasicFields(state: ActivityEditorUiState, onTitle: (String) -> Unit, onDescription: (String) -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            OutlinedTextField(
                value = state.form.title,
                onValueChange = onTitle,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.timeline_editor_title_label)) },
                placeholder = { Text(stringResource(R.string.timeline_editor_title_placeholder)) },
                singleLine = true
            )
            OutlinedTextField(
                value = state.form.description,
                onValueChange = onDescription,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.timeline_editor_note_label)) },
                minLines = 2,
                maxLines = 4
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedActivitySelector(types: List<ActivityType>, selectedId: String?, onSelect: (ActivityType) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    val selected = types.firstOrNull { it.id == selectedId }
    val selectedColor = if (selected?.color != null && selected.color != 0L) Color(selected.color) else MaterialTheme.colorScheme.primary

    // Trigger-Button: fancy gradient card mit Icon
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.lg))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        selectedColor.copy(alpha = 0.15f),
                        selectedColor.copy(alpha = 0.05f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            )
            .clickable { showSheet = true }
            .padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(selectedColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    selected?.icon?.takeIf { it.isNotBlank() } ?: "?",
                    fontSize = 20.sp
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.timeline_editor_activity_label),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    selected?.name ?: stringResource(R.string.timeline_editor_activity_select),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("▸", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showSheet) {
        FancyActivityPickerSheet(
            types = types,
            selectedId = selectedId,
            onSelect = { type -> onSelect(type); showSheet = false },
            onDismiss = { showSheet = false }
        )
    }
}

/**
 * R20-v3: Fancy Activity-Picker mit Live-Suchleiste.
 * Modernes ModalBottomSheet mit:
 * - Großer Suchleiste mit Live-Filter (kein Enter nötig)
 * - Animiert scrollbarer Ergebnisliste
 * - Icon + Farbe pro Eintrag
 * - Selected-State mit Checkmark
 * - Ohne Kategorisierung — flache Liste
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FancyActivityPickerSheet(
    types: List<ActivityType>,
    selectedId: String?,
    onSelect: (ActivityType) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, types) {
        if (query.isBlank()) types
        else types.filter { it.name.contains(query, ignoreCase = true) }
    }
    // Animation für Listeneinträge
    val listVisible = remember { Animatable(0f) }
    LaunchedEffect(Unit) { listVisible.animateTo(1f, animationSpec = tween(300, easing = FastOutSlowInEasing)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.lg)
                .padding(bottom = AevumSpacing.xl)
        ) {
            Text(
                stringResource(R.string.timeline_editor_activity_select),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = AevumSpacing.md)
            )
            // Live-Suchleiste — fancy mit Clear-Button
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.timeline_search_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(AevumRadius.lg),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            )
            Spacer(Modifier.height(AevumSpacing.md))
            // Ergebnisliste
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AevumSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🔍", fontSize = 40.sp)
                    Spacer(Modifier.height(AevumSpacing.sm))
                    Text(
                        stringResource(R.string.timeline_no_results, query),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    if (filtered.size == 1) {
                        stringResource(R.string.timeline_activity_count_singular, filtered.size)
                    } else {
                        stringResource(R.string.timeline_activity_count_plural, filtered.size)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AevumSpacing.sm)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { type ->
                        val isSelected = type.id == selectedId
                        val typeColor = if (type.color != null && type.color != 0L) Color(type.color)
                        else MaterialTheme.colorScheme.primary
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isSelected) typeColor.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                )
                                .clickable { onSelect(type) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(typeColor.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    type.icon?.takeIf { it.isNotBlank() } ?: "•",
                                    fontSize = 18.sp
                                )
                            }
                            Text(
                                type.name,
                                fontSize = 16.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(typeColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualTimeEditorCard(
    state: ActivityEditorUiState,
    onStartMinute: (Int) -> Unit,
    onEndMinute: (Int) -> Unit,
    onStartHour: (Int) -> Unit,
    onStartQuarter: (Int) -> Unit,
    onEndHour: (Int) -> Unit,
    onEndQuarter: (Int) -> Unit,
    onSnapStart: (TriggerEventMarker) -> Unit,
    onSnapEnd: (TriggerEventMarker) -> Unit,
    openEnded: Boolean,
    onOpenEndedChange: (Boolean) -> Unit,
    durationOnly: Boolean = false,
    durationOnlyMinutes: Int? = null,
    onDurationOnlyModeChange: (Boolean) -> Unit = {},
    onDurationOnlyMinutesChange: (Int) -> Unit = {}
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(state.form.startAt).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(state.form.endAt ?: state.form.startAt).atZone(zone).toLocalTime()
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.timeline_editor_time_window), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.timeline_editor_time_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    when {
                        durationOnly -> stringResource(R.string.timeline_duration_minutes, durationOnlyMinutes ?: 60)
                        openEnded -> stringResource(R.string.timeline_editor_open_ended)
                        else -> state.duration
                    },
                    color = when {
                        durationOnly -> MaterialTheme.colorScheme.tertiary
                        openEnded -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp
                )
            }
            // R20-v2: Drei-Wege-Schalter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AevumRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                SegmentButton(
                    label = stringResource(R.string.timeline_editor_segment_fixed),
                    selected = !openEnded && !durationOnly,
                    modifier = Modifier.weight(1f)
                ) { onOpenEndedChange(false); onDurationOnlyModeChange(false) }
                SegmentButton(
                    label = stringResource(R.string.timeline_editor_segment_open),
                    selected = openEnded && !durationOnly,
                    modifier = Modifier.weight(1f)
                ) { onOpenEndedChange(true); onDurationOnlyModeChange(false) }
                SegmentButton(
                    label = stringResource(R.string.timeline_editor_segment_duration),
                    selected = durationOnly,
                    modifier = Modifier.weight(1f)
                ) { onDurationOnlyModeChange(true); onOpenEndedChange(false) }
            }
            if (durationOnly) {
                // R20-v2: "Nur Dauer"-Eingabe
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text(
                        stringResource(R.string.timeline_duration_only_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val mins = durationOnlyMinutes ?: 60
                    val hours = mins / 60
                    val minutes = mins % 60
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.common_hours), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onDurationOnlyMinutesChange(((hours - 1).coerceAtLeast(0)) * 60 + minutes) }) { Text("−") }
                                Text("$hours", fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                OutlinedButton(onClick = { onDurationOnlyMinutesChange(((hours + 1).coerceAtMost(23)) * 60 + minutes) }) { Text("+") }
                            }
                        }
                        Text(":", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.common_minutes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val step = 5
                                OutlinedButton(onClick = { onDurationOnlyMinutesChange(hours * 60 + ((minutes - step).coerceAtLeast(0) / step * step)) }) { Text("−") }
                                Text("${minutes.toString().padStart(2, '0')}", fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                OutlinedButton(onClick = { onDurationOnlyMinutesChange(hours * 60 + ((minutes + step).coerceAtMost(59) / step * step)) }) { Text("+") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60, 90, 120).forEach { quick ->
                            AssistChip(
                                onClick = { onDurationOnlyMinutesChange(quick) },
                                label = { Text(if (quick >= 60) "${quick / 60}h${if (quick % 60 > 0) " ${quick % 60}m" else ""}" else "${quick}m") }
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        AevumTimePicker(
                            initialHour = start.hour,
                            initialMinute = start.minute,
                            accent = Color(0xFFF5A623),
                            onTimeChange = { h, m ->
                                onStartHour(h)
                                onStartQuarter(m)
                            },
                            label = stringResource(R.string.timeline_time_start_label),
                            showDigitalDisplay = true
                        )
                    }
                    if (!openEnded) {
                        Box(modifier = Modifier.weight(1f)) {
                            AevumTimePicker(
                                initialHour = end.hour,
                                initialMinute = end.minute,
                                accent = MaterialTheme.colorScheme.primary,
                                onTimeChange = { h, m ->
                                    onEndHour(h)
                                    onEndQuarter(m)
                                },
                                label = stringResource(R.string.timeline_time_end_label),
                                showDigitalDisplay = true
                            )
                        }
                    }
                }
                if (openEnded) {
                    Text(
                        stringResource(
                            R.string.timeline_editor_open_ended_hint,
                            TimeFormatting.formatTime(state.form.startAt)
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.triggerMarkers.isNotEmpty() && !durationOnly) {
                TriggerSnapRow(
                    markers = state.triggerMarkers,
                    startAtMs = state.form.startAt,
                    endAtMs = state.form.endAt,
                    onSnapStart = onSnapStart,
                    onSnapEnd = onSnapEnd
                )
            }
        }
    }
}

@Composable
private fun TriggerSnapRow(
    markers: List<TriggerEventMarker>,
    startAtMs: Long,
    endAtMs: Long?,
    onSnapStart: (TriggerEventMarker) -> Unit,
    onSnapEnd: (TriggerEventMarker) -> Unit
) {
    // M18.93 (User: "Kürzungen am Anfang und Ende differenziert statt alles
    // in eine Liste; nur passende Trigger, die wirklich nahe an Start/Ende
    // liegen; Beispiel: Schlaf bis 08:10 von Garmin, Handy-Aufzeichnung
    // 08:05 → der 08:05-Trigger soll als End-Kürzung klickbar sein"):
    //
    //  - ZWEI gruppierte Zeilen: "Am Anfang kürzen" / "Am Ende kürzen".
    //  - Relevanz-Filter: nur Trigger im ±120-Min-Fenster um den jeweils
    //    zugehörigen Zeitpunkt (Start bzw. Ende).
    //  - Pro Seite die 3 BESTEN (kleinste Differenz), beste Treffer
    //    hervorgehoben; Delta-Label zeigt die Abweichung in Minuten.
    //  - Leere Seiten werden komplett ausgeblendet (kein Leerraum).
    // M18.93: Relevanz-Filter — Marker ±120min um Start/Ende, Top-3 je Seite.
    val endMs = endAtMs ?: startAtMs
    val windowMs = 120L * 60_000

    fun candidates(targetMs: Long): List<Pair<TriggerEventMarker, Long>> =
        markers
            .map { it to (it.occurredAt - targetMs) }
            .filter { kotlin.math.abs(it.second) <= windowMs }
            .sortedBy { kotlin.math.abs(it.second) }
            .take(3)

    val startCandidates = remember(markers, startAtMs) { candidates(startAtMs) }
    val endCandidates = remember(markers, endMs) { candidates(endMs) }

    if (startCandidates.isEmpty() && endCandidates.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
        Text(stringResource(R.string.timeline_editor_trigger_row_m93), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (startCandidates.isNotEmpty()) {
            TriggerSnapGroup(
                title = stringResource(R.string.timeline_editor_snap_start_section),
                candidates = startCandidates,
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = { onSnapStart(it) }
            )
        }
        if (endCandidates.isNotEmpty()) {
            TriggerSnapGroup(
                title = stringResource(R.string.timeline_editor_snap_end_section),
                candidates = endCandidates,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onSnapEnd(it) }
            )
        }
    }
}

/** Eine Gruppe (Anfang/Ende) relevanter Trigger-Chips; der beste Treffer
 *  (kleinste Differenz) bekommt einen Akzent-Rand. */
@Composable
private fun TriggerSnapGroup(
    title: String,
    candidates: List<Pair<TriggerEventMarker, Long>>,
    accent: androidx.compose.ui.graphics.Color,
    onClick: (TriggerEventMarker) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xxs)) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            candidates.forEachIndexed { index, (marker, deltaMs) ->
                val deltaMin = kotlin.math.round(deltaMs / 60_000.0).toInt()
                val isBest = index == 0
                androidx.compose.material3.Surface(
                    onClick = { onClick(marker) },
                    shape = RoundedCornerShape(AevumRadius.md),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (isBest) androidx.compose.foundation.BorderStroke(1.5.dp, accent)
                    else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = AevumSpacing.sm, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text(
                            TimeFormatting.formatTime(marker.occurredAt) + "  ·  " +
                                stringResource(
                                    if (deltaMs >= 0) R.string.timeline_editor_snap_delta_after
                                    else R.string.timeline_editor_snap_delta_before,
                                    kotlin.math.abs(deltaMin)
                                ),
                            fontSize = 12.sp,
                            fontWeight = if (isBest) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isBest) accent else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            marker.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidationCard(validation: SessionValidationResult, errorMessage: String?) {
    val message = errorMessage ?: when (validation) {
        SessionValidationResult.Valid -> stringResource(R.string.timeline_validation_valid)
        is SessionValidationResult.Invalid -> validation.message
        is SessionValidationResult.Warning -> validation.message
    }
    AevumCard(variant = CardVariant.Filled) {
        Text(
            message,
            fontSize = 13.sp,
            color = if (validation is SessionValidationResult.Invalid || errorMessage != null)
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
