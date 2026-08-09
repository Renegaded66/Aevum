package com.d_drostes_apps.aevum.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.automation.geofence.GeofenceRegistrar
import com.d_drostes_apps.aevum.automation.sleep.SleepFusionEngine
import com.d_drostes_apps.aevum.automation.garmin.GarminSyncScheduler
import com.d_drostes_apps.aevum.automation.sleep.SleepFusionStatus
import com.d_drostes_apps.aevum.automation.sleep.SleepFusionWorker
import com.d_drostes_apps.aevum.automation.sleep.SleepHeuristicEngine
import com.d_drostes_apps.aevum.automation.sleep.SleepHeuristicStatus
import com.d_drostes_apps.aevum.data.garmin.GarminApiClient
import com.d_drostes_apps.aevum.data.garmin.GarminStatus
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.domain.digital.UsageStatsCollector
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.screens.automation.AutomationScrollSignal
import com.d_drostes_apps.aevum.ui.screens.automation.SleepFusionStatusDialog
import com.d_drostes_apps.aevum.ui.screens.automation.SleepStatusDialog
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.57: Trigger & Erkennung — die FUSIONIERTE Einstellungsseite.
 *
 * Vorher gab es zwei Seiten:
 *   1. "Trigger & Erkennung" (nur Toggles, keine Berechtigungen)
 *   2. "Berechtigungen" / Automation (Status + Permission-Cards + Health/Digital)
 *
 * Jetzt gibt es NUR NOCH diese eine Seite. Der User entscheidet pro
 * Trigger-Quelle, ob sie aktiv ist, und die benötigten Berechtigungen
 * werden direkt hier verwaltet:
 *
 *   🏠 Geofences          — braucht Standort (Vordergrund) + Hintergrund
 *                           ("Immer erlauben" — Geofences müssen auch bei
 *                           geschlossener App funktionieren)
 *   🚗 Autofahren          — braucht Aktivitätserkennung
 *   🚶 Walking / Laufen    — braucht Aktivitätserkennung
 *   🚴 Radfahren           — braucht Aktivitätserkennung
 *   🌙 Schlaf-Erkennung    — braucht Aktivitätserkennung (STILL-Signal)
 *
 * Permission-Gating (M18.57): Bei frischer Installation sind keine
 * Berechtigungen erteilt → alle Trigger, die Berechtigungen brauchen,
 * sind DEAKTIVIERT (Switch aus, dezent roter Hinweis "Berechtigung noch
 * nicht erteilt"). Tippt man auf einen blockierten Trigger, wird man
 * zuerst zum Berechtigungs-Flow geleitet. Erst wenn die Berechtigung
 * erteilt ist, springt der Schieberegler auf aktiviert und der Hinweis
 * wird grün ("Berechtigung erteilt").
 *
 * Die DB-Flags bleiben dabei unverändert (keine Migration nötig): Der
 * Switch zeigt `flag && permissionGranted`. Wird die Berechtigung später
 * entzogen, fällt der Trigger sichtbar zurück; nach erneuter Erteilung
 * springt er wieder an.
 */
@Composable
fun TriggerSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenGeofences: () -> Unit = {},
    onOpenTriggers: () -> Unit = {},
    onOpenStatus: () -> Unit = {},
    viewModel: TriggerSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // M18.57: Welcher Trigger wartet gerade auf seine Berechtigung?
    // Nach erfolgreicher Erteilung wird genau dieser Trigger aktiviert.
    var pendingTrigger by remember { mutableStateOf<String?>(null) }

    fun openAppDetails() {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e: Exception) {
            Log.e("TriggerSettings", "App-Details öffnen fehlgeschlagen", e)
        }
    }

    // ── Permission-Launcher ──────────────────────────────────────────
    // Standort (Vordergrund): Geofences. Nach Grant ggf. weiter zu den
    // App-Details, damit der User "Immer erlauben" (Hintergrund) wählen kann.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            val fresh = viewModel.uiState.value
            if (!fresh.backgroundLocationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Hintergrund-Standort ist KEINE Runtime-Permission — der User
                // muss "Immer erlauben" in den App-Details wählen.
                openAppDetails()
            } else if (pendingTrigger == "geofences") {
                viewModel.setGeofencing(true)
                pendingTrigger = null
            }
        }
        viewModel.refreshPermissions()
    }

    // Aktivitätserkennung: Autofahren, Walking, Radfahren, Schlaf-Fusion
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingTrigger) {
                "driving" -> viewModel.setDriving(true)
                "walking" -> viewModel.setWalking(true)
                "bicycle" -> viewModel.setBicycle(true)
                // M18.58: "sleep" nicht mehr — die Schlaf-Quelle wird über
                // die SleepSourceCard (sleepSource) gewählt, nicht über
                // einen AR-Permission-Trigger.
            }
            pendingTrigger = null
        }
        viewModel.refreshPermissions()
    }

    // Benachrichtigungen (nur Status-Zeile, kein Trigger-Gate)
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    // M18.57: Bei Rückkehr aus den System-Einstellungen (z.B. nach
    // "Immer erlauben") den Permission-Status neu prüfen und einen
    // wartenden Geofence-Trigger abschließen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
                val fresh = viewModel.uiState.value
                if (pendingTrigger == "geofences" &&
                    fresh.foregroundLocationGranted && fresh.backgroundLocationGranted
                ) {
                    viewModel.setGeofencing(true)
                    pendingTrigger = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // M12.1: Scroll-Signal vom Dashboard ("Bildschirmzeit aktivieren")
    // → zur Digital-Balance-Sektion scrollen.
    val listState = rememberLazyListState()
    val scrollToUsage = AutomationScrollSignal.consumeScrollToUsage()
    LaunchedEffect(scrollToUsage) {
        if (scrollToUsage) listState.animateScrollToItem(DIGITAL_BALANCE_ITEM_INDEX)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { TriggerSettingsHero(onBack) }

            // ── Berechtigungen (fusioniert) ──────────────────────────
            item {
                PermissionStatusCard(
                    foregroundGranted = state.foregroundLocationGranted,
                    backgroundGranted = state.backgroundLocationGranted,
                    activityRecognitionGranted = state.activityRecognitionGranted,
                    notificationsGranted = state.notificationsGranted,
                    usageStatsGranted = state.usageStatsGranted,
                    onRequestForeground = {
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    onRequestBackground = { openAppDetails() },
                    onRequestActivityRecognition = {
                        activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenUsageAccess = { viewModel.openUsageAccess() }
                )
            }

            // ── Ortsbasiert ──────────────────────────────────────────
            item {
                val geofenceBlocked = !state.foregroundLocationGranted || !state.backgroundLocationGranted
                TriggerGroup(
                    title = "Ortsbasiert",
                    items = listOf(
                        TriggerToggle(
                            icon = "📍",
                            title = "Geofences",
                            description = "Zuhause, Arbeit & Co. erkennen — Betreten startet, Verlassen stoppt die Aktivität",
                            accent = Color(0xFF4F9CF9),
                            checked = state.settings.geofencingEnabled,
                            permissionGranted = !geofenceBlocked,
                            onCheckedChange = viewModel::setGeofencing,
                            onRequestPermission = {
                                pendingTrigger = "geofences"
                                if (!state.foregroundLocationGranted) {
                                    locationLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else {
                                    openAppDetails()
                                }
                            }
                        )
                    )
                )
            }

            // ── Bewegung ─────────────────────────────────────────────
            item {
                val arBlocked = !state.activityRecognitionGranted
                TriggerGroup(
                    title = "Bewegung",
                    items = listOf(
                        TriggerToggle(
                            icon = "🚗",
                            title = "Autofahren",
                            description = "Automatisch starten, wenn Android eine Fahrt erkennt — stoppt beim Aussteigen",
                            accent = Color(0xFFF59E0B),
                            checked = state.settings.drivingDetectionEnabled,
                            permissionGranted = !arBlocked,
                            onCheckedChange = viewModel::setDriving,
                            onRequestPermission = {
                                pendingTrigger = "driving"
                                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        ),
                        TriggerToggle(
                            icon = "🚶",
                            title = "Walking & Laufen",
                            description = "Trigger erst nach 5 Minuten am Stück (kein False-Trigger bei kurzen Wegen)",
                            accent = Color(0xFF10B981),
                            checked = state.settings.walkingDetectionEnabled,
                            permissionGranted = !arBlocked,
                            onCheckedChange = viewModel::setWalking,
                            onRequestPermission = {
                                pendingTrigger = "walking"
                                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        ),
                        TriggerToggle(
                            icon = "🚴",
                            title = "Radfahren",
                            description = "Sofort-Trigger bei erkannten Fahrrad-Fahrten",
                            accent = Color(0xFF8B5CF6),
                            checked = state.settings.bicycleDetectionEnabled,
                            permissionGranted = !arBlocked,
                            onCheckedChange = viewModel::setBicycle,
                            onRequestPermission = {
                                pendingTrigger = "bicycle"
                                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        )
                    )
                )
            }

            // ── Schlaf ───────────────────────────────────────────────
            // M18.58: GENAU EINE Schlaf-Quelle (User-Wunsch: "nur einen
            // auswählbaren Trigger, entweder health connect oder
            // Bildschirmzeit. Oder keine Aufzeichnung. fancy Auswahl, wo
            // die drei Auswahlmöglichkeiten als Buttons nebeneinander sind,
            // und ein Rahmen um einen Button, und wenn man eine andere
            // Quelle will, klickt man auf die andere Quelle und der Rahmen
            // schwebt in einer Animation zur anderen Quelle").
            // Die alten "Bildschirm-Analyse"/"Fusion analysieren"-Buttons
            // sind entfernt (User: "alle Buttons die irgendwie die
            // Bildschirmzeiten anzeigen und fusionieren voll unnötig").
            item {
                SleepSourceCard(
                    currentSource = state.settings.sleepSource,
                    onSelectSource = viewModel::setSleepSource
                )
            }

            // ── M18.58: Garmin Connect Status ────────────────────────
            item {
                GarminStatusCard(
                    connected = state.garminConnected,
                    checking = state.garminChecking,
                    lastSyncAt = state.garminLastSyncAt,
                    error = state.garminError,
                    onSync = viewModel::garminSyncNow,
                    onRefresh = viewModel::refreshGarminStatus
                )
            }

            // ── Weitere Automatisierung (aus der alten Automation-Seite) ──
            // M18.58: Health-Connect-Schalter ENTFERNT — die Schlaf-Quelle
            // oben ist jetzt die einzige Stelle (User: "alle Buttons die
            // irgendwie die Bildschirmzeiten anzeigen und fusionieren voll
            // unnötig").
            item {
                AdditionalAutomationCard(
                    backgroundCaptureEnabled = state.settings.backgroundCaptureEnabled,
                    digitalBalanceEnabled = state.settings.digitalBalanceEnabled,
                    usageStatsGranted = state.usageStatsGranted,
                    onBackgroundCapture = viewModel::setBackgroundCapture,
                    onDigitalBalance = viewModel::setDigitalBalance,
                    onOpenUsageAccess = { viewModel.openUsageAccess() }
                )
            }

            // ── Daten & Status ───────────────────────────────────────
            item {
                DataCard(
                    geofenceCount = state.geofenceCount,
                    triggerCount = state.triggerCount,
                    pendingCandidateCount = state.pendingCandidateCount,
                    registrationMessage = state.registrationMessage,
                    onOpenGeofences = onOpenGeofences,
                    onOpenTriggers = onOpenTriggers,
                    onOpenStatus = onOpenStatus
                )
            }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

/** Item-Index der "Weitere Automatisierung"-Karte (Digital Balance) in der LazyColumn. */
private const val DIGITAL_BALANCE_ITEM_INDEX = 5

@Composable
private fun TriggerSettingsHero(onBack: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Trigger & Erkennung", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Entscheide pro Quelle, was Aevum automatisch erkennen darf. " +
                    "Berechtigungen werden direkt hier verwaltet — alles läuft lokal auf deinem Gerät.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

/**
 * M18.57: Kompakte Berechtigungs-Status-Karte (die fusionierte
 * "Berechtigungen"-Seite). Jede Zeile zeigt den Status und öffnet bei
 * Klick den passenden Berechtigungs-Flow.
 */
@Composable
private fun PermissionStatusCard(
    foregroundGranted: Boolean,
    backgroundGranted: Boolean,
    activityRecognitionGranted: Boolean,
    notificationsGranted: Boolean,
    usageStatsGranted: Boolean,
    onRequestForeground: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenUsageAccess: () -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
            Text(
                "Berechtigungen",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PermissionStatusRow("Standort", foregroundGranted, onRequestForeground)
            PermissionStatusRow(
                "Standort im Hintergrund (Immer erlauben)",
                backgroundGranted,
                onRequestBackground
            )
            PermissionStatusRow("Aktivitätserkennung", activityRecognitionGranted, onRequestActivityRecognition)
            PermissionStatusRow("Benachrichtigungen", notificationsGranted, onRequestNotifications)
            PermissionStatusRow("Nutzungszugriff (Bildschirmzeit)", usageStatsGranted, onOpenUsageAccess)
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp)
        Text(
            if (granted) "✓ Erteilt" else "Ausstehend",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (granted) PermissionGreen else PermissionRed
        )
    }
}

@Composable
private fun TriggerGroup(
    title: String,
    items: List<TriggerToggle>,
    footer: (@Composable () -> Unit)? = null
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            items.forEach { toggle ->
                TriggerToggleRow(toggle)
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun TriggerToggleRow(toggle: TriggerToggle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!toggle.permissionGranted) {
                    Modifier.clickable(onClick = toggle.onRequestPermission)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        // Icon-Badge
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(toggle.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(toggle.icon, fontSize = 18.sp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(toggle.title, fontWeight = FontWeight.SemiBold)
            Text(
                toggle.description,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
            // M18.57: Permission-Hinweis — rot (fehlt) / grün (erteilt)
            if (toggle.permissionGranted) {
                Text(
                    "✓ Berechtigung erteilt",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = PermissionGreen
                )
            } else {
                Text(
                    "⚠ Berechtigung noch nicht erteilt — tippen zum Erteilen",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = PermissionRed
                )
            }
        }
        Switch(
            checked = toggle.checked && toggle.permissionGranted,
            onCheckedChange = toggle.onCheckedChange,
            enabled = toggle.permissionGranted
        )
    }
}

private data class TriggerToggle(
    val icon: String,
    val title: String,
    val description: String,
    val accent: Color,
    val checked: Boolean,
    val permissionGranted: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val onRequestPermission: () -> Unit
)

/**
 * M18.58: Schlaf-Quellen-Auswahl — die EINE fancy Auswahl für Schlaf.
 *
 * User-Wunsch: "Ich will, dass die Schlafzeit nur einen auswählbaren
 * Trigger hat, entweder health connect oder Bildschirmzeit. Oder keine
 * Aufzeichnung. Das sollte so mit einer fancy Auswahl passieren, wo die
 * drei Auswahlmöglichkeiten als Buttons nebeneinander sind, und ein
 * Rahmen um einen Button, und wenn man eine andere Quelle will, klickt
 * man auf die andere Quelle und der Rahmen schwebt in einer Animation
 * zur anderen Quelle."
 *
 * M18.58-Erweiterung: Garmin als vierte Quelle (User-Wunsch Garmin-
 * Integration). Vier Buttons: Bildschirmzeit | Health Connect | Garmin |
 * Aus. Der Rahmen (Overlay-Box mit Border) SCHWEBT per Animation zur
 * aktiven Quelle: onGloballyPositioned misst die Button-Mittelpunkte,
 * animateFloatAsState gleitet zum Ziel, Modifier.offset setzt den Rahmen.
 */
@Composable
private fun SleepSourceCard(
    currentSource: String,
    onSelectSource: (String) -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                "Schlaf-Quelle",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Eine Quelle — Aevum trägt erkannten Schlaf automatisch in die Timeline ein.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val sources = listOf(
                SleepSourceOption("screen", "📱", "Bildschirmzeit"),
                SleepSourceOption("health_connect", "❤️", "Health Connect"),
                SleepSourceOption("garmin", "⌚", "Garmin"),
                SleepSourceOption("none", "🚫", "Aus")
            )
            val activeIndex = sources.indexOfFirst { it.id == currentSource }
                .coerceAtLeast(0)

            // M18.58: Rahmen-Position messen. Jeder Button registriert sein
            // linkes x (in px). Der schwebende Rahmen nutzt das Zentrum des
            // aktiven Buttons als Ziel-Offset.
            var buttonLefts by remember { mutableStateOf(listOf(0f, 0f, 0f, 0f)) }
            var buttonWidthPx by remember { mutableStateOf(0f) }
            val density = LocalDensity.current
            val targetCenterPx = if (buttonWidthPx > 0f && activeIndex < buttonLefts.size) {
                buttonLefts[activeIndex] + buttonWidthPx / 2f
            } else 0f
            val animatedCenterPx by androidx.compose.animation.core.animateFloatAsState(
                targetValue = targetCenterPx,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.72f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                ),
                label = "sleep-source-indicator"
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                // Die 4 Buttons (Basis-Ebene, ohne eigenen Rahmen)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                ) {
                    sources.forEach { option ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onGloballyPositioned { coords ->
                                    val lefts = buttonLefts.toMutableList()
                                    val idx = sources.indexOfFirst { it.id == option.id }
                                    if (idx in lefts.indices) lefts[idx] = coords.positionInParent().x
                                    buttonLefts = lefts
                                    buttonWidthPx = coords.size.width.toFloat()
                                }
                                .clip(RoundedCornerShape(AevumRadius.md))
                                .background(
                                    if (option.id == currentSource) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                                    }
                                )
                                .clickable { onSelectSource(option.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(option.icon, fontSize = 16.sp)
                                Text(
                                    option.label,
                                    fontSize = 10.sp,
                                    fontWeight = if (option.id == currentSource) FontWeight.Bold else FontWeight.Medium,
                                    color = if (option.id == currentSource) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Der schwebende Rahmen (Overlay, animiert zwischen Buttons)
                if (buttonWidthPx > 0f) {
                    val frameWidth = with(density) { buttonWidthPx.toDp() }
                    val frameOffset = with(density) { (animatedCenterPx - buttonWidthPx / 2f).toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = frameOffset)
                            .width(frameWidth)
                            .height(with(density) { (buttonWidthPx * 2.1f).toDp() })
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(AevumRadius.md)
                            )
                    )
                }
            }

            // Beschreibung der aktiven Quelle
            val description = when (currentSource) {
                "screen" -> "Bildschirm aus = schlafen. Erkennt Schlaf aus deinen Bildschirm-Phasen — ohne zusätzliche Geräte."
                "health_connect" -> "Importiert Schlaf aus Health Connect (z.B. Smartwatch, Fitnessband)."
                "garmin" -> "Importiert Schlaf aus Garmin Connect — direkt in die Timeline, sobald Daten verfügbar sind."
                else -> "Keine automatische Schlaf-Aufzeichnung. Schlaf kannst du weiterhin manuell eintragen."
            }
            Text(
                description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class SleepSourceOption(
    val id: String,
    val icon: String,
    val label: String
)

/**
 * M18.58: Garmin-Connect-Status-Karte.
 *
 * Zeigt, ob die Aevum-Garmin-Bridge verbunden ist (Token auf dem Server
 * vorhanden), wann der letzte Sync war, und bietet "Jetzt synchronisieren"
 * + Status-Neuprüfung. Der eigentliche Garmin-Login läuft NICHT in der
 * App — die Bridge übernimmt die bestehende Garmin-Session vom
 * Calorie-Tracker (gleiche Maschine, gleiche Tokens).
 */
@Composable
private fun GarminStatusCard(
    connected: Boolean,
    checking: Boolean,
    lastSyncAt: Long,
    error: String?,
    onSync: () -> Unit,
    onRefresh: () -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Garmin Connect",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (connected) "Verbunden — Kacheln & Aktivitäten synchronisiert"
                        else "Nicht verbunden — keine Kacheln, kein Aktivitäts-Import",
                        fontSize = 12.sp,
                        color = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (connected) "●" else "○",
                        fontSize = 20.sp,
                        color = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            // Letzter Sync + Fehler
            if (lastSyncAt > 0L) {
                Text(
                    "Letzter Sync: ${formatGarminTime(lastSyncAt)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            error?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(
                    onClick = onSync,
                    enabled = !checking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (checking) "Synchronisiere…" else "Jetzt synchronisieren")
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !checking
                ) {
                    Text("Status prüfen")
                }
            }

            Text(
                "Login: läuft über die Bridge auf dem Server (bestehende Garmin-Session vom Calorie Tracker). Kein Passwort in der App.",
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatGarminTime(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMAN)
    return sdf.format(java.util.Date(ms))
}

/** M18.57: Weitere Automatisierung (aus der alten Automation-Seite übernommen). */
@Composable
private fun AdditionalAutomationCard(
    backgroundCaptureEnabled: Boolean,
    digitalBalanceEnabled: Boolean,
    usageStatsGranted: Boolean,
    onBackgroundCapture: (Boolean) -> Unit,
    onDigitalBalance: (Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                "Weitere Automatisierung",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingSwitchRow(
                "Hintergrunderfassung",
                "Geofences erkennen, auch wenn Aevum geschlossen ist",
                backgroundCaptureEnabled,
                onBackgroundCapture
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
            SettingSwitchRow(
                "Bildschirmzeit erfassen",
                "Nutzungsstatistiken lokal analysieren",
                digitalBalanceEnabled,
                onDigitalBalance
            )
            if (!usageStatsGranted && digitalBalanceEnabled) {
                TextButton(onClick = onOpenUsageAccess) { Text("Nutzungszugriff öffnen") }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DataCard(
    geofenceCount: Int,
    triggerCount: Int,
    pendingCandidateCount: Int,
    registrationMessage: String?,
    onOpenGeofences: () -> Unit,
    onOpenTriggers: () -> Unit,
    onOpenStatus: () -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                "Daten & Status",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$geofenceCount Geofences · $triggerCount Trigger · $pendingCandidateCount offene Vorschläge",
                fontSize = 13.sp
            )
            registrationMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(onClick = onOpenGeofences) { Text("Geofences") }
                OutlinedButton(onClick = onOpenTriggers) { Text("Trigger") }
                OutlinedButton(onClick = onOpenStatus) { Text("Status-Details") }
            }
        }
    }
}

private val PermissionGreen = Color(0xFF10B981)
private val PermissionRed = Color(0xFFEF4444)

/** M18.57: ViewModel für die fusionierte Trigger-&-Erkennung-Seite. */
@HiltViewModel
class TriggerSettingsViewModel @Inject constructor(
    private val app: android.app.Application,
    private val settingsRepository: AutomationSettingsRepository,
    private val geofenceRegistrar: GeofenceRegistrar,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val triggerRepository: TriggerEventRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val usageStatsCollector: UsageStatsCollector,
    private val sleepHeuristicEngine: SleepHeuristicEngine,
    private val sleepFusionEngine: SleepFusionEngine,
    // M18.58: Garmin Connect — Status + Sync aus den Einstellungen.
    private val garminApiClient: GarminApiClient,
    private val garminSyncScheduler: GarminSyncScheduler
) : ViewModel() {

    private val registrationMessage = MutableStateFlow<String?>(null)

    // M18.57: Permission-Status wird bei jedem Tick frisch geprüft.
    // refreshPermissions() wird nach Permission-Dialogen und bei
    // ON_RESUME (Rückkehr aus System-Settings) aufgerufen.
    private data class PermissionSnapshot(
        val foregroundGranted: Boolean = false,
        val backgroundGranted: Boolean = false,
        val activityRecognitionGranted: Boolean = false,
        val notificationsGranted: Boolean = false,
        val usageStatsGranted: Boolean = false
    )

    private data class UiExtras(
        val message: String? = null,
        val perms: PermissionSnapshot = PermissionSnapshot(),
        // M18.58: Garmin-Status
        val garminConnected: Boolean = false,
        val garminChecking: Boolean = false,
        val garminLastSync: Long = 0L,
        val garminError: String? = null
    )

    private val permissionState = MutableStateFlow(PermissionSnapshot())
    // M18.58: Garmin-Status (verbunden, letzter Sync, Fehler)
    private val garminState = MutableStateFlow(
        GarminUiState(
            connected = false,
            checking = false,
            lastSyncAt = garminApiClient.lastSyncAt,
            error = null
        )
    )

    private val extras = combine(registrationMessage, permissionState, garminState) { msg, perms, garmin ->
        UiExtras(
            msg,
            perms,
            garminConnected = garmin.connected,
            garminChecking = garmin.checking,
            garminLastSync = garmin.lastSyncAt,
            garminError = garmin.error
        )
    }

    init {
        refreshPermissions()
        refreshGarminStatus()
    }

    val uiState: StateFlow<TriggerSettingsUiState> = combine(
        settingsRepository.get(),
        geofenceRepository.getAll(),
        triggerRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        extras
    ) { settings, geofences, triggers, candidates, extras ->
        TriggerSettingsUiState(
            settings = settings ?: AutomationSettings(),
            foregroundLocationGranted = extras.perms.foregroundGranted,
            backgroundLocationGranted = extras.perms.backgroundGranted,
            activityRecognitionGranted = extras.perms.activityRecognitionGranted,
            notificationsGranted = extras.perms.notificationsGranted,
            usageStatsGranted = extras.perms.usageStatsGranted,
            geofenceCount = geofences.size,
            triggerCount = triggers.size,
            pendingCandidateCount = candidates.size,
            registrationMessage = extras.message,
            // M18.58: Garmin-Status
            garminConnected = extras.garminConnected,
            garminChecking = extras.garminChecking,
            garminLastSyncAt = extras.garminLastSync,
            garminError = extras.garminError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TriggerSettingsUiState())

    fun refreshPermissions() {
        val perms = geofenceRegistrar.getPermissionStatus()
        permissionState.value = PermissionSnapshot(
            foregroundGranted = perms.foregroundGranted,
            backgroundGranted = perms.backgroundGranted,
            activityRecognitionGranted = has(Manifest.permission.ACTIVITY_RECOGNITION),
            notificationsGranted = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            usageStatsGranted = usageStatsCollector.hasPermission()
        )
    }

    // ── M18.58: Garmin Connect ─────────────────────────────────────────
    fun refreshGarminStatus() {
        viewModelScope.launch {
            garminState.value = garminState.value.copy(checking = true)
            val status = try {
                garminApiClient.getStatus()
            } catch (e: Exception) {
                GarminStatus(connected = false, error = e.message)
            }
            garminState.value = GarminUiState(
                connected = status.connected,
                checking = false,
                lastSyncAt = garminApiClient.lastSyncAt,
                error = status.error
            )
        }
    }

    /** M18.58: Manueller Garmin-Sync (sofort). */
    fun garminSyncNow() {
        viewModelScope.launch {
            garminState.value = garminState.value.copy(checking = true)
            garminSyncScheduler.syncNow()
            // Nach kurzer Wartezeit Status neu prüfen — der Worker läuft
            // asynchron, der letzteSync wird beim nächsten Status-Refresh
            // aktualisiert.
            delay(2_000)
            refreshGarminStatus()
        }
    }

    fun setGeofencing(enabled: Boolean) {
        upsert { it.copy(geofencingEnabled = enabled) }
        // M18.44: Sofort wirksam — bei Deaktivierung werden alle Geofences
        // beim System deregistriert, bei Aktivierung neu registriert.
        viewModelScope.launch {
            try {
                geofenceRegistrar.refreshRegisteredGeofences()
            } catch (e: Exception) {
                Log.e("TriggerSettings", "Geofence-Registrierung fehlgeschlagen", e)
            }
        }
    }

    fun setDriving(enabled: Boolean) = upsert { it.copy(drivingDetectionEnabled = enabled) }
    fun setWalking(enabled: Boolean) = upsert { it.copy(walkingDetectionEnabled = enabled) }
    fun setBicycle(enabled: Boolean) = upsert { it.copy(bicycleDetectionEnabled = enabled) }

    fun setSleepFusion(enabled: Boolean) {
        upsert { it.copy(sleepFusionEnabled = enabled) }
        if (enabled) {
            viewModelScope.launch {
                try {
                    val request = androidx.work.OneTimeWorkRequestBuilder<SleepFusionWorker>().build()
                    androidx.work.WorkManager.getInstance(app)
                        .enqueueUniqueWork(
                            SleepFusionWorker.WORK_NAME,
                            androidx.work.ExistingWorkPolicy.REPLACE,
                            request
                        )
                    registrationMessage.value = "✓ Intelligente Schlaf-Erkennung aktiv. Analyse läuft."
                } catch (e: Exception) {
                    registrationMessage.value = "Worker-Start fehlgeschlagen: ${e.message}"
                }
            }
        }
    }

    fun setBackgroundCapture(enabled: Boolean) =
        upsert { it.copy(backgroundCaptureEnabled = enabled, geofencingEnabled = enabled) }

    /**
     * M18.58: EINE Schlaf-Quelle (User-Wunsch: "nur einen auswählbaren
     * Trigger, entweder health connect oder Bildschirmzeit. Oder keine
     * Aufzeichnung"). Werte: "screen" | "health_connect" | "garmin" | "none".
     * Die alten Toggles (healthSleepEnabled, sleepFusionEnabled) werden
     * damit abgelöst — die Quelle ist die Single Source of Truth.
     */
    fun setSleepSource(source: String) {
        upsert {
            it.copy(
                sleepSource = source,
                // Rückwärtskompatibilität: healthSleepEnabled steuert den
                // Health-Connect-Import-Scheduler (SleepImportWorker).
                healthSleepEnabled = source == "health_connect",
                // sleepFusionEnabled war bisher der Fusion-Toggle — die
                // Fusion ist jetzt Teil der Quelle "screen".
                sleepFusionEnabled = source == "screen"
            )
        }
    }

    fun setDigitalBalance(enabled: Boolean) = upsert { it.copy(digitalBalanceEnabled = enabled) }

    fun openUsageAccess() = usageStatsCollector.openUsageAccessSettings()

    // ── Schlaf-Heuristik (Bildschirm-Muster) ─────────────────────────

    private val _isAnalyzingSleep = MutableStateFlow(false)
    val isAnalyzingSleep: StateFlow<Boolean> = _isAnalyzingSleep

    private val _sleepStatus = MutableStateFlow<SleepHeuristicStatus?>(null)
    val sleepStatus: StateFlow<SleepHeuristicStatus?> = _sleepStatus

    fun analyzeSleepNow() {
        viewModelScope.launch {
            _isAnalyzingSleep.value = true
            try {
                sleepHeuristicEngine.init(app)
                val sleepCandidatesBefore = candidateRepository.getByStatus("PENDING").first()
                    .count { it.activityTypeId == "sleep" }
                sleepHeuristicEngine.analyzeLatest()
                _sleepStatus.value = sleepHeuristicEngine.getStatus()
                val sleepCandidatesAfter = candidateRepository.getByStatus("PENDING").first()
                    .count { it.activityTypeId == "sleep" }
                registrationMessage.value = when {
                    sleepCandidatesAfter > sleepCandidatesBefore ->
                        "✓ Schlaf-Vorschlag in der Review-Inbox bereit."
                    sleepCandidatesAfter == sleepCandidatesBefore ->
                        "✓ Schlaf wurde direkt in die Timeline übernommen (Auto-Accept)."
                    else ->
                        "✓ Schlaf-Analyse abgeschlossen. Bereits erkannt — keine Änderung."
                }
            } catch (e: Exception) {
                registrationMessage.value = "Analyse fehlgeschlagen: ${e.message}"
            } finally {
                _isAnalyzingSleep.value = false
            }
        }
    }

    fun openSleepStatus() {
        viewModelScope.launch {
            try {
                sleepHeuristicEngine.init(app)
                _sleepStatus.value = sleepHeuristicEngine.getStatus()
            } catch (e: Exception) {
                registrationMessage.value = "Status nicht verfügbar: ${e.message}"
            }
        }
    }

    fun dismissSleepStatus() {
        _sleepStatus.value = null
    }

    // ── 3-Signal-Fusion ──────────────────────────────────────────────

    private val _isAnalyzingFusion = MutableStateFlow(false)
    val isAnalyzingFusion: StateFlow<Boolean> = _isAnalyzingFusion

    private val _fusionStatus = MutableStateFlow<SleepFusionStatus?>(null)
    val fusionStatus: StateFlow<SleepFusionStatus?> = _fusionStatus

    fun openFusionStatus() {
        viewModelScope.launch {
            try {
                _fusionStatus.value = sleepFusionEngine.getStatus()
            } catch (e: Exception) {
                registrationMessage.value = "Fusion-Status nicht verfügbar: ${e.message}"
            }
        }
    }

    fun dismissFusionStatus() {
        _fusionStatus.value = null
    }

    fun analyzeSleepFusionNow() {
        viewModelScope.launch {
            _isAnalyzingFusion.value = true
            try {
                sleepFusionEngine.analyzeLatest()
                _fusionStatus.value = sleepFusionEngine.getStatus()
                registrationMessage.value = "✓ Schlaf-Fusion ausgeführt. Ergebnis in der Review-Inbox / Timeline prüfen."
            } catch (e: Exception) {
                registrationMessage.value = "Fusion fehlgeschlagen: ${e.message}"
            } finally {
                _isAnalyzingFusion.value = false
            }
        }
    }

    private fun upsert(transform: (AutomationSettings) -> AutomationSettings) {
        val current = uiState.value.settings
        viewModelScope.launch {
            try {
                settingsRepository.upsert(transform(current).copy(updatedAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                // M18.56: Fehler sichtbar machen statt schlucken — vorher
                // sprangen Toggles stillschweigend zurück, weil DB-Exceptions
                // von viewModelScope.launch verschluckt wurden.
                Log.e("TriggerSettings", "upsert fehlgeschlagen", e)
            }
        }
    }

    private fun has(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(app, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

data class TriggerSettingsUiState(
    val settings: AutomationSettings = AutomationSettings(),
    val foregroundLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val activityRecognitionGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val geofenceCount: Int = 0,
    val triggerCount: Int = 0,
    val pendingCandidateCount: Int = 0,
    val registrationMessage: String? = null,
    // M18.58: Garmin Connect Status
    val garminConnected: Boolean = false,
    val garminChecking: Boolean = false,
    val garminLastSyncAt: Long = 0L,
    val garminError: String? = null
)

/** M18.58: Interner Garmin-Status im ViewModel. */
private data class GarminUiState(
    val connected: Boolean = false,
    val checking: Boolean = false,
    val lastSyncAt: Long = 0L,
    val error: String? = null
)
