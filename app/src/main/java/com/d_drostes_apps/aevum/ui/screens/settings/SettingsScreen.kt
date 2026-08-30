package com.d_drostes_apps.aevum.ui.screens.settings

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * M12.2: Callback-Signatur für Home/Work-Quick-Action.
 * Wenn bereits ein Geofence existiert, wird [onOpenExistingGeofence] aufgerufen,
 * andernfalls [onCreateHomeGeofence] / [onCreateWorkGeofence].
 * Damit gibt es keine "Bald verfügbar"-Platzhalter mehr.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    // M18.44: Trigger & Erkennung als eigene Seite (alle Quellen einzeln schaltbar)
    onOpenTriggerSettings: () -> Unit = {},
    onOpenGeofences: () -> Unit = {},
    onOpenTriggers: () -> Unit = {},
    // M18.30: Todos + Tagespauschalen
    onOpenTodos: () -> Unit = {},
    onOpenDailyAllowances: () -> Unit = {},
    // M18.2: Positivitäts-Scores pro Aktivität
    onOpenActivityTypes: () -> Unit = {},
    // M18.59: Kategorien-Seite
    onOpenCategories: () -> Unit = {},
    // M18.83: Orts-Timeline (Google-Maps-artige Tages-Story)
    onOpenPlaceTimeline: () -> Unit = {},
    onOpenHomeGeofence: (String) -> Unit = {},
    onOpenWorkGeofence: (String) -> Unit = {},
    onCreateHomeGeofence: () -> Unit = {},
    onCreateWorkGeofence: () -> Unit = {},
    // M18.55: Datenschutz, Export, Backup
    onOpenPrivacy: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    // M18.59: Fitness-Tracker (Garmin Connect Login + Sync)
    onOpenFitnessTrackers: () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { SettingsHero() }
            // App-Einstellungen: Sprache (Dropdown mit Flagge + Text, alphabetisch sortiert)
            item {
                val viewModel: SettingsViewModel = hiltViewModel()
                val currentLanguage by viewModel.language.collectAsState()
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                LanguageSettingsSection(
                    currentLanguage = currentLanguage,
                    onSelect = { lang ->
                        scope.launch {
                            viewModel.setLanguage(lang)
                            // Activity neu aufbauen, damit alle Ressourcen
                            // sofort in der neuen Sprache geladen werden.
                            (context as? android.app.Activity)?.recreate()
                        }
                    }
                )
            }
            // M18.44-RESTRUKTURIERUNG (User: "Einstellungen besser sortieren und
            // gruppieren"): Vorher standen Automatisierung, Aktivitäten und
            // Erweitert gleichwertig nebeneinander — Überladung. Jetzt:
            //  1. AUTOMATISIERUNG  — Trigger & Erkennung (primär) + Verwaltung
            //  2. AKTIVITÄTEN      — Activity Types
            //  3. ERWEITERT        — Ziele, Gewohnheiten, Todos, Pauschalen, Bucket List
            //  4. DATEN            — Datenschutz, Export, Backup
            // Jede Gruppe hat einen klaren Fokus; die neue Trigger-Seite ist
            // der zentrale Ort für alle automatischen Erkennungen.
            item {
                SettingsSection(
                    stringResource(R.string.settings_section_automation),
                    listOf(
                        // M18.57: Die Seite "Berechtigungen" wurde in
                        // "Trigger & Erkennung" fusioniert — nur noch diese
                        // eine Seite existiert (inkl. Berechtigungs-Status).
                        SettingsEntry(stringResource(R.string.settings_trigger_detection), stringResource(R.string.settings_trigger_detection_desc), onOpenTriggerSettings),
                        SettingsEntry(stringResource(R.string.settings_geofences_manage), stringResource(R.string.settings_geofences_manage_desc), onOpenGeofences),
                        SettingsEntry(stringResource(R.string.settings_trigger_events), stringResource(R.string.settings_trigger_events_desc), onOpenTriggers),
                        // M18.83: Orts-Timeline — eigener Punkt unter Einstellungen.
                        SettingsEntry(stringResource(R.string.settings_place_timeline), stringResource(R.string.settings_place_timeline_desc), onOpenPlaceTimeline)
                    )
                )
            }
            item {
                // M12.2: Home/Work-Status wird jetzt live aus dem Geofence-Repository gelesen.
                val placeGeofences by hiltViewModel<SettingsViewModel>().geofences.collectAsState()
                val homeExisting = placeGeofences.firstOrNull { it.name.contains("Zuhause", ignoreCase = true) || it.name.contains("home", ignoreCase = true) }
                val workExisting = placeGeofences.firstOrNull { it.name.contains("Arbeit", ignoreCase = true) || it.name.contains("work", ignoreCase = true) }
                SettingsSection(
                    stringResource(R.string.settings_my_places),
                    listOf(
                        SettingsEntry(
                            stringResource(R.string.common_home),
                            status = if (homeExisting != null) stringResource(R.string.common_existing) else stringResource(R.string.common_create_now),
                            onClick = { if (homeExisting != null) onOpenHomeGeofence(homeExisting.id) else onCreateHomeGeofence() }
                        ),
                        SettingsEntry(
                            stringResource(R.string.common_work),
                            status = if (workExisting != null) stringResource(R.string.common_existing) else stringResource(R.string.common_create_now),
                            onClick = { if (workExisting != null) onOpenWorkGeofence(workExisting.id) else onCreateWorkGeofence() }
                        )
                    )
                )
            }
            // M18.2: Aktivitäten mit Positivitäts-Slider
            // M18.59: + Kategorien-Seite (User-Wunsch: Kategorien auflisten,
            // neue erstellen, Aktivitäten zuordnen, Icon+Farbe personalisieren)
            item { SettingsSection(stringResource(R.string.settings_your_activities), listOf(
                SettingsEntry(stringResource(R.string.settings_activity_types), stringResource(R.string.settings_activity_types_desc), onOpenActivityTypes),
                SettingsEntry(stringResource(R.string.common_categories), stringResource(R.string.settings_categories_desc), onOpenCategories)
            )) }
            // M18.44: Neben-Features (Todos/Pauschalen) in eigener Sektion —
            // erreichbar, aber klar getrennt vom Kern.
            // M18.60: "Ziele verwalten" entfernt — Todos erfüllen die
            // Anforderungen eines Ziels (Ziel-Chip + Streak auf jeder Karte).
            item { SettingsSection(stringResource(R.string.settings_advanced), listOf(
                SettingsEntry(stringResource(R.string.settings_todos_manage), onClick = onOpenTodos),
                SettingsEntry(stringResource(R.string.settings_allowances_manage), onClick = onOpenDailyAllowances)
            )) }
            // M18.55: Datenschutz, Export, Backup — echte Seiten statt Platzhalter
            item { SettingsSection(stringResource(R.string.settings_privacy_data), listOf(
                SettingsEntry(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_desc), onOpenPrivacy),
                SettingsEntry(stringResource(R.string.common_export), stringResource(R.string.settings_export_desc), onOpenExport),
                SettingsEntry(stringResource(R.string.common_backup), stringResource(R.string.settings_backup_desc), onOpenBackup)
            )) }
            // M18.59: Fitness-Tracker — Garmin Connect Login + Sync
            item { SettingsSection(stringResource(R.string.settings_fitness_trackers), listOf(
                SettingsEntry(stringResource(R.string.settings_garmin_connect), stringResource(R.string.settings_garmin_connect_desc), onOpenFitnessTrackers)
            )) }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun SettingsHero() {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(stringResource(R.string.settings_title), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_hero_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

/**
 * App-Einstellungen: Sprachauswahl als Dropdown (Flagge + Text).
 * Optionen sind alphabetisch nach Anzeigename sortiert.
 */
@Composable
private fun LanguageSettingsSection(
    currentLanguage: String,
    onSelect: (String) -> Unit
) {
    // Alphabetisch sortiert nach Anzeigename: Deutsch, English, System
    val options = listOf(
        LanguageOption(LanguageRepository.LANGUAGE_DE, "🇩🇪", stringResource(R.string.language_de)),
        LanguageOption(LanguageRepository.LANGUAGE_EN, "🇬🇧", stringResource(R.string.language_en)),
        LanguageOption(LanguageRepository.LANGUAGE_SYSTEM, "🌐", stringResource(R.string.language_system))
    )
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.code == currentLanguage } ?: options.last()

    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(stringResource(R.string.settings_app_section), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_language_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                Surface(
                    onClick = { expanded = true },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selected.flag, fontSize = 18.sp)
                            Spacer(Modifier.width(AevumSpacing.sm))
                            Text(selected.label, fontWeight = FontWeight.Medium)
                        }
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(option.flag, fontSize = 18.sp)
                                    Spacer(Modifier.width(AevumSpacing.sm))
                                    Text(option.label)
                                }
                            },
                            onClick = {
                                expanded = false
                                if (option.code != currentLanguage) onSelect(option.code)
                            }
                        )
                    }
                }
            }
        }
    }
}

