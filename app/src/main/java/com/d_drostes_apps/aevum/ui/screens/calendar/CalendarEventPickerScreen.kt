package com.d_drostes_apps.aevum.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import java.time.LocalDate
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
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    // M18.132: Beim Öffnen der Seite den Kalender-Sync anstoßen, wenn der
    // Cache leer oder älter als 15 Minuten ist — der Nutzer sieht dann
    // seine frisch angelegten Termine sofort statt erst beim nächsten
    // periodischen Sync (Default alle 6 Stunden).
    LaunchedEffect(Unit) {
        viewModel.refreshFromCalendar()
    }

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

        // ── Zusammenfassung (markierte Termine) ──────────────────────
        AevumCard(variant = CardVariant.Gradient) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
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
            }
        }

        // ── 7-Tage-Kalender ───────────────────────────────────────────
        // Eine Liste von Tages-Sektionen: jeder Tag hat seinen Header
        // („Donnerstag, 18. September" — heute hervorgehoben) und darunter
        // alle Termine dieses Tags. Der Auftrag will genau das: eine
        // Kalender-Ansicht der nächsten 7 Tage, in der jeder Termin
        // einzeln antippbar ist.
        AevumCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                // M18.132: Transienter Hinweis, WÄHREND der Öffnungs-Sync
                // läuft — sonst wirkt eine kurz leere Liste wie ein Fehler.
                if (syncing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            stringResource(R.string.calendar_picker_syncing),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.totalEventsInCache == 0 && !syncing) {
                    Text(
                        stringResource(R.string.calendar_picker_empty_no_sync),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.days.forEach { day ->
                    DaySection(
                        day = day,
                        zone = zone,
                        onEventClick = { event -> viewModel.openEvent(event) }
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
 * M18.132: Eine Tages-Sektion der 7-Tage-Ansicht.
 *
 * Aufbau: Tages-Header („Heute · Donnerstag, 18. September" bzw. das
 * Datum der Folgetage) plus die Termine dieses Tags. Ein Tag OHNE
 * Termine zeigt den Header mit dem dezenten Hinweis „Keine Termine" —
 * ein Google-Kalender zeigt leere Tage auch als leere Tage, nicht als
 * Nichts (sonst würde die Seite den Eindruck machen, dort endete der
 * Kalender).
 */
@Composable
private fun DaySection(
    day: CalendarDaySectionUi,
    zone: ZoneId,
    onEventClick: (CalendarEventCache) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        // ── Tages-Header ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            // Tages-Kürzel im Kreis (Google-Kalender-Stil): Wochentag
            // über der Tageszahl, heute in der Akzentfarbe.
            val dayOfWeekLabel = day.date.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.SHORT,
                AppLocale.current
            )
            val accent = if (day.isToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (day.isToday) accent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        dayOfWeekLabel,
                        fontSize = 9.sp,
                        color = accent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${day.date.dayOfMonth}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (day.isToday) accent
                            else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                text = when {
                    day.isToday -> stringResource(R.string.calendar_picker_day_today)
                    day.date == LocalDate.now().plusDays(1) ->
                        stringResource(R.string.calendar_picker_day_tomorrow)
                    else -> day.date.format(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(AppLocale.current)
                    )
                }.replaceFirstChar { it.uppercase() },
                fontSize = 14.sp,
                fontWeight = if (day.isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = if (day.isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
            )
        }

        // ── Termine des Tages ───────────────────────────────────────
        if (day.events.isEmpty()) {
            Text(
                stringResource(R.string.calendar_picker_day_empty),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 46.dp)
            )
        } else {
            day.events.forEach { row ->
                EventRow(
                    row = row,
                    zone = zone,
                    onClick = { onEventClick(row.event) }
                )
            }
        }
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
    // M18.132: Drei-Optionen-Auswahl statt Toggle — der Auftrag verlangt
    // explizit „startet, sobald keine Aufzeichnung mehr läuft" als dritte
    // Wahl. Ein PIN aus einer ÄLTEREN App-Version mit nur-if-idle wird
    // korrekt vorausgewählt.
    var overlapPolicy by remember(event.eventId) {
        mutableStateOf(
            existingPin?.overlapPolicy
                ?: CalendarOverlapPolicy.OVERRIDE
        )
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

                // Overlap-Verhalten (M18.132: drei Optionen statt Toggle —
                // der Auftrag verlangt explizit auch „startet, sobald keine
                // Aufzeichnung mehr läuft"). Radio-Zeilen, damit alle drei
                // sichtbar sind; die gewählte Option wird markiert.
                Text(
                    stringResource(R.string.calendar_picker_dialog_overlap_label),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                val overlapOptions = listOf(
                    Triple(
                        CalendarOverlapPolicy.OVERRIDE,
                        stringResource(R.string.calendar_picker_dialog_overlap_override),
                        stringResource(R.string.calendar_picker_dialog_overlap_override_desc)
                    ),
                    Triple(
                        CalendarOverlapPolicy.ONLY_IF_IDLE,
                        stringResource(R.string.calendar_picker_dialog_overlap_idle),
                        stringResource(R.string.calendar_picker_dialog_overlap_idle_desc)
                    ),
                    Triple(
                        CalendarOverlapPolicy.QUEUE_IF_BUSY,
                        stringResource(R.string.calendar_picker_dialog_overlap_queue),
                        stringResource(R.string.calendar_picker_dialog_overlap_queue_desc)
                    )
                )
                overlapOptions.forEach { (policy, label, desc) ->
                    val selected = overlapPolicy == policy
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AevumRadius.sm))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { overlapPolicy = policy }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (selected) 0.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onPrimary)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                desc,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val id = selectedTypeId
                if (id == null) {
                    errorShown = true
                } else {
                    onSave(id, customTitle.takeIf { it.isNotBlank() }, overlapPolicy)
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
