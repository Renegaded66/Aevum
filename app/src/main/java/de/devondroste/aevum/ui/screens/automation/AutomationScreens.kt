package de.devondroste.aevum.ui.screens.automation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.automation.geofence.GeofenceDebugLogger
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.AevumMapView
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme
import java.util.Locale

@Composable
fun AutomationSettingsScreen(
    modifier: Modifier = Modifier,
    onOpenGeofences: () -> Unit,
    onOpenTriggers: () -> Unit,
    onOpenDebug: () -> Unit,
    viewModel: AutomationSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshGeofences() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshGeofences() }
    val context = LocalContext.current

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item {
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                        Text("Automatisierung", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                        Text("Aevum erkennt Orte nur, wenn du es ausdrücklich aktivierst. Jede Erkennung bleibt als Trigger und Candidate nachvollziehbar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingSwitch("Hintergrunderfassung", "Batteriesparend über Android Geofencing", state.settings.backgroundCaptureEnabled, viewModel::setBackgroundCapture)
                        SettingSwitch("Review-Hinweise", "Zurückhaltende Benachrichtigung nur bei neuen überprüfbaren Vorschlägen", state.settings.reviewNotificationsEnabled, viewModel::setReviewNotifications)
                        state.registrationMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
            // M8: Health Connect
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Health Connect", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Schlafdaten automatisch auslesen. Alle Daten bleiben lokal.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingSwitch("Schlaf erkennen", "Schlafsessions aus Health Connect als Vorschläge importieren", state.settings.healthSleepEnabled, viewModel::setHealthSleep)
                    }
                }
            }
            // M8: Digital Balance
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Digital Balance", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Bildschirmzeit und App-Nutzung erfassen. Analytics, keine Vorschläge.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingSwitch("Bildschirmzeit erfassen", "Tägliche Smartphone-Nutzung anonymisiert analysieren", state.settings.digitalBalanceEnabled, viewModel::setDigitalBalance)
                        if (!state.usageStatsGranted && state.settings.digitalBalanceEnabled) {
                            TextButton(onClick = { viewModel.openUsageAccess() }) { Text("Nutzungszugriff öffnen") }
                        }
                    }
                }
            }
            item { PermissionCard("Standort", "Erlaubt Aevum, Orte im Vordergrund zu konfigurieren und aktuelle Position zu übernehmen.", state.foregroundLocationGranted) { foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } }
            item { PermissionCard("Hintergrundstandort", "Nötig, damit Geofences auch erkannt werden, wenn Aevum geschlossen ist.", state.backgroundLocationGranted) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }
            if (Build.VERSION.SDK_INT >= 33) item { PermissionCard("Benachrichtigungen", "Optional für Review-Hinweise. Aevum funktioniert auch ohne.", state.notificationsGranted) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } }
            item {
                AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Live-Status", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text("${state.geofenceCount} Geofences · ${state.triggerCount} Trigger · ${state.pendingCandidateCount} offene Candidates"); Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Button(onClick = onOpenGeofences) { Text("Geofences") }; OutlinedButton(onClick = onOpenTriggers) { Text("Trigger") }; OutlinedButton(onClick = onOpenDebug) { Text("Diagnose") } } } }
            }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionCard(title: String, description: String, granted: Boolean, onAction: () -> Unit) {
    AevumCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (granted) "Aktiv" else "Nicht erteilt", color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) }
            if (!granted) Button(onClick = onAction) { Text("Erklären & öffnen") }
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
            item { Header("Geofences", "Orte, die Trigger und Candidates erzeugen", onBack, "Neu", onCreate) }
            if (state.geofences.isEmpty()) item {
                EmptyState(
                    title = "Noch keine Orte",
                    message = "Lege Zuhause, Arbeit oder Fitnessstudio als ersten Geofence an.",
                    actionLabel = "Geofence anlegen",
                    onActionClick = onCreate
                )
            }
            state.geofences.forEach { geofence ->
                item { GeofenceRow(geofence, onEdit, viewModel::delete) }
            }
        }
    }
}

@Composable
private fun GeofenceRow(geofence: PlaceGeofence, onEdit: (String) -> Unit, onDelete: (String) -> Unit) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("${geofence.icon} ${geofence.name}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); AssistChip(onClick = {}, label = { Text(if (geofence.enabled) "Aktiv" else "Inaktiv") }) }
            Text("${geofence.latitude}, ${geofence.longitude} · ${geofence.radiusMeters.toInt()}m", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Button(onClick = { onEdit(geofence.id) }) { Text("Bearbeiten") }; OutlinedButton(onClick = { onDelete(geofence.id) }) { Text("Löschen") } }
        }
    }
}

