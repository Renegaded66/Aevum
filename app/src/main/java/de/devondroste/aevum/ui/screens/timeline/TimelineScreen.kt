package de.devondroste.aevum.ui.screens.timeline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Tag
import de.devondroste.aevum.domain.activity.SessionValidationResult
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.domain.trigger.TriggerEventMarker
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.CategoryChip
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.components.categoryColor
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    onCreateActivity: (Long) -> Unit,
    onEditActivity: (String) -> Unit,
    onEditCandidate: (String) -> Unit,
    onOpenActivity: (String) -> Unit,
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onCreateActivity(TimeFormatting.startOfDayMillis(state.selectedDate)) },
                text = { Text("Aktivität") },
                icon = { Text("+") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { TimelineHeader(state, viewModel::previousDay, viewModel::nextDay, viewModel::today) }
            item { SummaryCard(state) }
            if (state.candidates.isNotEmpty()) {
                item { CandidateReviewCard(state.candidates, viewModel::acceptCandidate, onEditCandidate, viewModel::dismissCandidate) }
            }
            if (state.sessions.isEmpty() && state.triggerEvents.isEmpty()) {
                item {
                    EmptyState(
                        title = "Noch keine Aktivitäten",
                        message = "Erfasse deinen Tag manuell oder aktiviere Geofencing. Trigger erscheinen künftig direkt auf dem Tageskalender.",
                        actionLabel = "Erste Aktivität anlegen",
                        onActionClick = { onCreateActivity(TimeFormatting.startOfDayMillis(state.selectedDate)) }
                    )
                }
            } else {
                item { DayCalendarTimeline(state.sessions, state.triggerEvents, onOpenActivity, onEditActivity) }
                item { Spacer(Modifier.height(72.dp)) }
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
            item { TagPickerCard(state.tags, state.form.selectedTagIds, viewModel::toggleTag) }
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
                    onSnapEnd = viewModel::snapEndTo
                )
            }
            item { ValidationCard(state.validation, state.form.errorMessage) }
            item { Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text(if (state.isEditing) "Änderungen speichern" else "Aktivität speichern") } }
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
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { TextButton(onClick = onBack) { Text("Zurück") } }
            val session = state.session
            if (session == null) {
                item { EmptyState(title = "Aktivität nicht gefunden", message = "Der Eintrag existiert nicht mehr.", actionLabel = "Zurück", onActionClick = onBack) }
            } else {
                item {
                    AevumCard(variant = CardVariant.Gradient) {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                            Text(session.title, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                CategoryChip(categoryId = state.category?.name ?: "Sonstiges")
                                state.activityType?.let { SimpleChip(it.name) }
                            }
                            Text(state.range, fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
                            Text("Dauer: ${state.duration}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            session.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                item { TagsCard(state.tags) }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Button(onClick = { onEdit(session.id) }, modifier = Modifier.weight(1f)) { Text("Bearbeiten") }
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Löschen") }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Aktivität löschen?") },
            text = { Text("Der Eintrag verschwindet aus Timeline und Dashboard.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun TimelineHeader(state: TimelineUiState, onPreviousDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousDay) { Text("‹", fontSize = 30.sp) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.dayTitle, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.formattedDate, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onNextDay) { Text("›", fontSize = 30.sp) }
            }
            OutlinedButton(onClick = onToday, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Heute") }
        }
    }
}

@Composable
private fun SummaryCard(state: TimelineUiState) {
    AevumCard { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { SummaryValue("Erfasst", state.totalTracked); SummaryValue("Einträge", state.sessionCount.toString()); SummaryValue("Konflikte", if (state.hasOverlaps) "Prüfen" else "Keine") } }
}

@Composable
private fun SummaryValue(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun CandidateReviewCard(candidates: List<CandidateReviewUi>, onAccept: (String) -> Unit, onEdit: (String) -> Unit, onDismiss: (String) -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("Wir haben Aktivität erkannt", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            candidates.forEach { candidate ->
                AevumCard(variant = CardVariant.Filled) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(candidate.title, fontWeight = FontWeight.SemiBold)
                            Text("${candidate.confidence}%", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                        }
                        Text("${candidate.timeRange} · ${candidate.duration}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(candidate.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Button(onClick = { onAccept(candidate.id) }) { Text("Übernehmen") }
                            OutlinedButton(onClick = { onEdit(candidate.id) }) { Text("Bearbeiten") }
                            OutlinedButton(onClick = { onDismiss(candidate.id) }) { Text("Verwerfen") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCalendarTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit
) {
    var isListMode by remember { mutableStateOf(false) }
    var pixelsPerHour by remember { mutableStateOf(TimelineUiState.DEFAULT_PIXELS_PER_HOUR) }
    val lanes = remember(sessions) { assignTimelineLanes(sessions) }
    val maxLane = (lanes.values.maxOrNull() ?: 0).coerceAtLeast(0)
    val laneCount = maxLane + 1

    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            // Header: title + view mode toggle + zoom controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Tageskalender", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isListMode) "Eine Zeile pro Ereignis" else "00:00–24:00 · Pinch zum Zoomen",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                    ModeToggleButton("Liste", isListMode) { isListMode = true }
                    ModeToggleButton("Tag", !isListMode) { isListMode = false }
                }
            }
            // Zoom slider (only in day mode)
            if (!isListMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Text("−", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = pixelsPerHour,
                        onValueChange = { pixelsPerHour = it },
                        valueRange = TimelineUiState.MIN_PIXELS_PER_HOUR..TimelineUiState.MAX_PIXELS_PER_HOUR,
                        modifier = Modifier.weight(1f)
                    )
                    Text("+", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isListMode) {
                EventListTimeline(
                    sessions = sessions,
                    triggers = triggers,
                    onOpen = onOpen,
                    onEdit = onEdit
                )
            } else {
                ZoomableDayTimeline(
                    sessions = sessions,
                    triggers = triggers,
                    lanes = lanes,
                    pixelsPerHour = pixelsPerHour,
                    onPixelsPerHourChange = { pixelsPerHour = it },
                    onOpen = onOpen,
                    onEdit = onEdit
                )
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
 */
@Composable
private fun EventListTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit
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
            "Keine Ereignisse. Erfasse deine erste Aktivität.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = AevumSpacing.md)
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        merged.forEach { entry ->
            when (entry) {
                is TimelineEntry.Session -> {
                    EventListRow(
                        time = entry.session.time,
                        title = entry.session.title,
                        detail = "${entry.session.range} · ${entry.session.duration}",
                        accent = categoryColor(entry.session.categoryName),
                        kind = if (entry.session.isAuto) "Auto" else "Erfasst",
                        onClick = { onOpen(entry.session.id) },
                        onEdit = { onEdit(entry.session.id) }
                    )
                }
                is TimelineEntry.Trigger -> {
                    EventListRow(
                        time = entry.trigger.time,
                        title = "◆ ${entry.trigger.label}",
                        detail = "${entry.trigger.confidence}% Konfidenz",
                        accent = MaterialTheme.colorScheme.secondary,
                        kind = "Trigger",
                        onClick = {},
                        onEdit = {}
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

private sealed class TimelineEntry {
    data class Session(val session: TimelineSessionUi) : TimelineEntry()
    data class Trigger(val trigger: TriggerEventUi) : TimelineEntry()
}

@Composable
private fun EventListRow(
    time: String,
    title: String,
    detail: String,
    accent: Color,
    kind: String,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(accent)
        )
        Text(
            time,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                .background(accent.copy(alpha = 0.12f))
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
    onEdit: (String) -> Unit
) {
    val totalHeight = (24 * pixelsPerHour).dp
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val blockX = 56.dp
    val axisX = 48.dp
    val blockRightPadding = 12.dp
    val laneCount = (lanes.values.maxOrNull() ?: 0).coerceAtLeast(0) + 1

    // Track now-time for the marker line — only computed at composition
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val nowMinute = TimeFormatting.minutesOfDay(now, zone).coerceIn(0, 1440)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(560.dp)
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
                Canvas(modifier = Modifier.fillMaxWidth().height(totalHeight)) {
                    val pxHour = pixelsPerHour.dp.toPx()
                    val axisXLocal = axisX.toPx()
                    val blockXLocal = blockX.toPx()
                    val blockRightPaddingLocal = blockRightPadding.toPx()
                    val blockWidth = size.width - blockXLocal - blockRightPaddingLocal

                    // Hour grid + axis
                    drawLine(
                        color = Color.White.copy(alpha = 0.18f),
                        start = Offset(axisXLocal, 0f),
                        end = Offset(axisXLocal, size.height),
                        strokeWidth = 2f
                    )
                    for (hour in 0..24) {
                        val y = hour * pxHour
                        drawLine(
                            color = Color.White.copy(alpha = if (hour % 6 == 0) 0.18f else 0.06f),
                            start = Offset(axisXLocal, y),
                            end = Offset(size.width, y),
                            strokeWidth = if (hour % 6 == 0) 1.5f else 0.5f
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
                        val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
                        val endMin = session.endMinuteOfDay.coerceIn(startMin + 5, 1440)
                        val lane = lanes[session.id] ?: 0
                        val topY = (startMin / 60f) * pxHour
                        val bottomY = (endMin / 60f) * pxHour
                        val totalH = (bottomY - topY).coerceAtLeast(20f)
                        val laneH = if (totalH > 8f) (totalH - 4f) / (laneCount.coerceAtLeast(1)).toFloat() else totalH
                        val laneY = topY + 2f + lane * laneH
                        val laneHeight = (laneH - 2f).coerceAtLeast(2f)
                        val color = categoryColor(session.categoryName)
                        drawRoundRect(
                            color = color.copy(alpha = if (session.isOverlapping) 0.65f else 0.42f),
                            topLeft = Offset(blockXLocal, laneY),
                            size = Size(blockWidth.coerceAtLeast(0f), laneHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                        )
                        drawLine(
                            color = color.copy(alpha = 0.78f),
                            start = Offset(blockXLocal, laneY),
                            end = Offset(blockXLocal + blockWidth, laneY),
                            strokeWidth = 1.5f
                        )
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
                sessions.forEach { session ->
                    val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
                    val endMin = session.endMinuteOfDay.coerceIn(startMin + 5, 1440)
                    val lane = lanes[session.id] ?: 0
                    val topY = (startMin / 60f) * pixelsPerHour
                    val totalH = (endMin / 60f - startMin / 60f) * pixelsPerHour
                    val laneH = if (totalH > 8f) (totalH - 4f) / (laneCount.coerceAtLeast(1)).toFloat() else totalH
                    val laneY = topY + 2f + lane * laneH
                    Box(
                        modifier = Modifier
                            .padding(start = blockX + 6.dp, top = laneY.dp)
                            .pointerInput(session.id) {
                                detectTapGestures(
                                    onTap = { onOpen(session.id) },
                                    onLongPress = { onEdit(session.id) }
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

            // Pinch-Indikator + Zoom-Slider ist bereits in DayCalendarTimeline
            // — hier nur der aktuelle Wert als Hinweis.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.sm, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${"%.0f".format(pixelsPerHour)} px/h",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EditorHeader(isEditing: Boolean, duration: String, onBack: () -> Unit, onSave: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(if (isEditing) "Aktivität bearbeiten" else "Neue Aktivität", fontSize = 28.sp, fontWeight = FontWeight.SemiBold); Text("Dauer: $duration", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace) }; Column(horizontalAlignment = Alignment.End) { TextButton(onClick = onBack) { Text("Abbrechen") }; Button(onClick = onSave) { Text("Speichern") } } } }
}

@Composable
private fun BasicFields(state: ActivityEditorUiState, onTitle: (String) -> Unit, onDescription: (String) -> Unit) {
    AevumCard(variant = CardVariant.Gradient) { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) { OutlinedTextField(value = state.form.title, onValueChange = onTitle, modifier = Modifier.fillMaxWidth(), label = { Text("Was hast du gemacht?") }, placeholder = { Text("z. B. Deep Work, Motorradfahrt, Sport") }, singleLine = true); OutlinedTextField(value = state.form.description, onValueChange = onDescription, modifier = Modifier.fillMaxWidth(), label = { Text("Notiz optional") }, minLines = 2, maxLines = 4) } }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedActivitySelector(types: List<ActivityType>, selectedId: String?, onSelect: (ActivityType) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    val selected = types.firstOrNull { it.id == selectedId }
    AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Aktivität", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("Eine Auswahl reicht — Kategorie wird intern automatisch gesetzt.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onClick = { showSheet = true }, modifier = Modifier.fillMaxWidth()) { Text(selected?.name ?: "Aktivität auswählen") } } }
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.padding(AevumSpacing.md), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                Text("Aktivität auswählen", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = "", onValueChange = {}, modifier = Modifier.fillMaxWidth(), enabled = false, label = { Text("Suche vorbereitet") }, placeholder = { Text("M6+: Aktivität suchen") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { types.forEach { type -> FilterChip(selected = type.id == selectedId, onClick = { onSelect(type); showSheet = false }, label = { Text(type.name) }) } }
                Spacer(Modifier.height(AevumSpacing.lg))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TagPickerCard(tags: List<Tag>, selectedIds: List<String>, onToggle: (String) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("Tags", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(if (selectedIds.isEmpty()) "Optionaler Kontext" else "${selectedIds.size} gewählt", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; OutlinedButton(onClick = { showSheet = true }) { Text("Auswählen") } }; Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { tags.filter { it.id in selectedIds }.forEach { SimpleChip(it.name) } } } }
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.padding(AevumSpacing.md), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                Text("Tags hinzufügen", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), label = { Text("Suche vorbereitet") }, placeholder = { Text("M6+: Tags suchen/filtern") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { tags.forEach { tag -> FilterChip(selected = tag.id in selectedIds, onClick = { onToggle(tag.id) }, label = { Text(tag.name) }) } }
                Button(onClick = { showSheet = false }, modifier = Modifier.fillMaxWidth()) { Text("Fertig") }
                Spacer(Modifier.height(AevumSpacing.lg))
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
    onSnapEnd: (TriggerEventMarker) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(state.form.startAt).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(state.form.endAt ?: state.form.startAt).atZone(zone).toLocalTime()
    val startMinute = TimeFormatting.minutesOfDay(state.form.startAt, zone)
    val endMinute = state.form.endAt?.let { TimeFormatting.minutesOfDay(it, zone) } ?: startMinute
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Zeitfenster", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Groß ziehen · Marker einrasten", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(state.duration, color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            }
            TimeRail(startMinute, endMinute, state.triggerMarkers, onStartMinute, onEndMinute)
            TriggerSnapRow(state.triggerMarkers, onSnapStart, onSnapEnd)
            TimeRow("Start", start.hour, start.minute, onStartHour, onStartQuarter)
            TimeRow("Ende", end.hour, end.minute, onEndHour, onEndQuarter)
        }
    }
}

/**
 * Verbesserter Time-Rail:
 * - Große Drag-Handles (16dp Radius, 56dp Trefferfläche)
 * - 1-Minuten Snap (statt 15er)
 * - Klare Vorschau (aktuelle Start/Ende-Zeit)
 * - Stunden-Marker mit besserer Lesbarkeit
 */
@Composable
private fun TimeRail(
    startMinute: Int,
    endMinute: Int,
    markers: List<TriggerEventMarker>,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit
) {
    val railHeight = 380.dp
    val handleRadius = 16.dp
    val handleHitRadius = 28.dp
    var draggingHandle by remember { mutableStateOf<String?>(null) }
    var dragStartY by remember { mutableStateOf(0f) }
    var dragStartValue by remember { mutableStateOf(0) }
    val accent = MaterialTheme.colorScheme.primary
    val accentSurface = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    val markerColor = MaterialTheme.colorScheme.secondary
    val handleFill = MaterialTheme.colorScheme.surface
    val handleBorder = accent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(railHeight)
            .pointerInput(startMinute, endMinute) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val hourAxisX = 54.dp.toPx()
                        val blockX = 96.dp.toPx()
                        val totalHeight = size.height
                        val startY = totalHeight * startMinute / 1440f
                        val endY = totalHeight * endMinute / 1440f
                        val distStart = kotlin.math.abs(offset.y - startY)
                        val distEnd = kotlin.math.abs(offset.y - endY)
                        val hit = handleHitRadius.toPx()
                        if (distStart <= hit && distStart <= distEnd) {
                            draggingHandle = "start"
                            dragStartY = offset.y
                            dragStartValue = startMinute
                        } else if (distEnd <= hit) {
                            draggingHandle = "end"
                            dragStartY = offset.y
                            dragStartValue = endMinute
                        } else {
                            // Fallback: nearest handle
                            draggingHandle = if (distStart < distEnd) "start" else "end"
                            dragStartY = offset.y
                            dragStartValue = if (draggingHandle == "start") startMinute else endMinute
                        }
                    },
                    onDrag = { change, _ ->
                        val dragging = draggingHandle ?: return@detectDragGestures
                        val totalHeight = size.height
                        val deltaMinute = ((change.position.y - dragStartY) / totalHeight * 1440f).toInt()
                        val raw = dragStartValue + deltaMinute
                        val snapped = raw.coerceIn(0, 1440) // 1-minute snap
                        if (dragging == "start") {
                            onStart(snapped.coerceAtMost(endMinute - 5))
                        } else {
                            onEnd(snapped.coerceAtLeast(startMinute + 5))
                        }
                    },
                    onDragEnd = { draggingHandle = null },
                    onDragCancel = { draggingHandle = null }
                )
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val axisX = 54.dp.toPx()
            val blockX = 96.dp.toPx()
            val blockWidth = size.width - blockX - 12.dp.toPx()

            // Vertical hour axis
            drawLine(
                color = trackColor,
                start = Offset(axisX, 0f),
                end = Offset(axisX, size.height),
                strokeWidth = 2.dp.toPx()
            )

            // Hour grid lines
            for (hour in 0..24 step 2) {
                val y = size.height * hour / 24f
                drawLine(
                    color = trackColor.copy(alpha = if (hour % 6 == 0) 0.62f else 0.30f),
                    start = Offset(axisX, y),
                    end = Offset(size.width, y),
                    strokeWidth = if (hour % 6 == 0) 1.5.dp.toPx() else 0.5.dp.toPx()
                )
            }

            // Trigger markers
            markers.forEach { marker ->
                val y = size.height * TimeFormatting.minutesOfDay(marker.occurredAt) / 1440f
                drawCircle(
                    color = markerColor,
                    radius = 5.dp.toPx(),
                    center = Offset(axisX, y)
                )
                drawLine(
                    color = markerColor.copy(alpha = 0.32f),
                    start = Offset(axisX, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Active session block
            val top = size.height * startMinute.coerceIn(0, 1440) / 1440f
            val bottom = size.height * endMinute.coerceIn(startMinute + 5, 1440) / 1440f
            drawRoundRect(
                color = accentSurface,
                topLeft = Offset(blockX, top),
                size = Size(blockWidth, bottom - top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx())
            )
            // Top inner border
            drawLine(
                color = accent.copy(alpha = 0.62f),
                start = Offset(blockX, top),
                end = Offset(blockX + blockWidth, top),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = accent.copy(alpha = 0.62f),
                start = Offset(blockX, bottom),
                end = Offset(blockX + blockWidth, bottom),
                strokeWidth = 2.dp.toPx()
            )

            // Drag handles — significantly larger
            // Top handle
            drawCircle(
                color = handleFill,
                radius = handleRadius.toPx(),
                center = Offset(blockX, top)
            )
            drawCircle(
                color = handleBorder,
                radius = handleRadius.toPx(),
                center = Offset(blockX, top),
                style = Stroke(width = 3.dp.toPx())
            )
            // Bottom handle
            drawCircle(
                color = handleFill,
                radius = handleRadius.toPx(),
                center = Offset(blockX, bottom)
            )
            drawCircle(
                color = handleBorder,
                radius = handleRadius.toPx(),
                center = Offset(blockX, bottom),
                style = Stroke(width = 3.dp.toPx())
            )
            // Three-line grip inside handles
            for (handleY in listOf(top, bottom)) {
                val cx = blockX
                listOf(-4f, 0f, 4f).forEach { offset ->
                    drawCircle(
                        color = handleBorder,
                        radius = 1.5.dp.toPx(),
                        center = Offset(cx + offset.dp.toPx(), handleY)
                    )
                }
            }
        }

        // Hour labels
        (0..24 step 4).forEach { hour ->
            Text(
                "%02d:00".format(hour),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = (380f * hour / 24f).dp)
            )
        }

        // Floating preview bubble while dragging
        draggingHandle?.let { handle ->
            val minute = if (handle == "start") startMinute else endMinute
            val yPos = (380f * minute / 1440f).dp
            val timeStr = "%02d:%02d".format(minute / 60, minute % 60)
            Box(
                modifier = Modifier
                    .padding(start = 132.dp, top = (yPos.value - 16).dp.coerceAtLeast(0.dp))
                    .background(
                        color = accent,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    timeStr,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TriggerSnapRow(markers: List<TriggerEventMarker>, onSnapStart: (TriggerEventMarker) -> Unit, onSnapEnd: (TriggerEventMarker) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) { Text("Trigger Marker (Architektur vorbereitet)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { markers.forEach { marker -> AssistChip(onClick = { onSnapStart(marker) }, label = { Text("Start: ${TimeFormatting.formatTime(marker.occurredAt)} ${marker.label}") }); AssistChip(onClick = { onSnapEnd(marker) }, label = { Text("Ende: ${TimeFormatting.formatTime(marker.occurredAt)}") }) } } }
}

@Composable
private fun TimeRow(label: String, hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) { Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontWeight = FontWeight.Medium); Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs), verticalAlignment = Alignment.CenterVertically) { TimeBump("−h") { onHour((hour + 23) % 24) }; ElevatedAssistChip(onClick = {}, label = { Text("%02d:%02d".format(hour, minute), fontFamily = FontFamily.Monospace) }); TimeBump("+h") { onHour((hour + 1) % 24) }; TimeBump("−15") { onMinute((minute + 45) % 60) }; TimeBump("+15") { onMinute((minute + 15) % 60) } } } }

@Composable
private fun TimeBump(label: String, onClick: () -> Unit) { AssistChip(onClick = onClick, label = { Text(label, fontSize = 12.sp) }) }

@Composable
private fun ValidationCard(validation: SessionValidationResult, errorMessage: String?) { val message = errorMessage ?: when (validation) { SessionValidationResult.Valid -> "Zeitfenster plausibel. Du kannst speichern."; is SessionValidationResult.Invalid -> validation.message; is SessionValidationResult.Warning -> validation.message }; AevumCard(variant = CardVariant.Filled) { Text(message, fontSize = 13.sp, color = if (validation is SessionValidationResult.Invalid || errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun TagsCard(tags: List<Tag>) { AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Tags", fontWeight = FontWeight.SemiBold); if (tags.isEmpty()) Text("Keine Tags", color = MaterialTheme.colorScheme.onSurfaceVariant) else Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), modifier = Modifier.horizontalScroll(rememberScrollState())) { tags.forEach { SimpleChip(it.name) } } } } }

@Composable
private fun SimpleChip(label: String) { AssistChip(onClick = {}, label = { Text(label) }) }
