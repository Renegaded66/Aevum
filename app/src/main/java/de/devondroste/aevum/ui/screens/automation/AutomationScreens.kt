package de.devondroste.aevum.ui.screens.automation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import de.devondroste.aevum.automation.geofence.GeofenceDebugLogger
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.AevumMapView
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.theme.AevumSpacing

// ══════════════════════════════════════════════════════
// AutomationSettingsScreen (M8.1: UX-polished)
// ══════════════════════════════════════════════════════

// M12.1: Item-Index der "Digital Balance" Karte in der Automation-Settings
// LazyColumn (header=0, status=1, toggles=2, health=3, sleep=4, digital=5, ...).
private const val USAGE_STATS_ITEM_INDEX = 5

@Composable
fun AutomationSettingsScreen(
    modifier: Modifier = Modifier,
    onOpenGeofences: () -> Unit,
    onOpenTriggers: () -> Unit,
    onOpenStatus: () -> Unit,
    viewModel: AutomationSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val isAnalyzingSleep by viewModel.isAnalyzingSleep.collectAsState()
    val sleepStatus by viewModel.sleepStatus.collectAsState()
    // M14: State für die 3-Signal-Fusion.
    val isAnalyzingFusion by viewModel.isAnalyzingFusion.collectAsState()
    val fusionStatus by viewModel.fusionStatus.collectAsState()
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshGeofences() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshGeofences() }
    val context = LocalContext.current

    // M12.1: Scroll-zu-UsageStats-Signal aus dem Dashboard.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollToUsage = AutomationScrollSignal.consumeScrollToUsage()
    LaunchedEffect(scrollToUsage) {
        if (scrollToUsage) {
            // USAGE_STATS_ITEM_INDEX = 5 (Digital-Balance-Block)
            // animateScrollToItem kappt sicher, wenn der Index out-of-range ist.
            listState.animateScrollToItem(USAGE_STATS_ITEM_INDEX)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // Header
            item {
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                        Text("Automatisierung", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                        Text("Aevum erkennt Orte nur mit deiner Erlaubnis. Alle Erkennungen sind nachvollziehbar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Status summary
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Status", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine("Standort", state.foregroundLocationGranted)
                        StatusLine("Hintergrundstandort", state.backgroundLocationGranted)
                        StatusLine("Benachrichtigungen", state.notificationsGranted)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(onClick = onOpenStatus) { Text("Status-Details") }
                            OutlinedButton(onClick = { viewModel.refreshGeofences() }) { Text("Jetzt prüfen") }
                        }
                    }
                }
            }

            // Toggles
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        SettingSwitch("Hintergrunderfassung", "Geofences erkennen, auch wenn Aevum geschlossen ist", state.settings.backgroundCaptureEnabled, viewModel::setBackgroundCapture)
                    }
                }
            }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Health Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingSwitch("Schlaf automatisch erkennen", "Aus Health Connect importieren", state.settings.healthSleepEnabled, viewModel::setHealthSleep)
                    }
                }
            }
            // M14: Intelligente Schlaf-Erkennung — 3-Signal-Fusion aus Screen,
            // Activity Recognition (STILL) und Digital Balance. Funktioniert ohne
            // Health Connect. Auto-Accept ab 2/3 Signalen, Review-Inbox bei 1/3.
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Intelligente Schlaf-Erkennung", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Kombiniert Bildschirm-Events, Activity-Recognition (STILL) und Bildschirmzeit. " +
                            "Bei 2 von 3 Signalen wird der Schlaf direkt in die Timeline übernommen — sonst Vorschlag in der Review-Inbox.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingSwitch(
                            title = "Schlaf automatisch erkennen",
                            description = "Aus Screen + STILL + Bildschirmzeit fusionieren",
                            checked = state.settings.sleepFusionEnabled,
                            onCheckedChange = viewModel::setSleepFusion
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { viewModel.analyzeSleepFusionNow() },
                                enabled = !isAnalyzingFusion
                            ) {
                                if (isAnalyzingFusion) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text("Fusion läuft…")
                                } else {
                                    Text("Jetzt fusionieren")
                                }
                            }
                            OutlinedButton(onClick = { viewModel.openFusionStatus() }) { Text("Status") }
                        }
                    }
                }
            }
            // M13: Schlaf ohne Health Connect — Bildschirm-Muster Heuristik.
            // Funktioniert auf jedem Android-Gerät, ist deutlich weniger genau als
            // Health Connect, liefert aber eine echte Schlaf-Schätzung.
            item {
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Schlaf aus Bildschirm-Muster", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Aevum erkennt Schlaf aus Screen-On/Off-Zeiten, wenn Health Connect nicht verfügbar ist. " +
                            "Die Vorschläge landen in der Review-Inbox mit niedriger Confidence — du bestätigst sie selbst.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { viewModel.analyzeSleepNow() },
                                enabled = !isAnalyzingSleep
                            ) {
                                if (isAnalyzingSleep) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text("Analysiere…")
                                } else {
                                    Text("Jetzt analysieren")
                                }
                            }
                            OutlinedButton(onClick = { viewModel.openSleepStatus() }) { Text("Status") }
                        }
                    }
                }
            }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Digital Balance", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingSwitch("Bildschirmzeit erfassen", "Nutzungsstatistiken lokal analysieren", state.settings.digitalBalanceEnabled, viewModel::setDigitalBalance)
                        if (!state.usageStatsGranted && state.settings.digitalBalanceEnabled) {
                            TextButton(onClick = { viewModel.openUsageAccess() }) { Text("Nutzungszugriff öffnen") }
                        }
                    }
                }
            }

            // M8.1: Permissions with helpful guidance (not error-colored)
            item {
                PermissionCardHelpful(
                    title = "Standort",
                    description = "Zum Konfigurieren von Orten und Übernehmen deiner aktuellen Position.",
                    granted = state.foregroundLocationGranted,
                    actionLabel = "Standort erlauben",
                    onAction = { foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                )
            }
            item {
                val bgDescription = if (Build.VERSION.SDK_INT >= 34) {
                    "Android 14+: Unter Einstellungen → Apps → Aevum → Standort → 'Immer erlauben' wählen."
                } else {
                    "Nötig, damit Geofences auch im Hintergrund erkannt werden."
                }
                PermissionCardHelpful(
                    title = "Hintergrundstandort",
                    description = bgDescription,
                    granted = state.backgroundLocationGranted,
                    actionLabel = "Einstellungen öffnen",
                    onAction = { viewModel.openBackgroundLocationSettings() }
                )
            }

            // Live data
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Daten", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("${state.geofenceCount} Geofences · ${state.triggerCount} Trigger · ${state.pendingCandidateCount} offene Vorschläge")
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Button(onClick = onOpenGeofences) { Text("Geofences") }
                            OutlinedButton(onClick = onOpenTriggers) { Text("Trigger") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }

        // M12.1: Sleep-Status Dialog
        sleepStatus?.let { status ->
            SleepStatusDialog(
                status = status,
                onDismiss = { viewModel.dismissSleepStatus() }
            )
        }

        // M14: Fusion-Status Dialog — zeigt, was die 3 Signal-Quellen sehen.
        fusionStatus?.let { status ->
            SleepFusionStatusDialog(
                status = status,
                onDismiss = { viewModel.dismissFusionStatus() }
            )
        }
    }
}

