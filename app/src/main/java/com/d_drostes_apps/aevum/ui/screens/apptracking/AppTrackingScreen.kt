package com.d_drostes_apps.aevum.ui.screens.apptracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.disclosure.PermissionDisclosureDialog
import com.d_drostes_apps.aevum.ui.disclosure.PermissionDisclosureKind
import com.d_drostes_apps.aevum.ui.disclosure.rememberPermissionDisclosureGate
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.67: App-Aufzeichnung — Apps auswählen, die automatisch als
 * Activity aufgezeichnet werden.
 *
 * Layout (User-Spezifikation):
 *  - Oben: Suchleiste (Live-Suche über BEIDE Spalten)
 *  - Links: alle installierten Apps (nicht getrackt)
 *  - Rechts: Apps, die zur Aufzeichnung berücksichtigt werden
 *  - Klick auf eine App → wechselt die Spalte
 *  - 3-Punkte-Menü (rechts) → Activity zuordnen
 */
@Composable
fun AppTrackingScreen(
    onBack: () -> Unit,
    viewModel: AppTrackingViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // M20: Diagnose live halten, solange der Screen sichtbar ist (2-s-Takt) —
    // „läuft die Aufzeichnung GERADE?" ist die Frage, die dieser Screen
    // beantworten muss, wenn die automatische Aufzeichnung sich komisch
    // verhält.
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshDiagnostics()
            kotlinx.coroutines.delay(2_000L)
        }
    }

    // M18.147: Beim Öffnen still aktualisieren (im ViewModel gedrosselt) —
    // neu installierte Apps tauchen auf. Die Liste selbst füllt sich
    // chunkweise nach, während der Screen sofort bedienbar ist.
    LaunchedEffect(Unit) { viewModel.refreshInstalled() }

    // M23: Beim Zurückkehren aus den System-Einstellungen (Nutzungszugriff /
    // Akku-Ausnahme) den Status sofort neu lesen. Ohne diesen Resume-Hook
    // blieb der Screen auf dem Stand von vor dem Settings-Besuch.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // M18.145 (Play-Konsistenz): dieser Screen ist über den Balance-Tab
    // erreichbar und fragt denselben Nutzungszugriff an wie der
    // Trigger-Screen. Bis hierher führte der Button DIREKT in die
    // Systemeinstellungen — ohne Offenlegung in der App. Das war ein
    // Bypass um den M18.141-Dialog und damit genau die Beanstandung
    // („Anfragen … geht keine unmittelbare Offenlegung in der App voraus").
    val permissionGate = rememberPermissionDisclosureGate()

    permissionGate.pending?.let { pending ->
        PermissionDisclosureDialog(
            kind = pending.kind,
            requiresSettings = pending.requiresSettings,
            onAllow = { permissionGate.consent { viewModel.openUsageAccessSettings() } },
            onDecline = { permissionGate.dismiss() }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Kopfzeile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.apptracking_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.apptracking_subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!state.hasUsagePermission) {
            // M18.147: Erst wenn der Status wirklich feststeht, den Hinweis
            // zeigen — vorher (erste Frames) nichts, statt kurz aufzublitzen.
            if (!state.loading) {
                // M18.145: erst erklären, dann in die Systemseite (siehe oben).
                PermissionHint(
                    onOpenSettings = {
                        permissionGate.request(
                            PermissionDisclosureKind.USAGE_ACCESS,
                            alreadyGranted = false
                        ) { viewModel.openUsageAccessSettings() }
                    }
                )
            }
            return
        }

        // M20: Hintergrund & Diagnose — Status der automatischen Aufzeichnung,
        // Akku-Optimierungs-Ausnahme (der Hebel für zuverlässige Hintergrund-
        // Starts seit Android 12) und das Diagnose-Protokoll zum Kopieren.
        AppTrackingDiagnosticsCard(
            diagnostics = diagnostics,
            onOpenBattery = { viewModel.openBatterySettings() },
            onClear = { viewModel.clearDiagnostics() }
        )

        // Suchleiste
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.xs),
            placeholder = { Text(stringResource(R.string.apptracking_search_placeholder), fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.apptracking_clear_search), modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(AevumRadius.lg)
        )

        // Zwei Spalten
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = AevumSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            AppColumn(
                title = stringResource(R.string.apptracking_all_apps),
                subtitle = "${state.allApps.size}",
                apps = state.allApps,
                accent = MaterialTheme.colorScheme.primary,
                onAppClick = { viewModel.addApp(it.packageName) },
                onAssignActivity = null,
                activityTypes = state.activityTypes,
                progress = state.scanning,
                modifier = Modifier.weight(1f)
            )
            AppColumn(
                title = stringResource(R.string.apptracking_recording),
                subtitle = "${state.trackedApps.size}",
                apps = state.trackedApps,
                accent = MaterialTheme.colorScheme.tertiary,
                onAppClick = { viewModel.removeApp(it.packageName) },
                onAssignActivity = { app, typeId -> viewModel.assignActivity(app.packageName, typeId) },
                activityTypes = state.activityTypes,
                progress = state.scanning && state.trackedApps.isEmpty(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AppColumn(
    title: String,
    subtitle: String,
    apps: List<TrackableApp>,
    accent: Color,
    onAppClick: (TrackableApp) -> Unit,
    onAssignActivity: ((TrackableApp, String) -> Unit)?,
    activityTypes: List<ActivityType>,
    // M18.147: true, solange der App-Scan lädt — dezenter Spinner statt
    // "keine Ergebnisse" bzw. neben dem Zähler.
    progress: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Spalten-Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AevumSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(AevumSpacing.xs))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // M18.147: dezenter Fortschritt, solange die Liste nachwächst.
            if (progress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(AevumSpacing.xs))
            }
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AevumSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
                if (progress) {
                    // M18.147: Liste füllt sich gerade nach — Spinner statt
                    // "keine Ergebnisse".
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        if (title == stringResource(R.string.apptracking_recording)) stringResource(R.string.apptracking_tap_left_hint) else stringResource(R.string.apptracking_no_results),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AevumSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        accent = accent,
                        onClick = { onAppClick(app) },
                        onAssignActivity = onAssignActivity?.let { { typeId -> it(app, typeId) } },
                        activityTypes = activityTypes
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: TrackableApp,
    accent: Color,
    onClick: () -> Unit,
    onAssignActivity: ((String) -> Unit)?,
    activityTypes: List<ActivityType>
) {
    var menuOpen by remember { mutableStateOf(false) }

    AevumCard(
        variant = CardVariant.Elevated,
        contentPadding = PaddingValues(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App-Icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AevumRadius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (app.icon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Text(
                        app.appLabel.take(1).uppercase(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            // App-Name + zugeordnete Activity
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.appLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.activityTypeId != null) {
                    val typeName = activityTypes.firstOrNull { it.id == app.activityTypeId }?.name
                    if (typeName != null) {
                        Text(
                            typeName,
                            fontSize = 11.sp,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // 3-Punkte-Menü (nur in der rechten Spalte)
            if (onAssignActivity != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.apptracking_assign_activity),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Text(
                            stringResource(R.string.apptracking_assign_activity),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.xs)
                        )
                        HorizontalDivider()
                        activityTypes.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            type.icon ?: "•",
                                            fontSize = 14.sp,
                                            modifier = Modifier.width(24.dp)
                                        )
                                        Text(type.name, fontSize = 13.sp)
                                        if (type.id == app.activityTypeId) {
                                            Spacer(Modifier.width(AevumSpacing.sm))
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = stringResource(R.string.apptracking_selected),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    onAssignActivity(type.id)
                                }
                            )
                        }
                    }
                }
            } else {
                // Linke Spalte: dezenter Plus-Button als Affordanz
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.apptracking_add_to_recording),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun PermissionHint(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AevumSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.apptracking_permission_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(AevumSpacing.sm))
        Text(
            stringResource(R.string.apptracking_permission_desc),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AevumSpacing.lg)
        )
        Spacer(Modifier.height(AevumSpacing.md))
        androidx.compose.material3.Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.apptracking_permission_grant))
        }
    }
}

