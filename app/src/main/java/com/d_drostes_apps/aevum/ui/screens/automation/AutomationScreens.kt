package com.d_drostes_apps.aevum.ui.screens.automation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.automation.geofence.GeofenceDebugLogger
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.AevumMapView
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

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
            item { Header(stringResource(R.string.automation_status_title), stringResource(R.string.automation_status_subtitle), onBack, null, {}) }

            // Overall readiness
            item {
                val ready = state.foregroundGranted && state.backgroundGranted
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            if (ready) stringResource(R.string.automation_ready) else stringResource(R.string.automation_not_fully_ready),
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (ready) stringResource(R.string.automation_ready_desc)
                            else stringResource(R.string.automation_not_ready_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Permission checks
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.common_permissions), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine(stringResource(R.string.automation_location_foreground), state.foregroundGranted)
                        StatusLine(stringResource(R.string.automation_location_background), state.backgroundGranted)
                        StatusLine(stringResource(R.string.settings_triggers_permission_notifications), state.notificationsGranted)
                        StatusLine(stringResource(R.string.automation_usage_access_digital), state.usageStatsGranted)
                        StatusLine(stringResource(R.string.automation_health_connect_connected), state.healthConnectReady)
                    }
                }
            }

            // Geofence Status
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.common_geofences), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine(stringResource(R.string.automation_registered_geofences), state.geofenceCount > 0)
                        if (state.geofenceCount > 0) Text(stringResource(R.string.automation_geofences_active_count, state.geofenceCount), fontSize = 14.sp)
                        else Text(stringResource(R.string.automation_no_geofences), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusLine(stringResource(R.string.automation_foreground_service), state.foregroundServiceRunning)
                    }
                }
            }

            // Activity
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.settings_triggers_group_movement), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.automation_triggers_today, state.triggersToday), fontSize = 14.sp)
                        Text(stringResource(R.string.automation_last_trigger, state.lastTriggerTime), fontSize = 14.sp)
                        Text(stringResource(R.string.automation_pending_suggestions, state.pendingCandidates), fontSize = 14.sp)
                        if (state.lastAutoActivity.isNotEmpty()) {
                            Text(stringResource(R.string.automation_last_auto_activity, state.lastAutoActivity), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // M8.2: Diagnostic event log
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.automation_diagnose_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine(stringResource(R.string.automation_system_events_today), state.systemEventsToday > 0)
                        if (state.systemEventsToday > 0) Text(stringResource(R.string.automation_events_received, state.systemEventsToday), fontSize = 13.sp)
                        StatusLine(stringResource(R.string.automation_failures_today), state.failuresToday == 0)
                        if (state.failuresToday > 0) Text(stringResource(R.string.automation_failures_count, state.failuresToday), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.automation_last_event, state.lastSystemEventType, state.lastSystemEventTime), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.automation_last_registration, state.lastRegistrationTime), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Recent event log
            if (state.recentLog.isNotEmpty()) {
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text(stringResource(R.string.automation_recent_events), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                            Button(onClick = viewModel::refreshAll) { Text(stringResource(R.string.automation_check_all)) }
                            OutlinedButton(onClick = viewModel::reRegisterGeofences) { Text(stringResource(R.string.automation_re_register_geofences)) }
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
                    if (isNew) stringResource(R.string.automation_geofence_new) else stringResource(R.string.automation_geofence_edit),
                    if (isQuickMode) stringResource(R.string.automation_quick_mode_subtitle) else stringResource(R.string.automation_full_mode_subtitle),
                    onBack, stringResource(R.string.common_save), viewModel::save
                )
            }

            // M8.1: Simplified quick setup — skip name/icon/color for Home/Work
            if (!isQuickMode) {
                item {
                    QuickSetupCard(
                        onQuick = viewModel::applyQuickSetup,
                        onCurrentLocation = viewModel::useCurrentLocation,
                        onRequestLocation = { foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        message = state.locationMessage,
                        // M18.60: "Standort erlauben" nur zeigen, wenn die
                        // Berechtigung noch nicht erteilt ist.
                        locationGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                            androidx.compose.ui.platform.LocalContext.current,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
                            Text(stringResource(R.string.automation_quick_mode_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = viewModel::useCurrentLocation, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.automation_use_current_position)) }
                            state.locationMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
            } else {
                // Full editor fields
                item {
                    AevumCard(variant = CardVariant.Gradient) {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                            OutlinedTextField(state.form.name, viewModel::setName, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.common_name)) }, placeholder = { Text(stringResource(R.string.automation_name_placeholder)) })
                            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                OutlinedTextField(state.form.latitude, viewModel::setLatitude, modifier = Modifier.weight(1f), label = { Text("Latitude") })
                                OutlinedTextField(state.form.longitude, viewModel::setLongitude, modifier = Modifier.weight(1f), label = { Text("Longitude") })
                            }
                            OutlinedTextField(state.form.radius, viewModel::setRadius, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.automation_radius_m)) })
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.common_active)); Switch(state.form.enabled, viewModel::setEnabled)
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
                            Text(stringResource(R.string.automation_activity_type), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                            Text(stringResource(R.string.common_icon), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.automation_choose_icon_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // M18.60 (User: "Ein icon soll vergeben werden
                            // können für geofences. Aus einer Vorauswahl,
                            // nicht zum selber eintippen. Eine große Auswahl
                            // bitte."): 40 Emoji-Icons.
                            // M18.61 (User: "die icons sollten nicht alle in
                            // einer horizontalen scroll leiste sein, lieber
                            // in einem pop up fenster auswählbar damit man
                            // den überblick behält"): Button + Popup-Dialog
                            // mit Grid statt LazyRow.
                            var showIconPicker by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { showIconPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${state.form.icon.ifBlank { "📍" }}  ${stringResource(R.string.automation_choose_icon)}",
                                    fontSize = 14.sp
                                )
                            }
                            if (showIconPicker) {
                                GeofenceIconPickerDialog(
                                    current = state.form.icon,
                                    onPick = { icon ->
                                        viewModel.setIcon(icon)
                                        showIconPicker = false
                                    },
                                    onDismiss = { showIconPicker = false }
                                )
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
                                Text(stringResource(R.string.settings_triggers_additional), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.automation_auto_start_on_enter),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(state.form.autoEnabled, viewModel::setAutoEnabled)
                        }
                        if (state.form.autoEnabled) {
                            // M18.66-FIX20 (User: "Ein geofence braucht nicht
                            // für die Automatisierung separat einen Activity
                            // Type. Es reicht, pro Geofence einmal einen
                            // Activity Type anzugeben, und wenn Automatisierung
                            // aktiviert wird, wird dieser Activity Type
                            // verwendet."): KEIN separater Auto-Start-Picker
                            // mehr. Die Automatisierung nutzt den oben
                            // gewählten Aktivitätstyp (activityTypeId).
                            val autoTypeName = state.activityTypes
                                .firstOrNull { it.id == (state.form.activityTypeId ?: state.form.autoStartActivityTypeId) }
                                ?.name
                            Text(
                                if (autoTypeName != null)
                                    stringResource(R.string.automation_auto_start_type, autoTypeName)
                                else
                                    stringResource(R.string.automation_auto_start_choose_type),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = AevumSpacing.xs),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.automation_auto_stop_on_exit), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        stringResource(R.string.automation_auto_stop_desc),
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
    message: String?,
    // M18.60: "Standort erlauben" nur zeigen, wenn nicht erteilt.
    locationGranted: Boolean = false
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(stringResource(R.string.automation_quick_setup), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.automation_quick_setup_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = { onQuick(QuickPlaceKind.Home) }) { Text("🏠 ${stringResource(R.string.common_home)}") }
                OutlinedButton(onClick = { onQuick(QuickPlaceKind.Work) }) { Text("💼 ${stringResource(R.string.common_work)}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = onCurrentLocation) { Text(stringResource(R.string.automation_current_position)) }
                // M18.60: Button nur anzeigen, wenn die Berechtigung fehlt.
                if (!locationGranted) {
                    OutlinedButton(onClick = onRequestLocation) { Text(stringResource(R.string.common_allow_location)) }
                }
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
            Text(stringResource(R.string.automation_map), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.automation_map_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            AevumMapView(
                latitude = lat, longitude = lon, radiusMeters = radiusVal,
                onCenterChanged = onCenterChanged,
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )

            Text(stringResource(R.string.automation_radius_value, radiusVal.toInt()), fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
    // M18.49 (User: "Beim löschen von Triggern in den Einstellungen sollte
    // noch ein Bestätigungsdialog erscheinen"): Löschen erst nach
    // expliziter Bestätigung — der Trash-Button setzt nur pendingDeleteId.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header(stringResource(R.string.automation_trigger_events), stringResource(R.string.automation_trigger_events_subtitle), onBack, null, {}) }
            if (state.triggers.isEmpty()) item {
                EmptyState(title = stringResource(R.string.automation_no_triggers), message = stringResource(R.string.automation_no_triggers_desc))
            }
            state.triggers.forEach { trigger -> item { TriggerRow(trigger, state.geofenceNames[trigger.geofenceId]?.name, onDelete = { pendingDeleteId = trigger.id }) } }
        }
    }

    // M18.49: Sicherheitsdialog vor dem Löschen eines Triggers.
    pendingDeleteId?.let { id ->
        val trigger = state.triggers.firstOrNull { it.id == id }
        val geofenceName = trigger?.let { state.geofenceNames[it.geofenceId]?.name }
        val isEnter = trigger?.type?.contains("ENTER") == true || trigger?.type?.contains("ARRIVED") == true
        val displayLabel = geofenceName?.let {
            if (isEnter) stringResource(R.string.automation_trigger_entered, it)
            else stringResource(R.string.automation_trigger_left, it)
        }
            ?: trigger?.type?.replace('_', ' ')?.lowercase()?.replaceFirstChar { c -> c.titlecase() }
            ?: stringResource(R.string.automation_this_trigger)
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.automation_delete_trigger_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.automation_delete_trigger_body,
                        displayLabel,
                        trigger?.let { TimeFormatting.formatSmartDateTime(it.occurredAt) } ?: stringResource(R.string.automation_unknown_time)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(id)
                        pendingDeleteId = null
                    }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun TriggerRow(trigger: TriggerEvent, geofenceName: String?, onDelete: (String) -> Unit) {
    AevumCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                // M11.2: lesbare Beschriftung mit Geofence-Name
                val isEnter = trigger.type.contains("ENTER") || trigger.type.contains("ARRIVED")
                val displayLabel = geofenceName?.let {
                    if (isEnter) stringResource(R.string.automation_trigger_entered, it)
                    else stringResource(R.string.automation_trigger_left, it)
                }
                    ?: trigger.type.replace('_', ' ').lowercase().replaceFirstChar { c -> c.titlecase() }
                Text(displayLabel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                // M12.1.1: Datum + Zeit statt nur Zeit, damit klar ist,
                // an welchem Tag der Trigger ausgelöst wurde.
                Text(
                    "${TimeFormatting.formatSmartDateTime(trigger.occurredAt)} · ${stringResource(R.string.automation_confidence, (trigger.confidence * 100).toInt())}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(trigger.source, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            // M18.48: Trigger löschen (User-Anforderung). Ein Trash-Button
            // mit Bestätigungsdialog entfernt den Trigger dauerhaft.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .clickable { onDelete(trigger.id) },
                contentAlignment = Alignment.Center
            ) {
                Text("🗑", fontSize = 16.sp)
            }
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
            item { Header(stringResource(R.string.automation_diagnose_title), stringResource(R.string.automation_diagnose_subtitle), onBack, null, {}) }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.common_permissions), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusLine("Foreground", state.foregroundLocationGranted)
                        StatusLine("Background", state.backgroundLocationGranted)
                        StatusLine("Notifications", state.notificationsGranted)
                    }
                }
            }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.automation_data), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.automation_active_geofences, state.activeGeofences))
                        Text(stringResource(R.string.automation_triggers_count, state.triggerCount))
                        Text(stringResource(R.string.automation_candidates_count, state.pendingCandidates))
                    }
                }
            }
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Button(onClick = viewModel::refreshRegistration) { Text(stringResource(R.string.automation_check_registration)) }
                            OutlinedButton(onClick = viewModel::runRulesNow) { Text(stringResource(R.string.automation_check_rules)) }
                        }
                    }
                }
            }
            if (state.debugLog.isNotEmpty()) {
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text(stringResource(R.string.automation_debug_log), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
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
    // M18.66-FIX20: Bestätigungsdialog vor dem Löschen
    var pendingDelete by remember { mutableStateOf<PlaceGeofence?>(null) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            item { Header(stringResource(R.string.automation_geofences_title), stringResource(R.string.automation_geofences_subtitle), onBack, stringResource(R.string.automation_new), onCreate) }
            // M18.66-FIX20: Suchleiste mit Live-Suche
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.automation_search_placeholder)) },
                    leadingIcon = { Text("🔍", fontSize = 16.sp) },
                    singleLine = true
                )
            }
            if (state.geofences.isEmpty()) item {
                EmptyState(
                    title = if (state.query.isBlank()) stringResource(R.string.automation_no_places) else stringResource(R.string.automation_no_results),
                    message = if (state.query.isBlank()) stringResource(R.string.automation_no_places_desc) else stringResource(R.string.automation_no_results_desc, state.query),
                    actionLabel = if (state.query.isBlank()) stringResource(R.string.automation_create_geofence) else null,
                    onActionClick = onCreate
                )
            }
            state.geofences.forEach { gf -> item { GeofenceRow(gf, onEdit, viewModel::setEnabled, { pendingDelete = gf }) } }
        }
    }
    // M18.66-FIX20: Lösch-Bestätigung
    pendingDelete?.let { gf ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.automation_delete_geofence_title)) },
            text = { Text(stringResource(R.string.automation_delete_geofence_body, gf.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(gf.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun GeofenceRow(
    geofence: PlaceGeofence,
    onEdit: (String) -> Unit,
    onEnabledChange: (PlaceGeofence, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("${geofence.icon} ${geofence.name}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("${geofence.latitude}, ${geofence.longitude} · ${geofence.radiusMeters.toInt()}m", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Switch(checked = geofence.enabled, onCheckedChange = { onEnabledChange(geofence, it) })
                Text(if (geofence.enabled) stringResource(R.string.common_active) else stringResource(R.string.automation_geofence_disabled), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = { onEdit(geofence.id) }) { Text(stringResource(R.string.common_edit)) }
                OutlinedButton(onClick = { onDelete(geofence.id) }) { Text(stringResource(R.string.common_delete)) }
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
fun SleepFusionStatusDialog(
    status: com.d_drostes_apps.aevum.automation.sleep.SleepFusionStatus,
    onDismiss: () -> Unit
) {
    val dateFmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val stillHours = String.format("%.1f h", status.stillClusterHours)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automation_sleep_fusion_status)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text(stringResource(R.string.automation_sleep_fusion_subtitle), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = stringResource(R.string.automation_screen_events_14h),
                    value = "${status.screenEventCount}"
                )
                StatusRow(
                    label = stringResource(R.string.automation_still_cluster),
                    value = stillHours
                )
                StatusRow(
                    label = stringResource(R.string.automation_digital_quiet),
                    value = formatHm(status.digitalQuietMs)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(stringResource(R.string.automation_last_screen_events), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = stringResource(R.string.automation_display_off),
                    value = status.lastScreenOff?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = stringResource(R.string.automation_display_on),
                    value = status.lastScreenOn?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
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
fun SleepStatusDialog(
    status: com.d_drostes_apps.aevum.automation.sleep.SleepHeuristicStatus,
    onDismiss: () -> Unit
) {
    val timeFmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val dateFmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automation_sleep_heuristic_status)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                StatusRow(
                    label = stringResource(R.string.automation_captured_screen_events),
                    value = "${status.eventCount}"
                )
                StatusRow(
                    label = stringResource(R.string.automation_last_display_off),
                    value = status.lastScreenOff?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = stringResource(R.string.automation_last_screen_activity),
                    value = status.lastScreenOn?.let { dateFmt.format(java.util.Date(it)) } ?: "—"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(stringResource(R.string.automation_estimated_sleep), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = stringResource(R.string.automation_start),
                    value = status.estimatedSleepStart?.let { timeFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = stringResource(R.string.automation_end),
                    value = status.estimatedSleepEnd?.let { timeFmt.format(java.util.Date(it)) } ?: "—"
                )
                StatusRow(
                    label = "Confidence",
                    value = status.estimatedConfidence?.let { "${(it * 100).toInt()}%" } ?: "—"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(stringResource(R.string.automation_last_candidate), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StatusRow(
                    label = stringResource(R.string.automation_created),
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
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

/**
 * M18.60: Große Icon-Vorauswahl für Geofences (User: "Aus einer
 * Vorauswahl, nicht zum selber eintippen. Eine große Auswahl bitte.").
 * 40 Emoji — Orte, Aktivitäten, Alltag.
 */
private val GeofenceIconChoices: List<String> = listOf(
    "🏠", "💼", "🏢", "🏫", "🎓", "🏋️", "🏃", "🚴", "🏊", "⚽",
    "🏀", "🎾", "⛳", "🧘", "🛒", "🏪", "🍽️", "☕", "🍺", "🎬",
    "🎮", "🎵", "📚", "💻", "🛏️", "🚿", "🧹", "🌳", "🏖️", "⛰️",
    "🚗", "🚌", "🚆", "✈️", "⛽", "🏥", "💊", "✂️", "🐕", "👨‍👩‍👧"
)

/**
 * M18.61 (User: "lieber in einem pop up fenster auswählbar damit man
 * den überblick behält"): Popup-Dialog mit Grid statt horizontaler
 * Scroll-Leiste. 5 Spalten, scrollbar bei Bedarf, aktuelle Auswahl
 * markiert.
 */
@Composable
private fun GeofenceIconPickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automation_choose_icon), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.automation_choose_icon_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AevumSpacing.sm))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    items(GeofenceIconChoices.chunked(5)) { rowIcons ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                        ) {
                            rowIcons.forEach { icon ->
                                val selected = icon == current
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(AevumRadius.md))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                        .clickable { onPick(icon) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(icon, fontSize = 24.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
