package de.devondroste.aevum.ui.screens.todos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.domain.todo.RecurrenceEngine
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import java.time.DayOfWeek

/**
 * M18.30: Todo-Editor.
 *
 * Felder (hinterfragt, auf UX reduziert):
 *  - Titel (Pflicht)
 *  - Typ-Umschalter: Checkbox vs. Dauer-Ziel (Slider 5-480 min)
 *  - Optional: Aktivitäts-Zuordnung (Icon + Farbe) — nur bei Dauer-Ziel
 *    sinnvoll (Auto-Check), aber auch bei Checkbox erlaubt.
 *  - Recurrence: Einmalig / Jeden Tag / Wochentags / Wochentage wählen /
 *    Alle x Tage / x-mal pro Woche / x-mal pro Monat
 *  - ONCE: optionales Fälligkeits-Datum (heute + 0..30 Tage)
 */
@Composable
fun TodoEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: TodoEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        item {
            AevumCard(variant = CardVariant.Gradient) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("NEUES TODO", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Aufgabe erstellen", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onBack) { Text("Abbrechen") }
                }
            }
        }

        item {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text("Was ist zu tun?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::setTitle,
                        label = { Text("z.B. Müll rausbringen, 2h lernen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Typ-Umschalter: Checkbox vs. Dauer
        item {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text("Art", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        TypeToggle(
                            label = "Checkbox",
                            selected = !state.isDuration,
                            onClick = { viewModel.setDuration(false) },
                            modifier = Modifier.weight(1f)
                        )
                        TypeToggle(
                            label = "Dauer-Ziel",
                            selected = state.isDuration,
                            onClick = { viewModel.setDuration(true) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (state.isDuration) {
                        // M18.37: Praezise Dauer-Eingabe. Vorher: Slider mit
                        // steps=18 bei 5..480 -> 25-Minuten-Schritte (230->255,
                        // 240 unmoeglich). Jetzt: Slider in 5-Minuten-Schritten
                        // + -5/+5-Buttons fuer Feintuning. 240 ist exakt treffbar.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Ziel-Dauer", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${state.targetMinutes} min",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = state.targetMinutes.toFloat(),
                            onValueChange = { viewModel.setTargetMinutes(it.toInt()) },
                            valueRange = 5f..480f,
                            steps = 94 // 5-Minuten-Schritte: (480-5)/5 - 1 = 94
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MinuteStepButton("-15", onClick = { viewModel.setTargetMinutes(state.targetMinutes - 15) })
                            Spacer(Modifier.width(AevumSpacing.sm))
                            MinuteStepButton("-5", onClick = { viewModel.setTargetMinutes(state.targetMinutes - 5) })
                            Spacer(Modifier.width(AevumSpacing.sm))
                            MinuteStepButton("+5", onClick = { viewModel.setTargetMinutes(state.targetMinutes + 5) })
                            Spacer(Modifier.width(AevumSpacing.sm))
                            MinuteStepButton("+15", onClick = { viewModel.setTargetMinutes(state.targetMinutes + 15) })
                        }
                        Text(
                            "Wird automatisch abgehakt, sobald die Aktivität diese Dauer heute erreicht hat.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Aktivitäts-Zuordnung
        item {
            ActivityTypePickerCard(
                activityTypes = state.activityTypes,
                selectedId = state.activityTypeId,
                onSelect = viewModel::setActivityType
            )
        }

        // Recurrence
        item {
            RecurrenceCard(
                state = state,
                onTypeChange = viewModel::setRecurrenceType,
                onWeekdaysChange = viewModel::toggleWeekday,
                onIntervalChange = viewModel::setIntervalDays,
                onCountChange = viewModel::setCountPerPeriod
            )
        }

        // Fälligkeitsdatum (nur ONCE)
        if (state.recurrenceType == RecurrenceEngine.TYPE_ONCE) {
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Fällig", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                            listOf(0 to "Heute", 1 to "Morgen", 7 to "In 7 Tagen", 14 to "In 14 Tagen").forEach { (days, label) ->
                                val selected = state.dueInDays == days
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AevumRadius.full))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        )
                                        .clickable { viewModel.setDueInDays(days) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Text(
                            if (state.dueInDays == 0) "Ohne Datum bleibt das Todo relevant, bis du es abhakst."
                            else "Nur an diesem Tag relevant — danach automatisch archiviert.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    viewModel.save()
                    onSaved()
                },
                enabled = state.title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Todo speichern") }
        }
        item { Spacer(Modifier.height(AevumSpacing.xxl)) }
    }
}

@Composable
private fun TypeToggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActivityTypePickerCard(
    activityTypes: List<ActivityType>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Aktivität (optional)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (selectedId != null) {
                    TextButton(onClick = { onSelect(null) }) { Text("Entfernen", fontSize = 12.sp) }
                }
            }
            Text(
                "Bei Dauer-Zielen wird die erfasste Zeit dieser Aktivität automatisch angerechnet.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.height(160.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(activityTypes, key = { it.id }) { type ->
                    val selected = type.id == selectedId
                    val typeColor = if (type.color != null && type.color != 0L) Color(type.color)
                    else MaterialTheme.colorScheme.secondary
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) typeColor.copy(alpha = 0.22f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                            .clickable { onSelect(type.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(typeColor.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(type.icon?.takeIf { it.isNotBlank() } ?: "•", fontSize = 14.sp)
                        }
                        Text(
                            type.name,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) Text("✓", fontSize = 14.sp, color = typeColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurrenceCard(
    state: TodoEditorUiState,
    onTypeChange: (String) -> Unit,
    onWeekdaysChange: (DayOfWeek) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCountChange: (Int) -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("Wiederholung", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            RecurrenceEngine.allTypes.forEach { type ->
                val selected = state.recurrenceType == type
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        )
                        .clickable { onTypeChange(type) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        RecurrenceEngine.labelFor(type),
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }

            if (state.recurrenceType == RecurrenceEngine.TYPE_WEEKLY_ON) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val sel = day in state.selectedWeekdays
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(
                                    if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .clickable { onWeekdaysChange(day) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()).take(2),
                                fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (state.recurrenceType == RecurrenceEngine.TYPE_EVERY_N_DAYS) {
                Text("Alle ${state.intervalDays} Tage", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Slider(
                    value = state.intervalDays.toFloat(),
                    onValueChange = { onIntervalChange(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28
                )
            }

            if (state.recurrenceType == RecurrenceEngine.TYPE_N_PER_WEEK ||
                state.recurrenceType == RecurrenceEngine.TYPE_N_PER_MONTH
            ) {
                val unit = if (state.recurrenceType == RecurrenceEngine.TYPE_N_PER_WEEK) "Woche" else "Monat"
                Text("${state.countPerPeriod}x pro $unit", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Slider(
                    value = state.countPerPeriod.toFloat(),
                    onValueChange = { onCountChange(it.toInt()) },
                    valueRange = 1f..14f,
                    steps = 12
                )
                Text(
                    "Flexibel: du entscheidest, an welchen Tagen. Die Quote zählt pro $unit.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * M18.37: Kleiner Rund-Button fuer die praezise Minuten-Eingabe.
 * -15 / -5 / +5 / +15 — zusammen mit dem 5-Minuten-Slider ist jede
 * Ziel-Dauer exakt treffbar (z.B. 240 min).
 */
@Composable
private fun MinuteStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
