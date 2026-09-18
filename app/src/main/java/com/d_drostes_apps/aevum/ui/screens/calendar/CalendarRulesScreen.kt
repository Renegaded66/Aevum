package com.d_drostes_apps.aevum.ui.screens.calendar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.util.AppLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** M18.129: Erlaubte Sync-Takte (Stunden) für die Auswahl. */
private val SYNC_INTERVALS = listOf(1, 3, 6, 12, 24)

/**
 * M18.129: Die Kalender-Regeln-Seite (Einstellungen).
 *
 * Aufbau, von oben nach unten:
 *  1. Permission-Banner (4-State) — verschwindet, wenn erteilt
 *  2. Feature-Schalter (Kalender lesen / Automatisch aufzeichnen)
 *  3. Sync-Panel: Zeitstempel · Sync-Button · Takt-Auswahl · Termin-Zähler
 *  4. Regel-Liste mit Aktivieren/Deaktivieren, Bearbeiten, Löschen
 *  5. „Regel hinzufügen"
 *
 * Die Trennung von Lesen und Aufzeichnen ist Absicht: der Nutzer kann die
 * Vorschau wollen, ohne dass sein Kalender die Aufzeichnung steuert.
 *
 * M18.129-i18n: Alle sichtbaren Texte kommen aus `strings_calendar.xml`
 * (DE in `values/`, EN in `values-en/`). Kein deutscher Literaltext mehr
 * im Composable-Code.
 */