private data class LanguageOption(
    val code: String,
    val flag: String,
    val label: String
)

@Composable
private fun SettingsSection(title: String, entries: List<SettingsEntry>) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            entries.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, fontWeight = FontWeight.Medium)
                        // M12.2: status wird dynamisch pro Eintrag gesetzt (z. B. "Vorhanden" / "Jetzt anlegen").
                        Text(entry.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // M12.2: Auch ohne onClick zeigen wir einen echten "Öffnen"-Button
                    // statt eines "Bald"-Chips. Default = passiver "Öffnen"-Button
                    // der deaktiviert ist, falls keine Aktion dahinter liegt.
                    if (entry.onClick != null) {
                        Button(onClick = entry.onClick!!) { Text(stringResource(R.string.common_open)) }
                    } else {
                        AssistChip(onClick = {}, label = { Text(entry.status) })
                    }
                }
            }
        }
    }
}

/**
 * M12.2: status hat jetzt einen sinnvollen Default. Home/Work setzen
 * "Vorhanden" / "Jetzt anlegen" dynamisch; nicht-klickbare Einträge
 * zeigen "Bald nur noch, wenn wirklich nichts existiert" — nie als
 * Platzhalter.
 */
private data class SettingsEntry(
    val title: String,
    val status: String = "Verfügbar",
    val onClick: (() -> Unit)? = null
)
/**
 * M12.2: ViewModel, das die PlaceGeofence-Liste an die Settings-Screen liefert.
 * Damit kann die UI ohne extra Repository-Aufruf entscheiden, ob "Zuhause" oder
 * "Arbeit" bereits existieren (und direkt geöffnet werden können) oder neu
 * angelegt werden müssen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    placeGeofenceRepository: PlaceGeofenceRepository,
    private val languageRepository: LanguageRepository
) : ViewModel() {
    val geofences: StateFlow<List<PlaceGeofence>> = placeGeofenceRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Aktuell gewählte App-Sprache ("system", "de", "en"). */
    val language: StateFlow<String> = languageRepository.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LanguageRepository.LANGUAGE_SYSTEM)

    suspend fun setLanguage(language: String) {
        languageRepository.setLanguage(language)
    }
}