/**
 * M20: „Hintergrund & Diagnose"-Karte — Service-Zustand, letzter Poll,
 * Vordergrund-Schnappschuss, Akku-Optimierungs-Ausnahme (der entscheidende
 * Hebel für Hintergrund-Starts ab Android 12) und das Diagnose-Protokoll
 * (kopierbar — damit der nächste „klappt nicht"-Report mit Belegen kommt
 * statt mit Vermutungen).
 */
@Composable
private fun AppTrackingDiagnosticsCard(
    diagnostics: AppTrackingDiagnosticsState,
    onOpenBattery: () -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AevumCard(
        variant = CardVariant.Outlined,
        contentPadding = PaddingValues(AevumSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.xs)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.apptracking_diag_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.apptracking_diag_subtitle),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(if (expanded) R.string.apptracking_diag_hide else R.string.apptracking_diag_show),
                        fontSize = 12.sp
                    )
                }
            }

            DiagRow(
                label = stringResource(R.string.apptracking_diag_service),
                value = stringResource(
                    if (diagnostics.serviceAlive) R.string.apptracking_diag_active
                    else R.string.apptracking_diag_inactive
                ) + " · " + stringResource(
                    R.string.apptracking_diag_lastpoll,
                    diagnostics.pollAgeMs.let { age ->
                        when {
                            age < 0L -> stringResource(R.string.apptracking_diag_never)
                            age < 60_000L -> stringResource(R.string.apptracking_diag_age_seconds, (age / 1000).coerceAtLeast(0))
                            age < 3_600_000L -> stringResource(R.string.apptracking_diag_age_minutes, age / 60_000)
                            else -> stringResource(R.string.apptracking_diag_age_hours, age / 3_600_000)
                        }
                    }
                ),
                ok = diagnostics.serviceAlive
            )

            DiagRow(
                label = stringResource(R.string.apptracking_diag_foreground),
                value = diagnostics.snapshotPackage
                    ?: stringResource(R.string.apptracking_diag_foreground_none),
                ok = null
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // M23: Sichtbarer Status-Punkt — nach dem Erteilen der
                // Akku-Ausnahme springt er auf Grün (vorher war der Zustand
                // nur eine farbige Textzeile, kein „Punkt“).
                Box(
                    modifier = Modifier
                        .padding(end = AevumSpacing.sm)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            when (diagnostics.batteryExempt) {
                                true -> MaterialTheme.colorScheme.secondary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.apptracking_diag_battery),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(
                            when (diagnostics.batteryExempt) {
                                true -> R.string.apptracking_diag_battery_exempt
                                false -> R.string.apptracking_diag_battery_restricted
                                null -> R.string.apptracking_diag_battery_unknown
                            }
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = when (diagnostics.batteryExempt) {
                            true -> MaterialTheme.colorScheme.secondary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                TextButton(onClick = onOpenBattery) {
                    Text(stringResource(R.string.apptracking_diag_battery_button), fontSize = 11.sp)
                }
            }

            Text(
                stringResource(R.string.apptracking_diag_hint),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded) {
                Text(
                    stringResource(R.string.apptracking_diag_log),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (diagnostics.logLines.isEmpty()) {
                    Text(
                        stringResource(R.string.apptracking_diag_empty),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(AevumRadius.sm))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(AevumSpacing.sm)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            diagnostics.logLines.reversed().joinToString("\n"),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    TextButton(onClick = {
                        try {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "Aevum App-Aufzeichnung",
                                    diagnostics.logLines.joinToString("\n")
                                )
                            )
                            copied = true
                        } catch (_: Exception) {
                        }
                    }) {
                        Text(
                            stringResource(if (copied) R.string.apptracking_diag_copied else R.string.apptracking_diag_copy),
                            fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = {
                        copied = false
                        onClear()
                    }) {
                        Text(stringResource(R.string.apptracking_diag_clear), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, ok: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = when (ok) {
                true -> MaterialTheme.colorScheme.secondary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
