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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 */
@Composable
fun CalendarRulesScreen(
    onBack: () -> Unit,
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
                Text("Kalender", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Termine automatisch als Aktivitäten aufzeichnen",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                Text("Kalender-Integration", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

                ToggleLine(
                    title = "Kalender lesen",
                    subtitle = if (permission.isGranted) {
                        "Termine werden lokal ausgelesen und für die Vorschau genutzt."
                    } else {
                        "Berechtigung erforderlich — siehe Hinweis oben."
                    },
                    checked = state.syncEnabled,
                    enabled = permission.isGranted,
                    onCheckedChange = { viewModel.setSyncEnabled(it) }
                )

                ToggleLine(
                    title = "Automatisch aufzeichnen",
                    subtitle = "Startet und stoppt Aktivitäten an Termingrenzen.",
                    checked = state.autoTrackingEnabled,
                    // Auto-Aufzeichnung ohne Lesezugriff wäre sinnlos —
                    // der Schalter ist deshalb gekoppelt (ehrliche UI,
                    // kein stiller Fehlschlag).
                    enabled = permission.isGranted && state.syncEnabled,
                    onCheckedChange = { viewModel.setAutoTrackingEnabled(it) }
                )

                if (permission.isGranted && !state.syncEnabled) {
                    Text(
                        "Zum Aufzeichnen zuerst „Kalender lesen“ aktivieren.",
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
                    Text("Synchronisierung", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

                    // Zeitstempel des letzten Syncs (Auftragsanforderung).
                    Text(
                        text = formatLastSync(state.lastSyncAt),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            state.cachedEventCount == 0 -> "Keine Termine im Synchronisierungsfenster."
                            state.cachedEventCount == 1 -> "1 Termin zwischengespeichert."
                            else -> "${state.cachedEventCount} Termine zwischengespeichert."
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
                            Text(if (syncing) "Synchronisiere…" else "Jetzt synchronisieren")
                        }
                    }

                    // Ergebnis-Feedback (transient).
                    syncMessage?.let { msg ->
                        val text = when {
                            msg.startsWith("sync_ok_") ->
                                "Synchronisiert: ${msg.removePrefix("sync_ok_")} Termine."
                            msg == "sync_failed_permission" -> "Berechtigung fehlt — bitte erneut erteilen."
                            else -> "Synchronisierung fehlgeschlagen."
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
                    Text("Automatischer Sync alle:", fontSize = 13.sp)
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
                                    if (hours == 24) "1 Tag" else "${hours} h",
                                    fontSize = 12.sp,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        "Synchronisiert nur bei ausreichend Akku — kein 24/7-Zugriff.",
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
                        "Regeln",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${state.rules.size}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (state.rules.isEmpty()) {
                    Text(
                        "Noch keine Regeln. Beispiel: „Enthält Vorlesung oder Übung → Activity Studium“.",
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
                    Text("Regel hinzufügen")
                }
                if (!permission.isGranted) {
                    Text(
                        "Regeln lassen sich nach dem Erteilen der Berechtigung anlegen.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Erklär-Karte ──────────────────────────────────────────────
        AevumCard(variant = CardVariant.Outlined) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text("So funktioniert es", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "• Trifft ein Termin auf eine Regel, startet die Aufzeichnung " +
                        "zum Terminbeginn und endet mit dem Terminende.\n" +
                        "• In der Timeline erscheinen die kommenden 7 Tage bereits " +
                        "vorab — diagonal gestrichelt, damit Pläne und echte " +
                        "Aufzeichnungen unterscheidbar bleiben.\n" +
                        "• Aevum liest den Kalender nur. Es werden niemals Termine " +
                        "angelegt oder verändert.",
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
            title = { Text("Regel löschen?") },
            text = { Text("„${rule.name}“ wird entfernt. Bereits aufgezeichnete Aktivitäten bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule.id)
                    pendingDelete = null
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") }
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
            Text("Kalender verbinden", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = when (permission) {
                    is CalendarPermissionState.PermanentlyDenied ->
                        "Die Berechtigung ist gesperrt. Öffne die App-Einstellungen und erlaube " +
                            "„Kalender“ unter Berechtigungen."
                    is CalendarPermissionState.Denied ->
                        "Ohne Kalender-Zugriff kann Aevum keine Termine erkennen. " +
                            "Die Berechtigung ist jederzeit in den System-Einstellungen widerrufbar."
                    else ->
                        "Aevum liest deine Termine ausschließlich lokal auf diesem Gerät, " +
                            "um Aktivitäten zum richtigen Zeitpunkt aufzuzeichnen. " +
                            "Es werden keine Termine angelegt oder verändert."
                },
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                when (permission) {
                    is CalendarPermissionState.PermanentlyDenied -> {
                        Button(onClick = onOpenSettings) { Text("App-Einstellungen öffnen") }
                    }
                    is CalendarPermissionState.Denied -> {
                        Button(onClick = onRequest) { Text("Erneut versuchen") }
                        TextButton(onClick = onDismiss) { Text("Später") }
                    }
                    else -> {
                        Button(onClick = onRequest) { Text("Zugriff erlauben") }
                        TextButton(onClick = onDismiss) { Text("Erstmal ohne") }
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
                    "→ Aktivität fehlt (bitte neu wählen)"
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
            Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Löschen", modifier = Modifier.size(18.dp))
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

/** Kompakte, lesbare Zusammenfassung der Regel-Bedingung. */
private fun ruleConditionSummary(rule: CalendarRule): String {
    val base = when (rule.matchType) {
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.TITLE_CONTAINS ->
            "Titel enthält „${rule.matchValue}“"
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.DESCRIPTION_CONTAINS ->
            "Beschreibung enthält „${rule.matchValue}“"
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.TITLE_REGEX ->
            "Titel passt auf /${rule.matchValue}/"
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.CALENDAR_IS -> {
            val n = com.d_drostes_apps.aevum.domain.calendar.CalendarMatchEngine
                .parseIds(rule.matchCalendarIds).size
            if (n == 0) "Alle Kalender" else "$n Kalender ausgewählt"
        }
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.ATTENDEE_CONTAINS ->
            "Teilnehmer enthält „${rule.matchValue}“"
        com.d_drostes_apps.aevum.data.model.CalendarRuleType.ALL_DAY_ONLY ->
            "Nur ganztägige Termine"
        else ->
            "Titel/Beschreibung enthält „${rule.matchValue}“"
    }
    val extras = mutableListOf<String>()
    if (rule.requireAllWords) extras += "alle Wörter"
    if (rule.caseSensitive) extras += "Groß/Klein"
    if (rule.minDurationMinutes > 0) extras += "≥ ${rule.minDurationMinutes} min"
    if (rule.weekdayMask != 0x7F) extras += "bestimmte Tage"
    if (rule.windowStartMinute >= 0 || rule.windowEndMinute >= 0) {
        val from = rule.windowStartMinute.takeIf { it >= 0 }?.let { "%02d:%02d".format(it / 60, it % 60) }
        val to = rule.windowEndMinute.takeIf { it >= 0 }?.let { "%02d:%02d".format(it / 60, it % 60) }
        extras += listOfNotNull(from, to).joinToString("–")
    }
    return if (extras.isEmpty()) base else "$base · ${extras.joinToString(", ")}"
}

/** „Zuletzt synchronisiert: heute 14:23" / „noch nie". */
private fun formatLastSync(lastSyncAt: Long): String {
    if (lastSyncAt <= 0L) return "Zuletzt synchronisiert: noch nie"
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(lastSyncAt).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (dt.toLocalDate()) {
        today -> "Zuletzt synchronisiert: heute $time"
        today.minusDays(1) -> "Zuletzt synchronisiert: gestern $time"
        else -> "Zuletzt synchronisiert: ${dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} $time"
    }
}
