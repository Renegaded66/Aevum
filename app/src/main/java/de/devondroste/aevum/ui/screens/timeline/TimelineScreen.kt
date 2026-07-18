package de.devondroste.aevum.ui.screens.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.model.Tag
import de.devondroste.aevum.domain.activity.SessionValidationResult
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.CategoryChip
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.components.SectionHeader
import de.devondroste.aevum.ui.components.TimelineItem
import de.devondroste.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onCreateActivity(TimeFormatting.startOfDayMillis(state.selectedDate)) },
                text = { Text("Aktivität") },
                icon = { Text("+") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { TimelineHeader(state, viewModel::previousDay, viewModel::nextDay, viewModel::today) }
            item { WeekStrip(state.selectedDate) }
            item { SummaryCard(state) }
            if (state.sessions.isEmpty()) {
                item {
                    EmptyState(
                        title = "Noch keine Aktivitäten",
                        message = "Erfasse deinen Tag manuell. Titel, Typ und Zeitfenster reichen für den ersten Eintrag.",
                        actionLabel = "Erste Aktivität anlegen",
                        onActionClick = { onCreateActivity(TimeFormatting.startOfDayMillis(state.selectedDate)) }
                    )
                }
            } else {
                item { SectionHeader("Tagesansicht", "Heute", viewModel::today) }
                items(state.sessions, key = { it.id }) { session ->
                    TimelineItem(
                        time = session.time,
                        title = session.title,
                        category = session.categoryName,
                        duration = session.duration,
                        source = session.source,
                        isCurrent = session.isRunning,
                        isConflict = session.isOverlapping,
                        onClick = { onOpenActivity(session.id) },
                        onEdit = { onEditActivity(session.id) }
                    )
                }
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { EditorHeader(state.isEditing, state.duration, onBack, viewModel::save) }
            item { BasicFields(state, viewModel::setTitle, viewModel::setDescription) }
            item { TypeSelector(state.activityTypes, state.form.activityTypeId, viewModel::setActivityType) }
            item { CategorySelector(state.categories, state.form.categoryId, viewModel::setCategory) }
            item { TagSelector(state.tags, state.form.selectedTagIds, viewModel::toggleTag) }
            item { TimeEditorCard(state.form, state.duration, viewModel::setStartHour, viewModel::setStartMinute, viewModel::setEndHour, viewModel::setEndMinute) }
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AevumSpacing.md),
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(state.dayTitle, fontSize = 32.sp, fontWeight = FontWeight.SemiBold); Text(state.formattedDate, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row { IconButton(onClick = onPreviousDay) { Text("‹", fontSize = 28.sp) }; IconButton(onClick = onNextDay) { Text("›", fontSize = 28.sp) } }
        }
        Spacer(Modifier.height(AevumSpacing.md))
        OutlinedButton(onClick = onToday) { Text("Zu heute springen") }
    }
}

@Composable
private fun WeekStrip(selectedDate: LocalDate) {
    val today = LocalDate.now()
    AevumCard(variant = CardVariant.Filled) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Woche", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Wochenansicht vorbereitet — tägliche Navigation ist bereits aktiv.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                (-3..3).forEach { offset ->
                    val day = today.plusDays(offset.toLong())
                    AssistChip(onClick = {}, label = { Text((if (day == selectedDate) "● " else "") + day.dayOfWeek.name.take(2) + " " + day.dayOfMonth) })
                }
            }
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
private fun EditorHeader(isEditing: Boolean, duration: String, onBack: () -> Unit, onSave: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(if (isEditing) "Aktivität bearbeiten" else "Neue Aktivität", fontSize = 28.sp, fontWeight = FontWeight.SemiBold); Text("Dauer: $duration", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace) }; Column(horizontalAlignment = Alignment.End) { TextButton(onClick = onBack) { Text("Abbrechen") }; Button(onClick = onSave) { Text("Speichern") } } } }
}

@Composable
private fun BasicFields(state: ActivityEditorUiState, onTitle: (String) -> Unit, onDescription: (String) -> Unit) {
    AevumCard(variant = CardVariant.Gradient) { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) { OutlinedTextField(value = state.form.title, onValueChange = onTitle, modifier = Modifier.fillMaxWidth(), label = { Text("Was hast du gemacht?") }, placeholder = { Text("z. B. Deep Work, Training, Lesen") }, singleLine = true); OutlinedTextField(value = state.form.description, onValueChange = onDescription, modifier = Modifier.fillMaxWidth(), label = { Text("Notiz optional") }, minLines = 2, maxLines = 4) } }
}

@Composable
private fun TypeSelector(types: List<ActivityType>, selectedId: String?, onSelect: (ActivityType) -> Unit) = SelectorCard("Activity Type", "Semantische Bedeutung") { types.forEach { FilterChip(selected = it.id == selectedId, onClick = { onSelect(it) }, label = { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }) } }

@Composable
private fun CategorySelector(categories: List<Category>, selectedId: String?, onSelect: (String?) -> Unit) = SelectorCard("Kategorie", "Visuelle Gruppierung") { categories.forEach { FilterChip(selected = it.id == selectedId, onClick = { onSelect(it.id) }, label = { Text(it.name) }) } }

@Composable
private fun TagSelector(tags: List<Tag>, selectedIds: List<String>, onToggle: (String) -> Unit) = SelectorCard("Tags", "Optionaler Kontext") { tags.forEach { FilterChip(selected = it.id in selectedIds, onClick = { onToggle(it.id) }, label = { Text(it.name) }) } }

@Composable
private fun SelectorCard(title: String, subtitle: String, content: @Composable () -> Unit) { AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { content() } } } }

@Composable
private fun TimeEditorCard(form: ActivityEditorForm, duration: String, onStartHour: (Int) -> Unit, onStartMinute: (Int) -> Unit, onEndHour: (Int) -> Unit, onEndMinute: (Int) -> Unit) {
    val zone = ZoneId.systemDefault(); val start = Instant.ofEpochMilli(form.startAt).atZone(zone).toLocalTime(); val end = Instant.ofEpochMilli(form.endAt ?: form.startAt).atZone(zone).toLocalTime()
    AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("Zeitfenster", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(duration, color = MaterialTheme.colorScheme.secondary) }; Text(TimeFormatting.formatDate(form.date), color = MaterialTheme.colorScheme.onSurfaceVariant) }; TimeRow("Start", start.hour, start.minute, onStartHour, onStartMinute); TimeRow("Ende", end.hour, end.minute, onEndHour, onEndMinute) } }
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