// ══════════════════════════════════════════════════════
// M8.1: Automation Status Dashboard (user-facing)
// ══════════════════════════════════════════════════════

@Composable
fun AutomationStatusScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: AutomationStatusViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { Header("Automatisierung Status", "Warum funktioniert die Erkennung — oder nicht?", onBack, null, {}) }

            // Overall readiness
            item {
                val ready = state.foregroundGranted && state.backgroundGranted
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            if (ready) "Automatisierung bereit" else "Automatisierung nicht vollständig",
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (ready) "Aevum erkennt Orte im Hintergrund und erzeugt Vorschläge."
                            else "Einige Voraussetzungen sind noch nicht erfüllt. Siehe Details unten.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Permission checks
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Berechtigungen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine("Standort (Vordergrund)", state.foregroundGranted)
                        StatusLine("Standort (Hintergrund)", state.backgroundGranted)
                        StatusLine("Benachrichtigungen", state.notificationsGranted)
                        StatusLine("Nutzungszugriff (Digital Balance)", state.usageStatsGranted)
                        StatusLine("Health Connect verbunden", state.healthConnectReady)
                    }
                }
            }

            // Geofence Status
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Geofences", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine("Registrierte Geofences", state.geofenceCount > 0)
                        if (state.geofenceCount > 0) Text("${state.geofenceCount} aktiv registriert", fontSize = 14.sp)
                        else Text("Keine Geofences registriert. Lege Zuhause oder Arbeit an.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusLine("Foreground Service", state.foregroundServiceRunning)
                    }
                }
            }

            // Activity
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Aktivität", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Trigger heute: ${state.triggersToday}", fontSize = 14.sp)
                        Text("Letzter Trigger: ${state.lastTriggerTime}", fontSize = 14.sp)
                        Text("Offene Vorschläge: ${state.pendingCandidates}", fontSize = 14.sp)
                        if (state.lastAutoActivity.isNotEmpty()) {
                            Text("Letzte automatische Aktivität: ${state.lastAutoActivity}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // M8.2: Diagnostic event log
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Diagnose", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine("System-Events heute", state.systemEventsToday > 0)
                        if (state.systemEventsToday > 0) Text("${state.systemEventsToday} Events vom System empfangen", fontSize = 13.sp)
                        StatusLine("Fehler heute", state.failuresToday == 0)
                        if (state.failuresToday > 0) Text("${state.failuresToday} Fehler — siehe Log", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Letztes Event: ${state.lastSystemEventType} · ${state.lastSystemEventTime}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Letzte Registrierung: ${state.lastRegistrationTime}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Recent event log
            if (state.recentLog.isNotEmpty()) {
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text("Letzte Ereignisse", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            state.recentLog.forEach { entry ->
                                val icon = if (entry.success) "✓" else "✗"
                                val color = if (entry.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                Text(
                                    "$icon ${entry.eventType}: ${entry.detail.take(80)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = color,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Action
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Button(onClick = viewModel::refreshAll) { Text("Alles prüfen") }
                            OutlinedButton(onClick = viewModel::reRegisterGeofences) { Text("Geofences neu registrieren") }
                        }
                        state.actionMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// GeofenceEditorScreen (M8.1: Simplified quick-setup)
// ══════════════════════════════════════════════════════

@Composable
fun GeofenceEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: GeofenceEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    val isNew = state.form.id == null
    val isQuickMode = state.form.quickKind != null

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item {
                Header(
                    if (isNew) "Geofence anlegen" else "Geofence bearbeiten",
                    if (isQuickMode) "Karte ausrichten, Radius wählen, speichern." else "Karte + aktuelle Position + Zuhause/Arbeit Presets",
                    onBack, "Speichern", viewModel::save
                )
            }

            // M8.1: Simplified quick setup — skip name/icon/color for Home/Work
            if (!isQuickMode) {
                item {
                    QuickSetupCard(
                        onQuick = viewModel::applyQuickSetup,
                        onCurrentLocation = viewModel::useCurrentLocation,
                        onRequestLocation = { foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        message = state.locationMessage
                    )
                }
            }

            // Map (always shown)
            item {
                MapLibreMapCard(
                    latitude = state.form.latitude,
                    longitude = state.form.longitude,
                    radius = state.form.radius,
                    onCenterChanged = viewModel::setCoordinates,
                    onRadiusChanged = viewModel::setRadius
                )
            }

            // M8.1: Quick mode — only radius + current location button
            if (isQuickMode) {
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                            Text(state.form.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.form.icon, fontSize = 48.sp)
                            Text("Karte passt du durch Verschieben an. Der Mittelpunkt ist dein Ort.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = viewModel::useCurrentLocation, modifier = Modifier.fillMaxWidth()) { Text("Meine aktuelle Position verwenden") }
                            state.locationMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
            } else {
                // Full editor fields
                item {
                    AevumCard(variant = CardVariant.Gradient) {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                            OutlinedTextField(state.form.name, viewModel::setName, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("Zuhause, Arbeit, Fitnessstudio…") })
                            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                OutlinedTextField(state.form.latitude, viewModel::setLatitude, modifier = Modifier.weight(1f), label = { Text("Latitude") })
                                OutlinedTextField(state.form.longitude, viewModel::setLongitude, modifier = Modifier.weight(1f), label = { Text("Longitude") })
                            }
                            OutlinedTextField(state.form.radius, viewModel::setRadius, modifier = Modifier.fillMaxWidth(), label = { Text("Radius (m)") })
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Aktiv"); Switch(state.form.enabled, viewModel::setEnabled)
                            }
                            state.form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            // Activity type + tags (only in full mode)
            if (!isQuickMode) {
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text("Aktivitätstyp", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                state.activityTypes.forEach { type ->
                                    FilterChip(
                                        selected = type.id == state.form.activityTypeId,
                                        onClick = { viewModel.setActivityType(type.id, type.defaultCategoryId) },
                                        label = { Text(type.name) }
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text("Tags", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                state.tags.forEach { tag ->
                                    FilterChip(
                                        selected = tag.id in state.form.selectedTagIds,
                                        onClick = { viewModel.toggleTag(tag.id) },
                                        label = { Text(tag.name) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // M11+: Automation section — proper, clear UI.
            // Lets the user pick:
            //  - Whether to auto-start anything when entering
            //  - Which activity type to start (separate from the "default" type)
            //  - Whether to auto-stop on exit
            item {
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Automatisierung", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Beim Betreten automatisch starten",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(state.form.autoEnabled, viewModel::setAutoEnabled)
                        }
                        if (state.form.autoEnabled) {
                            // P3: Drei typische Presets, die das UX vereinfachen.
                            // "Kein Timer" = gar nicht erst starten (Zuhause use-case).
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                            ) {
                                FilterChip(
                                    selected = state.form.autoStartActivityTypeId == null,
                                    onClick = { viewModel.setAutoStartActivityTypeId(null) },
                                    label = { Text("Nichts starten") }
                                )
                                state.activityTypes.take(6).forEach { type ->
                                    FilterChip(
                                        selected = state.form.autoStartActivityTypeId == type.id,
                                        onClick = { viewModel.setAutoStartActivityTypeId(type.id) },
                                        label = { Text(type.name) }
                                    )
                                }
                            }
                            Text(
                                if (state.form.autoStartActivityTypeId == null)
                                    "Beim Betreten passiert nichts — kein Timer läuft."
                                else
                                    "Beim Betreten wird \"${state.activityTypes.firstOrNull { it.id == state.form.autoStartActivityTypeId }?.name ?: "gewählte Aktivität"}\" als Vorschlag gestartet.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = AevumSpacing.xs),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Beim Verlassen automatisch beenden", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Aktivität endet beim Verlassen des Geofence",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(state.form.autoStopEnabled, viewModel::setAutoStopEnabled)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// Reusable components (M8.1: polished)
// ══════════════════════════════════════════════════════

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp)
        Text(
            if (ok) "✓" else "—",
            color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * M8.1: PermissionCard without error-color blame.
 * Uses neutral style — tells the user what's needed and how to fix it.
 */
@Composable
private fun PermissionCardHelpful(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (granted) "Erteilt" else "Ausstehend",
                    fontSize = 13.sp,
                    color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) {
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(if (Build.VERSION.SDK_INT >= 34 && title.contains("Hintergrund")) "Zu den Standorteinstellungen" else actionLabel)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// Quick Setup Card (unchanged)
// ══════════════════════════════════════════════════════

@Composable
private fun QuickSetupCard(
    onQuick: (QuickPlaceKind) -> Unit,
    onCurrentLocation: () -> Unit,
    onRequestLocation: () -> Unit,
    message: String?
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Schnell einrichten", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Profil wählen, aktuelle Position übernehmen, speichern.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = { onQuick(QuickPlaceKind.Home) }) { Text("🏠 Zuhause") }
                OutlinedButton(onClick = { onQuick(QuickPlaceKind.Work) }) { Text("💼 Arbeit") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = onCurrentLocation) { Text("Aktuelle Position") }
                OutlinedButton(onClick = onRequestLocation) { Text("Standort erlauben") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp) }
        }
    }
}

// ══════════════════════════════════════════════════════
// MapLibreMapCard (unchanged)
// ══════════════════════════════════════════════════════

@Composable
fun MapLibreMapCard(
    latitude: String,
    longitude: String,
    radius: String,
    onCenterChanged: (Double, Double) -> Unit,
    onRadiusChanged: (String) -> Unit
) {
    val lat = latitude.replace(',', '.').toDoubleOrNull() ?: 51.6167
    val lon = longitude.replace(',', '.').toDoubleOrNull() ?: 7.5167
    val radiusVal = radius.replace(',', '.').toFloatOrNull() ?: 150f

    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Karte", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Verschieben = Mittelpunkt setzen. Der ⌖ zeigt die ausgewählte Position.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            AevumMapView(
                latitude = lat, longitude = lon, radiusMeters = radiusVal,
                onCenterChanged = onCenterChanged,
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )

            Text("Radius: ${radiusVal.toInt()}m", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            androidx.compose.material3.Slider(
                value = radiusVal.coerceIn(50f, 2000f),
                onValueChange = { onRadiusChanged(it.toInt().toString()) },
                valueRange = 50f..2000f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ══════════════════════════════════════════════════════
// TriggerEventsScreen (unchanged)
// ══════════════════════════════════════════════════════

@Composable
fun TriggerEventsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: TriggerEventsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header("Trigger Events", "Gespeicherte Zeitpunkte aus Geofences", onBack, null, {}) }
            if (state.triggers.isEmpty()) item {
                EmptyState(title = "Noch keine Trigger", message = "Sobald ein Geofence ausgelöst wird, erscheint der Zeitpunkt hier.")
            }
            state.triggers.forEach { trigger -> item { TriggerRow(trigger, state.geofenceNames[trigger.geofenceId]?.name) } }
        }
    }
}

@Composable
private fun TriggerRow(trigger: TriggerEvent, geofenceName: String?) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
            // M11.2: lesbare Beschriftung mit Geofence-Name
            val isEnter = trigger.type.contains("ENTER") || trigger.type.contains("ARRIVED")
            val displayLabel = geofenceName?.let { if (isEnter) "$it betreten" else "$it verlassen" }
                ?: trigger.type.replace('_', ' ').lowercase().replaceFirstChar { c -> c.titlecase() }
            Text(displayLabel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            // M12.1.1: Datum + Zeit statt nur Zeit, damit klar ist,
            // an welchem Tag der Trigger ausgelöst wurde.
            Text(
                "${TimeFormatting.formatSmartDateTime(trigger.occurredAt)} · ${(trigger.confidence * 100).toInt()}% Konfidenz",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(trigger.source, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

// ══════════════════════════════════════════════════════
// Geofence Debug Screen (kept for developer reference)
// ══════════════════════════════════════════════════════

@Composable
fun GeofenceDebugScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: GeofenceDebugViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header("Diagnose", "Technische Details für Entwickler", onBack, null, {}) }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Berechtigungen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine("Foreground", state.foregroundLocationGranted)
                        StatusLine("Background", state.backgroundLocationGranted)
                        StatusLine("Notifications", state.notificationsGranted)
                    }
                }
            }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Daten", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Aktive Geofences: ${state.activeGeofences}")
                        Text("Trigger: ${state.triggerCount}")
                        Text("Candidates: ${state.pendingCandidates}")
                    }
                }
            }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Button(onClick = viewModel::refreshRegistration) { Text("Registrierung prüfen") }
                            OutlinedButton(onClick = viewModel::runRulesNow) { Text("Regeln prüfen") }
                        }
                    }
                }
            }
            if (state.debugLog.isNotEmpty()) {
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text("Debug-Log", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            state.debugLog.takeLast(20).reversed().forEach { entry ->
                                Text(
                                    "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))}] ${entry.tag}: ${entry.message}",
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// Shared components
// ══════════════════════════════════════════════════════

@Composable
fun Header(title: String, subtitle: String, onBack: () -> Unit, actionLabel: String?, onAction: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            TextButton(onClick = onBack) { Text("Zurück") }
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null) Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
        }
    }
}

@Composable
fun GeofenceListScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: GeofenceListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header("Geofences", "Orte, die Trigger und Vorschläge erzeugen", onBack, "Neu", onCreate) }
            if (state.geofences.isEmpty()) item {
                EmptyState(title = "Noch keine Orte", message = "Lege Zuhause, Arbeit oder Fitnessstudio an.", actionLabel = "Geofence anlegen", onActionClick = onCreate)
            }
            state.geofences.forEach { gf -> item { GeofenceRow(gf, onEdit, viewModel::delete) } }
        }
    }
}

@Composable
private fun GeofenceRow(geofence: PlaceGeofence, onEdit: (String) -> Unit, onDelete: (String) -> Unit) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("${geofence.icon} ${geofence.name}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("${geofence.latitude}, ${geofence.longitude} · ${geofence.radiusMeters.toInt()}m", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = { onEdit(geofence.id) }) { Text("Bearbeiten") }
                OutlinedButton(onClick = { onDelete(geofence.id) }) { Text("Löschen") }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// M12.1: Sleep-Status Dialog
// ══════════════════════════════════════════════════════

// ══════════════════════════════════════════════════════
// M14: Fusion-Status Dialog
// ══════════════════════════════════════════════════════

@Composable
private fun SleepFusionStatusDialog(
    status: de.devondroste.aevum.automation.sleep.SleepFusionStatus,
    onDismiss: () -> Unit
) {
    val dateFmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val stillHours = String.format("%.1f h", status.stillClusterHours)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schlaf-Fusion Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Drei Quellen, eine Schlaf-Erkennung", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = "Bildschirm-Events (14h-Fenster)",
                    value = "${status.screenEventCount}"
                )
                StatusRow(
                    label = "STILL-Cluster (Activity Recognition)",
                    value = stillHours
                )
                StatusRow(
                    label = "Stille im Digital-Balance-Log",
                    value = formatHm(status.digitalQuietMs)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text("Letzte Bildschirm-Events", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = "Display aus",
                    value = status.lastScreenOff?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = "Display an",
                    value = status.lastScreenOn?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )
}

private fun formatHm(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}min" else "${m}min"
}

@Composable
private fun SleepStatusDialog(
    status: de.devondroste.aevum.automation.sleep.SleepHeuristicStatus,
    onDismiss: () -> Unit
) {
    val timeFmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val dateFmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schlaf-Heuristik Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                StatusRow(
                    label = "Erfasste Bildschirm-Events",
                    value = "${status.eventCount}"
                )
                StatusRow(
                    label = "Letztes Display aus",
                    value = status.lastScreenOff?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = "Letzte Bildschirm-Aktivität",
                    value = status.lastScreenOn?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text("Geschätzter Schlaf", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = "Beginn",
                    value = status.estimatedSleepStart?.let { timeFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = "Ende",
                    value = status.estimatedSleepEnd?.let { timeFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = "Confidence",
                    value = status.estimatedConfidence?.let { "${(it * 100).toInt()}%" } ?: "—"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text("Zuletzt erzeugter Candidate", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = "Erstellt",
                    value = status.lastCandidateCreatedAt?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
                if (status.lastCandidateReason != null) {
                    Text(
                        status.lastCandidateReason,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
    }
}