@Composable
fun CalendarRulesScreen(
    onBack: () -> Unit,
    /** M18.131: öffnet die Termin-Auswahl (einzelne Termine zuordnen). */
    onOpenEventPicker: () -> Unit = {},
    viewModel: CalendarRulesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permission by viewModel.permissionState.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()

    var editingRule by remember { mutableStateOf<CalendarRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CalendarRule?>(null) }
    var bannerDismissed by remember { mutableStateOf(false) }

    // Permission-Launcher (Muster aus android-permission-flow-composition:
    // der Launcher lebt im @Composable, das ViewModel hält nur State).
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    // M18.129: Permission bei Rückkehr aus den System-Einstellungen neu
    // lesen. Der Nutzer kann sie dort erteilen ODER entziehen — ohne
    // diesen Refresh zeigte die UI einen veralteten Zustand.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sync-Ergebnis als einmaligen Hinweis anzeigen, dann verwerfen.
    LaunchedEffect(syncMessage) {
        if (syncMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.consumeSyncMessage()
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
                    stringResource(R.string.calendar_rules_header),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.calendar_rules_header_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 0. Termin-Auswahl (M18.131) ───────────────────────────────
        // BEWUSST GANZ OBEN: Das ist der direkte Weg („ich will diesen
        // Termin aufzeichnen") — die Regeln darunter sind das mächtigere,
        // aber erklärungsbedürftigere Werkzeug. Der Nutzer soll erst den
        // einfachen Weg sehen.
        if (permission.isGranted) {
            AevumCard(variant = CardVariant.Gradient) {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(R.string.calendar_picker_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.calendar_picker_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenEventPicker,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.syncEnabled
                    ) {
                        Text(stringResource(R.string.calendar_picker_open))
                    }
                    if (!state.syncEnabled) {
                        Text(
                            stringResource(R.string.calendar_rules_autotrack_needs_read),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── 1. Permission-Banner (nur wenn NICHT erteilt) ─────────────
        if (!permission.isGranted && !bannerDismissed) {
            PermissionBanner(
                permission = permission,
                onRequest = { permissionLauncher.launch(android.Manifest.permission.READ_CALENDAR) },
                onOpenSettings = viewModel::openSettings,
                onDismiss = { bannerDismissed = true }
            )
        }

        // ── 2. Feature-Schalter ───────────────────────────────────────
        AevumCard {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                Text(
                    stringResource(R.string.calendar_rules_section_integration),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                ToggleLine(
                    title = stringResource(R.string.calendar_rules_toggle_read),
                    subtitle = if (permission.isGranted) {
                        stringResource(R.string.calendar_rules_toggle_read_desc_granted)
                    } else {
                        stringResource(R.string.calendar_rules_toggle_read_desc_locked)
                    },
                    checked = state.syncEnabled,
                    enabled = permission.isGranted,
                    onCheckedChange = { viewModel.setSyncEnabled(it) }
                )

                ToggleLine(
                    title = stringResource(R.string.calendar_rules_toggle_autotrack),
                    subtitle = stringResource(R.string.calendar_rules_toggle_autotrack_desc),
                    checked = state.autoTrackingEnabled,
                    // Auto-Aufzeichnung ohne Lesezugriff wäre sinnlos —
                    // der Schalter ist deshalb gekoppelt (ehrliche UI,
                    // kein stiller Fehlschlag).
                    enabled = permission.isGranted && state.syncEnabled,
                    onCheckedChange = { viewModel.setAutoTrackingEnabled(it) }
                )

                if (permission.isGranted && !state.syncEnabled) {
                    Text(
                        stringResource(R.string.calendar_rules_autotrack_needs_read),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 3. Sync-Panel ─────────────────────────────────────────────
        if (state.syncEnabled && permission.isGranted) {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(R.string.calendar_rules_section_sync),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Zeitstempel des letzten Syncs (Auftragsanforderung).
                    Text(
                        text = formatLastSync(state.lastSyncAt),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when (state.cachedEventCount) {
                            0 -> stringResource(R.string.calendar_rules_cached_none)
                            1 -> stringResource(R.string.calendar_rules_cached_one)
                            else -> stringResource(
                                R.string.calendar_rules_cached_many,
                                state.cachedEventCount
                            )
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                    ) {
                        Button(
                            onClick = { viewModel.syncNow() },
                            enabled = !syncing
                        ) {
                            if (syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (syncing) {
                                    stringResource(R.string.calendar_rules_sync_running)
                                } else {
                                    stringResource(R.string.calendar_rules_sync_now)
                                }
                            )
                        }
                    }

                    // Ergebnis-Feedback (transient). Der ViewModel-State hält
                    // weiterhin technische Codes (sync_ok_<n>/sync_failed*);
                    // nur die Auflösung in Text ist hier lokalisiert.
                    syncMessage?.let { msg ->
                        val text = when {
                            msg.startsWith("sync_ok_") -> {
                                // Ehrlich bleiben: ein unparsebarer Zähler
                                // darf nicht stillschweigend als "0" erscheinen.
                                val count = msg.removePrefix("sync_ok_").toIntOrNull()
                                if (count == null) {
                                    stringResource(R.string.calendar_rules_sync_result_failed)
                                } else {
                                    stringResource(R.string.calendar_rules_sync_result_ok, count)
                                }
                            }
                            msg == "sync_failed_permission" ->
                                stringResource(R.string.calendar_rules_sync_result_permission)
                            else ->
                                stringResource(R.string.calendar_rules_sync_result_failed)
                        }
                        Text(
                            text,
                            fontSize = 12.sp,
                            color = if (msg.startsWith("sync_ok_")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.calendar_rules_sync_interval_label),
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SYNC_INTERVALS.forEach { hours ->
                            val selected = state.syncIntervalHours == hours
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable { viewModel.setSyncInterval(hours) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    if (hours == 24) {
                                        stringResource(R.string.calendar_rules_sync_interval_day)
                                    } else {
                                        stringResource(R.string.calendar_rules_sync_interval_hours, hours)
                                    },
                                    fontSize = 12.sp,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.calendar_rules_sync_battery_note),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 4. Regel-Liste ────────────────────────────────────────────
        AevumCard {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.calendar_rules_section_rules),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${state.rules.size}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (state.rules.isEmpty()) {
                    Text(
                        stringResource(R.string.calendar_rules_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.rules.forEach { rule ->
                    RuleRow(
                        rule = rule,
                        activityType = state.activityTypes.firstOrNull { it.id == rule.activityTypeId },
                        onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                        onEdit = { editingRule = rule; showEditor = true },
                        onDelete = { pendingDelete = rule }
                    )
                }

                Spacer(Modifier.height(2.dp))
                OutlinedButton(
                    onClick = { editingRule = null; showEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = permission.isGranted
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.calendar_rules_add))
                }
                if (!permission.isGranted) {
                    Text(
                        stringResource(R.string.calendar_rules_add_needs_permission),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Erklär-Karte ──────────────────────────────────────────────
        AevumCard(variant = CardVariant.Outlined) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text(
                    stringResource(R.string.calendar_rules_how_it_works_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.calendar_rules_how_it_works_body),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(AevumSpacing.xl))
    }

    // ── Dialoge ───────────────────────────────────────────────────────
    if (showEditor) {
        CalendarRuleEditorDialog(
            existing = editingRule,
            activityTypes = state.activityTypes,
            calendars = calendars,
            onDismiss = { showEditor = false; editingRule = null },
            onSave = { rule ->
                viewModel.saveRule(rule)
                showEditor = false
                editingRule = null
            }
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.calendar_rules_delete_title)) },
            text = {
                Text(stringResource(R.string.calendar_rules_delete_message, rule.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * M18.129: Permission-Banner — je Zustand der passende Weg.
 *
 * NotAsked/Denied → Dialog auslösen.
 * PermanentlyDenied → nur der Settings-Link hilft (ein weiterer Dialog
 * würde stillschweigend nichts tun).
 */
@Composable
private fun PermissionBanner(
    permission: CalendarPermissionState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                stringResource(R.string.calendar_rules_permission_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when (permission) {
                    is CalendarPermissionState.PermanentlyDenied ->
                        stringResource(R.string.calendar_rules_permission_body_blocked)
                    is CalendarPermissionState.Denied ->
                        stringResource(R.string.calendar_rules_permission_body_denied)
                    else ->
                        stringResource(R.string.calendar_rules_permission_body_notasked)
                },
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                when (permission) {
                    is CalendarPermissionState.PermanentlyDenied -> {
                        Button(onClick = onOpenSettings) {
                            Text(stringResource(R.string.calendar_rules_permission_open_settings))
                        }
                    }
                    is CalendarPermissionState.Denied -> {
                        Button(onClick = onRequest) {
                            Text(stringResource(R.string.calendar_rules_permission_retry))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.calendar_rules_permission_later))
                        }
                    }
                    else -> {
                        Button(onClick = onRequest) {
                            Text(stringResource(R.string.calendar_rules_permission_allow))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.calendar_rules_permission_skip))
                        }
                    }
                }
            }
        }
    }
}

/** Eine Regel-Zeile: Icon · Name · Bedingung · Aktivität · Aktionen. */
@Composable
private fun RuleRow(
    rule: CalendarRule,
    activityType: ActivityType?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rule.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                ruleConditionSummary(rule),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (activityType != null) {
                    "→ ${activityType.icon} ${activityType.name}"
                } else {
                    // Ehrlicher Hinweis statt stiller Nicht-Ausführung
                    // (die Aktivität kann gelöscht worden sein —
                    // ON DELETE SET NULL).
                    stringResource(R.string.calendar_rules_rule_activity_missing)
                },
                fontSize = 11.sp,
                color = if (activityType != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.common_edit),
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.common_delete),
                modifier = Modifier.size(18.dp)
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ToggleLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Kompakte, lesbare Zusammenfassung der Regel-Bedingung.
 *
 * M18.129-i18n: `@Composable`, weil die Bausteine aus Ressourcen kommen.
 * Wird ausschließlich innerhalb von `Text(...)` aufgerufen — das ist
 * erlaubt und hält die Lokalisierung an einer Stelle.
 */
@Composable
private fun ruleConditionSummary(rule: CalendarRule): String {
    val base = when (rule.matchType) {
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.TITLE_CONTAINS ->
            stringResource(R.string.calendar_rules_summary_title_contains, rule.matchValue)
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.DESCRIPTION_CONTAINS ->
            stringResource(R.string.calendar_rules_summary_desc_contains, rule.matchValue)
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.TITLE_REGEX ->
            stringResource(R.string.calendar_rules_summary_title_regex, rule.matchValue)
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.CALENDAR_IS -> {
            val n = com.d_drostes_apps.aevum.domain.calendar.CalendarMatchEngine
                .parseIds(rule.matchCalendarIds).size
            if (n == 0) {
                stringResource(R.string.calendar_rules_summary_all_calendars)
            } else {
                stringResource(R.string.calendar_rules_summary_calendars_selected, n)
            }
        }
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.ATTENDEE_CONTAINS ->
            stringResource(R.string.calendar_rules_summary_attendee_contains, rule.matchValue)
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.ALL_DAY_ONLY ->
            stringResource(R.string.calendar_rules_summary_all_day_only)
        else ->
            stringResource(R.string.calendar_rules_summary_anyfield_contains, rule.matchValue)
    }
    val extras = mutableListOf<String>()
    if (rule.requireAllWords) extras += stringResource(R.string.calendar_rules_summary_extra_all_words)
    if (rule.caseSensitive) extras += stringResource(R.string.calendar_rules_summary_extra_case)
    if (rule.minDurationMinutes > 0) {
        extras += stringResource(
            R.string.calendar_rules_summary_extra_min_duration,
            rule.minDurationMinutes
        )
    }
    if (rule.weekdayMask != 0x7F) {
        extras += stringResource(R.string.calendar_rules_summary_extra_weekdays)
    }
    if (rule.windowStartMinute >= 0 || rule.windowEndMinute >= 0) {
        val from = rule.windowStartMinute.takeIf { it >= 0 }?.let { "%02d:%02d".format(it / 60, it % 60) }
        val to = rule.windowEndMinute.takeIf { it >= 0 }?.let { "%02d:%02d".format(it / 60, it % 60) }
        extras += listOfNotNull(from, to).joinToString("–")
    }
    return if (extras.isEmpty()) base else "$base · ${extras.joinToString(", ")}"
}

/**
 * „Zuletzt synchronisiert: heute 14:23" / „noch nie".
 *
 * Das Zeitformat (HH:mm) ist sprachneutral. Das DATUM wird lokalisiert
 * formatiert — auf Deutsch bleibt es beim projektweit üblichen
 * `dd.MM.yyyy`, auf Englisch wird daraus z. B. „Sep 14, 2026" (ein
 * deutsches Datumsmuster wäre für englische Nutzer irreführend).
 */
@Composable
private fun formatLastSync(lastSyncAt: Long): String {
    if (lastSyncAt <= 0L) return stringResource(R.string.calendar_rules_last_sync_never)
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(lastSyncAt).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (dt.toLocalDate()) {
        today -> stringResource(R.string.calendar_rules_last_sync_today, time)
        today.minusDays(1) -> stringResource(R.string.calendar_rules_last_sync_yesterday, time)
        else -> {
            val date = dt.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLocale.current)
            )
            stringResource(R.string.calendar_rules_last_sync_date, date, time)
        }
    }
}