@Composable
fun GeofenceEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: GeofenceEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header(if (state.form.id == null) "Geofence anlegen" else "Geofence bearbeiten", "Karte (Canvas) + aktuelle Position + Zuhause/Arbeit Presets", onBack, "Speichern", viewModel::save) }
            item { QuickSetupCard(viewModel::applyQuickSetup, viewModel::useCurrentLocation, { foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, state.locationMessage) }
            item { MapLibreMapCard(latitude = state.form.latitude, longitude = state.form.longitude, radius = state.form.radius, onCenterChanged = viewModel::setCoordinates, onRadiusChanged = viewModel::setRadius) }
            item { AevumCard(variant = CardVariant.Gradient) { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) { OutlinedTextField(state.form.name, viewModel::setName, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("Zuhause, Arbeit, Fitnessstudio…") }); Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { OutlinedTextField(state.form.icon, viewModel::setIcon, modifier = Modifier.weight(1f), label = { Text("Icon") }); OutlinedTextField(state.form.color, viewModel::setColor, modifier = Modifier.weight(2f), label = { Text("Farbe") }) }; Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { OutlinedTextField(state.form.latitude, viewModel::setLatitude, modifier = Modifier.weight(1f), label = { Text("Latitude") }); OutlinedTextField(state.form.longitude, viewModel::setLongitude, modifier = Modifier.weight(1f), label = { Text("Longitude") }) }; OutlinedTextField(state.form.radius, viewModel::setRadius, modifier = Modifier.fillMaxWidth(), label = { Text("Radius Meter") }); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Aktiv"); Switch(state.form.enabled, viewModel::setEnabled) }; state.form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } } }
            item { AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Zugehörige Aktivität", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { state.activityTypes.forEach { type -> FilterChip(selected = type.id == state.form.activityTypeId, onClick = { viewModel.setActivityType(type.id, type.defaultCategoryId) }, label = { Text(type.name) }) } } } } }
            item { AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Tags", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { state.tags.forEach { tag -> FilterChip(selected = tag.id in state.form.selectedTagIds, onClick = { viewModel.toggleTag(tag.id) }, label = { Text(tag.name) }) } } } } }
        }
    }
}

@Composable
private fun QuickSetupCard(onQuick: (QuickPlaceKind) -> Unit, onCurrentLocation: () -> Unit, onRequestLocation: () -> Unit, message: String?) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Schnell einrichten", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Wenige Schritte: Profil wählen, aktuelle Position übernehmen, Radius prüfen, speichern.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = { onQuick(QuickPlaceKind.Home) }) { Text("🏠 Zuhause") }
                OutlinedButton(onClick = { onQuick(QuickPlaceKind.Work) }) { Text("💼 Arbeit") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = onCurrentLocation) { Text("Aktuelle Position") }
                OutlinedButton(onClick = onRequestLocation) { Text("Standort erlauben") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

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
            Text("Karte (MapLibre / OpenStreetMap)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Karte verschieben = Mittelpunkt setzen. Pinch = Zoomen.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            AevumMapView(
                latitude = lat,
                longitude = lon,
                radiusMeters = radiusVal,
                onCenterChanged = onCenterChanged,
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )

            // Radius slider
            Text("Radius: ${radiusVal.toInt()}m", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            androidx.compose.material3.Slider(
                value = radiusVal.coerceIn(50f, 2000f),
                onValueChange = { newRadius ->
                    onRadiusChanged(newRadius.toInt().toString())
                },
                valueRange = 50f..2000f,
                steps = (2000 - 50) / 10,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun TriggerEventsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: TriggerEventsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header("Trigger Events", "Gespeicherte einzelne Zeitpunkte aus Geofences", onBack, null, {}) }
            if (state.triggers.isEmpty()) item {
                EmptyState(
                    title = "Noch keine Trigger",
                    message = "Sobald ein Geofence ausgelöst wird, erscheint der Zeitpunkt hier und in der Timeline."
                )
            }
            state.triggers.forEach { trigger ->
                item { TriggerRow(trigger, state.geofenceNames[trigger.geofenceId]?.name) }
            }
        }
    }
}

@Composable
private fun TriggerRow(trigger: TriggerEvent, geofenceName: String?) {
    AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) { Text(trigger.type, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("${TimeFormatting.formatTime(trigger.occurredAt)} · ${geofenceName ?: "kein Ort"} · ${(trigger.confidence * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(trigger.source, fontFamily = FontFamily.Monospace, fontSize = 12.sp); trigger.metadataJson?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}

@Composable
fun GeofenceDebugScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: GeofenceDebugViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header("Geofence Diagnose", "Transparenz statt Magie: Berechtigungen, Registrierung, Regeln", onBack, null, {}) }
            item { AevumCard(variant = CardVariant.Gradient) { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text(if (state.geofenceReady) "System bereit" else "Noch nicht vollständig bereit", fontSize = 24.sp, fontWeight = FontWeight.SemiBold); DebugLine("Foreground Standort", state.foregroundLocationGranted); DebugLine("Background Standort", state.backgroundLocationGranted); DebugLine("Benachrichtigungen", state.notificationsGranted); DebugLine("Review Hinweise aktiv", state.settings.reviewNotificationsEnabled) } } }
            item { AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Datenlage", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text("Aktive Geofences: ${state.activeGeofences}"); Text("Inaktive Geofences: ${state.inactiveGeofences}"); Text("Trigger Events: ${state.triggerCount}"); Text("Offene Candidates: ${state.pendingCandidates}") } } }
            item { AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Aktionen", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Button(onClick = viewModel::refreshRegistration) { Text("Registrierung prüfen") }; OutlinedButton(onClick = viewModel::runRulesNow) { Text("Regeln prüfen") } }; state.lastAction?.let { Text(it, color = MaterialTheme.colorScheme.secondary) } } } }
            item {
                if (state.debugLog.isNotEmpty()) {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text("Debug-Log (${state.debugLog.size} Einträge)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            state.debugLog.takeLast(20).reversed().forEach { entry ->
                                Text(
                                    "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))}] ${entry.tag}: ${entry.message}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugLine(label: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(if (ok) "OK" else "Fehlt", color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit, actionLabel: String?, onAction: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) { TextButton(onClick = onBack) { Text("Zurück") }; Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant); if (actionLabel != null) Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) } } }
}