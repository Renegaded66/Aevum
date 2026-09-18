package com.d_drostes_apps.aevum.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.d_drostes_apps.aevum.util.AppLocale

/**
 * M18.131: Termin-Auswahl — Kalender-Termine ansehen und einzeln zur
 * Aufzeichnung freigeben.
 *
 * WARUM DIESE SEITE IN DEN KALENDER-EINSTELLUNGEN LEBT:
 * Sie gehört funktional zum Kalender-Feature (dort sind Berechtigung,
 * Sync und Regeln), und der Nutzer findet „Kalender" an genau einer
 * Stelle. Eine eigene Navigations-Kachel würde die Einstellungen
 * zersplittern, ohne einen eigenen Nutzen zu stiften.
 *
 * BEDIENLOGIK:
 *  - Tag wählen, Termine sehen, Termin antippen → Dialog mit Aktivitätswahl.
 *  - Ein Termin, für den eine Regel greift, wird als INFORMATION gezeigt
 *    („Per Regel erfasst"), ist aber trotzdem antippbar: eine Markierung
 *    überstimmt die Regel (Auftrag: benutzerdefiniert vor Regel).
 *  - Ein markierter Termin zeigt seine Aktivität und kann im Dialog
 *    wieder entfernt werden.
 */
@Composable
fun CalendarEventPickerScreen(
    onBack: () -> Unit,
    viewModel: CalendarEventPickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editing by viewModel.editingEvent.collectAsStateWithLifecycle()
    val editingPin by viewModel.editingPin.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    // Meldung nach kurzer Zeit verwerfen (Muster aus CalendarRulesScreen).
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // ── Kopfzeile ─────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.calendar_picker_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.calendar_picker_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Tages-Navigation ──────────────────────────────────────────
        AevumCard(variant = CardVariant.Gradient) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = viewModel::previousDay) { Text("‹", fontSize = 22.sp) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val label = state.selectedDate.format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                                .withLocale(AppLocale.current)
                        )
                        Text(
                            label.replaceFirstChar { it.uppercase() },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = viewModel::nextDay) { Text("›", fontSize = 22.sp) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val summary = when (state.pinnedCount) {
                        0 -> stringResource(R.string.calendar_picker_summary_none)
                        1 -> stringResource(R.string.calendar_picker_summary_one)
                        else -> stringResource(R.string.calendar_picker_summary_many, state.pinnedCount)
                    }
                    Text(
                        summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        stringResource(R.string.calendar_picker_today),
                        modifier = Modifier
                            .clip(RoundedCornerShape(AevumRadius.full))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .clickable(onClick = viewModel::today)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ── Termin-Liste ──────────────────────────────────────────────
        AevumCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                if (state.events.isEmpty()) {
                    Text(
                        // Ehrliche Ursache statt „nichts da": eine leere Liste
                        // hat drei sehr verschiedene Gründe.
                        text = if (state.totalEventsInCache == 0) {
                            stringResource(R.string.calendar_picker_empty_no_sync)
                        } else {
                            stringResource(R.string.calendar_picker_empty)
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.events.forEach { row ->
                    EventRow(
                        row = row,
                        zone = zone,
                        // Der Termin reist im Zustand mit — kein
                        // Rekonstruieren aus Einzelfeldern.
                        onClick = { viewModel.openEvent(row.event) }
                    )
                }
            }
        }

        // ── Erklär-Karte ──────────────────────────────────────────────
        AevumCard(variant = CardVariant.Outlined) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text(
                    stringResource(R.string.calendar_picker_how_it_works_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.calendar_picker_how_it_works_body),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(AevumSpacing.xl))
    }

    // ── Dialog: Aktivität wählen ──────────────────────────────────────
    editing?.let { event ->
        EventPinDialog(
            event = event,
            activityTypes = state.activityTypes,
            // Vorbelegung aus der bestehenden Markierung (Aktivität, Titel,
            // Overlap-Verhalten) — sonst würde „öffnen → speichern" eine
            // vorhandene Zuordnung stillschweigend zurücksetzen.
            existingPin = editingPin,
            onDismiss = viewModel::closeEvent,
            onSave = { typeId, title, policy ->
                viewModel.pinEvent(event, typeId, title, policy)
            },
            onRemove = { viewModel.unpinEvent(event.eventId) }
        )
    }
}

/**
 * Eine Termin-Zeile.
 *
 * Die Statuszeile ist der Kern der Verständlichkeit: der Nutzer muss auf
 * einen Blick sehen, ob ein Termin aufgezeichnet wird und WODURCH
 * (eigene Markierung oder Regel).
 */
@Composable
private fun EventRow(
    row: CalendarEventRowUi,
    zone: ZoneId,
    onClick: () -> Unit
) {
    val statusText: String
    val statusColor: Color
    when {
        row.pinActivityMissing -> {
            statusText = stringResource(R.string.calendar_picker_status_activity_missing)
            statusColor = MaterialTheme.colorScheme.error
        }
        row.isPinned -> {
            statusText = stringResource(R.string.calendar_picker_status_pinned)
            statusColor = MaterialTheme.colorScheme.primary
        }
        else -> {
            statusText = stringResource(R.string.calendar_picker_status_none)
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.sm))
            .background(
                if (row.isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Aktivitäts-Icon der Markierung (oder Statuspunkt).
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    when {
                        row.pinnedActivityColor != 0L -> Color(row.pinnedActivityColor).copy(alpha = 0.22f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (row.isPinned && !row.pinActivityMissing) {
                Text(row.pinnedActivityIcon ?: "•", fontSize = 16.sp)
            } else {
                Text(
                    if (row.allDay) "◆" else "◇",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(AevumSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title.ifBlank { stringResource(R.string.calendar_picker_no_title) },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (row.allDay) {
                    stringResource(R.string.calendar_picker_all_day)
                } else {
                    "${TimeFormatting.formatTime(row.startAt, zone)}–${TimeFormatting.formatTime(row.endAt, zone)}" +
                        when {
                            row.isRunning -> " · " + stringResource(R.string.calendar_picker_running_now)
                            row.isPast -> " · " + stringResource(R.string.calendar_picker_past)
                            else -> ""
                        }
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(statusText, fontSize = 11.sp, color = statusColor)
        }
        if (row.isPinned) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Dialog zur Zuordnung eines Termins.
 *
 * Zeigt IMMER den Zeitraum, der aufgezeichnet würde — die wichtigste
 * Information, damit der Nutzer vor dem Bestätigen sieht, was passiert.
 * Der Hinweis auf Wiederholungen ist bewusst dabei: genau das ist der
 * Unterschied zur Regel und der Grund, warum es diese Funktion gibt.
 */
@Composable
private fun EventPinDialog(
    event: CalendarEventCache,
    activityTypes: List<ActivityType>,
    /** Bestehende Markierung des Termins (Vorbelegung) oder null. */
    existingPin: CalendarEventPin?,
    onDismiss: () -> Unit,
    onSave: (activityTypeId: String, customTitle: String?, overlapPolicy: String) -> Unit,
    onRemove: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val startLocal = Instant.ofEpochMilli(event.startAt).atZone(zone)
    val endLocal = Instant.ofEpochMilli(event.endAt).atZone(zone)
    val isRunning = event.startAt <= System.currentTimeMillis() &&
        event.endAt > System.currentTimeMillis()
    val isPast = event.endAt <= System.currentTimeMillis()

    // Vorbelegung aus der bestehenden Markierung: der Schlüssel ist die
    // eventId, damit ein Wechsel des Termins den Dialog neu initialisiert
    // (sonst schleppte er die Auswahl des vorigen Termins mit).
    var selectedTypeId by remember(event.eventId) {
        mutableStateOf(existingPin?.activityTypeId)
    }
    var customTitle by remember(event.eventId) {
        mutableStateOf(existingPin?.defaultTitle.orEmpty())
    }
    var overrideRunning by remember(event.eventId) {
        mutableStateOf(existingPin?.overlapPolicy != CalendarOverlapPolicy.ONLY_IF_IDLE)
    }
    var errorShown by remember(event.eventId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_picker_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                // Termin-Kopf
                Text(
                    event.title.ifBlank { stringResource(R.string.calendar_picker_no_title) },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (event.allDay) {
                        stringResource(R.string.calendar_picker_all_day)
                    } else {
                        "${startLocal.format(timeFmt)}–${endLocal.format(timeFmt)}"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    event.calendarName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Zeitraum-Hinweis (was würde aufgezeichnet)
                Text(
                    text = when {
                        isRunning -> stringResource(R.string.calendar_picker_dialog_info_running)
                        isPast -> stringResource(R.string.calendar_picker_dialog_info_past)
                        else -> stringResource(
                            R.string.calendar_picker_dialog_info,
                            "${startLocal.format(timeFmt)}",
                            "${endLocal.format(timeFmt)}"
                        )
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(2.dp))

                // Aktivitäts-Auswahl
                Text(
                    stringResource(R.string.calendar_picker_dialog_activity_label),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (errorShown && selectedTypeId == null) {
                    Text(
                        stringResource(R.string.calendar_picker_dialog_error_no_activity),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(activityTypes, key = { it.id }) { type ->
                        val selected = selectedTypeId == type.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AevumRadius.sm))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else Color.Transparent
                                )
                                .clickable { selectedTypeId = type.id; errorShown = false }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(type.icon, fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(type.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    label = { Text(stringResource(R.string.calendar_picker_dialog_custom_title_label)) },
                    placeholder = { Text(stringResource(R.string.calendar_picker_dialog_custom_title_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Overlap-Verhalten (nur relevant, wenn etwas läuft)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.calendar_editor_toggle_stop_running),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = overrideRunning, onCheckedChange = { overrideRunning = it })
                }
                Text(
                    stringResource(R.string.calendar_editor_toggle_stop_running_desc),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val id = selectedTypeId
                if (id == null) {
                    errorShown = true
                } else {
                    onSave(
                        id,
                        customTitle.takeIf { it.isNotBlank() },
                        if (overrideRunning) CalendarOverlapPolicy.OVERRIDE
                        else CalendarOverlapPolicy.ONLY_IF_IDLE
                    )
                }
            }) {
                Text(stringResource(R.string.calendar_picker_dialog_save))
            }
        },
        dismissButton = {
            Row {
                // „Entfernen" nur zeigen, wenn es etwas zu entfernen gibt —
                // ein Knopf, der nichts tut, verwirrt.
                if (existingPin != null) {
                    TextButton(onClick = onRemove) {
                        Text(
                            stringResource(R.string.calendar_picker_dialog_remove),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}
