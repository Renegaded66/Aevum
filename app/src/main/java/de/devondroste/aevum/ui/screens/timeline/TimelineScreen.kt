package de.devondroste.aevum.ui.screens.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
            if (state.sessions.isEmpty()) {
                item {
                    EmptyState(
                        title = "Noch keine Aktivitäten",
                        message = "Erfasse deinen Tag manuell. Titel, Aktivität und Zeitfenster reichen für den ersten Eintrag.",
                        actionLabel = "Erste Aktivität anlegen",
                        onActionClick = { onCreateActivity(TimeFormatting.startOfDayMillis(state.selectedDate)) }
                    )
                }
            } else {
                item { DayCalendarTimeline(state.sessions, onOpenActivity, onEditActivity) }
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
private fun DayCalendarTimeline(sessions: List<TimelineSessionUi>, onOpen: (String) -> Unit, onEdit: (String) -> Unit) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Tageskalender", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("00:00–24:00 · visuelle Zeitblöcke", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.fillMaxWidth().height(760.dp)) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val axisX = 46.dp.toPx()
                    drawLine(Color.White.copy(alpha = .18f), Offset(axisX, 0f), Offset(axisX, size.height), strokeWidth = 2.dp.toPx())
                    (0..24 step 3).forEach { hour ->
                        val y = size.height * (hour / 24f)
                        drawLine(Color.White.copy(alpha = .08f), Offset(axisX, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                }
                (0..24 step 3).forEach { hour ->
                    Text("%02d:00".format(hour), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = (760f * hour / 24f).dp))
                }
                sessions.forEach { session ->
                    val start = session.startMinuteOfDay.coerceIn(0, 1440)
                    val end = session.endMinuteOfDay.coerceIn(start + 15, 1440)
                    val top = 760f * start / 1440f
                    val height = (760f * (end - start) / 1440f).coerceAtLeast(44f)
                    CalendarBlock(
                        session = session,
                        modifier = Modifier.padding(start = 66.dp, top = top.dp).height(height.dp).fillMaxWidth(),
                        onClick = { onOpen(session.id) },
                        onEdit = { onEdit(session.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarBlock(session: TimelineSessionUi, modifier: Modifier, onClick: () -> Unit, onEdit: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(AevumRadius.md), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (session.isOverlapping) .62f else .42f), border = androidx.compose.foundation.BorderStroke(1.dp, if (session.isOverlapping) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = .35f))) {
        Row(modifier = Modifier.fillMaxSize().padding(AevumSpacing.sm), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { Text(session.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${session.range} · ${session.duration}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            TextButton(onClick = onEdit) { Text("Edit") }
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
    AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Zeitfenster", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("Visuell ziehen · Marker einrasten", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(state.duration, color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace) }; TimeRail(startMinute, endMinute, state.triggerMarkers, onStartMinute, onEndMinute); TriggerSnapRow(state.triggerMarkers, onSnapStart, onSnapEnd); TimeRow("Start", start.hour, start.minute, onStartHour, onStartQuarter); TimeRow("Ende", end.hour, end.minute, onEndHour, onEndQuarter) } }
}

@Composable
private fun TimeRail(startMinute: Int, endMinute: Int, markers: List<TriggerEventMarker>, onStart: (Int) -> Unit, onEnd: (Int) -> Unit) {
    val railHeight = 360.dp
    Box(modifier = Modifier.fillMaxWidth().height(railHeight).pointerInput(startMinute, endMinute) {
        detectDragGestures { change, _ ->
            val minute = ((change.position.y / size.height) * 1440).roundToInt().coerceIn(0, 1440)
            if (kotlin.math.abs(minute - startMinute) < kotlin.math.abs(minute - endMinute)) onStart((minute / 15) * 15) else onEnd((minute / 15) * 15)
        }
    }) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val axisX = 54.dp.toPx(); val blockX = 96.dp.toPx(); val blockWidth = size.width - blockX - 12.dp.toPx()
            drawLine(Color.White.copy(alpha = .18f), Offset(axisX, 0f), Offset(axisX, size.height), strokeWidth = 2.dp.toPx())
            (0..24 step 4).forEach { hour -> val y = size.height * hour / 24f; drawLine(Color.White.copy(alpha = .08f), Offset(axisX, y), Offset(size.width, y), strokeWidth = 1.dp.toPx()) }
            markers.forEach { marker -> val y = size.height * TimeFormatting.minutesOfDay(marker.occurredAt) / 1440f; drawCircle(Color(0xFF2DD4BF), radius = 5.dp.toPx(), center = Offset(axisX, y)); drawLine(Color(0xFF2DD4BF).copy(alpha = .35f), Offset(axisX, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round) }
            val top = size.height * startMinute.coerceIn(0, 1440) / 1440f; val bottom = size.height * endMinute.coerceIn(startMinute + 15, 1440) / 1440f
            drawRoundRect(color = Color(0xFF6366F1).copy(alpha = .42f), topLeft = Offset(blockX, top), size = Size(blockWidth, bottom - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx()))
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(blockX, top)); drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(blockX, bottom))
        }
        (0..24 step 4).forEach { hour -> Text("%02d:00".format(hour), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = (360f * hour / 24f).dp)) }
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
