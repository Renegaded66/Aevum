package com.d_drostes_apps.aevum.ui.screens.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
    // M20: Ziel-Architektur — Ziele-Liste (Tages-/Wochen-/Monatsziele)
    onOpenGoals: () -> Unit = {},
    // M18.2: Positivitäts-Scores pro Aktivität
    onOpenActivityTypes: () -> Unit = {},
    // M18.59: Kategorien-Seite
    onOpenCategories: () -> Unit = {},
    // M18.83: Orts-Timeline (Google-Maps-artige Tages-Story)
    // M18.93: "Meine Orte" entfernt — onOpenHomeGeofence/onOpenWorkGeofence/
    // onCreateHomeGeofence/onCreateWorkGeofence entfallen (Home/Work läuft
    // über Einstellungen → Geofences mit Quick-Setup).
    onOpenPlaceTimeline: () -> Unit = {},
    // M18.55: Datenschutz, Export, Backup
    onOpenPrivacy: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    // M18.59: Fitness-Tracker (Garmin Connect Login + Sync)
    onOpenFitnessTrackers: () -> Unit = {},
    // PC-Tracker: Windows-Aufzeichnung im Heimnetz
    onOpenPcTracker: () -> Unit = {},
    // M18.129: Kalender-Integration (Regeln + Sync + Berechtigung)
    onOpenCalendarRules: () -> Unit = {}
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
            // M33: Einstellungen neu gruppiert. Vorher standen neun
            // gleichrangige Karten untereinander (Automatisierung, Aktivitäten,
            // Erweitert, Datenschutz, Fitness, PC, Kalender …) — der Screen
            // wirkte wie eine Feature-Liste statt wie ein Ort zum Einstellen.
            // Jetzt fünf klare Gruppen, jede Zeile ist eine echte Zeile
            // (Titel + Untertitel + Chevron) statt Titel + „Öffnen"-Button.
            item {
                val viewModel: SettingsViewModel = hiltViewModel()
                val currentLanguage by viewModel.language.collectAsState()
                val currentTheme by viewModel.theme.collectAsState()
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsGroupLabel(stringResource(R.string.settings_section_appearance))
                    AppSettingsSection(
                        currentLanguage = currentLanguage,
                        currentTheme = currentTheme,
                        onSelectLanguage = { lang ->
                            scope.launch {
                                viewModel.setLanguage(lang)
                                // Activity neu aufbauen, damit alle Ressourcen
                                // sofort in der neuen Sprache geladen werden.
                                (context as? android.app.Activity)?.recreate()
                            }
                        },
                        onSelectTheme = { theme ->
                            scope.launch {
                                // M18.106: Theme persistieren, dann Activity neu
                                // aufbauen — AevumAppTheme liest beim Rebuild den
                                // frischen Spiegel (gleicher Mechanismus wie beim
                                // Sprachwechsel, kein Theme-Flacker).
                                viewModel.setTheme(theme)
                                (context as? android.app.Activity)?.recreate()
                            }
                        }
                    )
                }
            }
            // M19.5: Maskottchen — eigenes Wesen fürs Dashboard (Devon:
            // „ein und ausschalten … default aus. Wenn ein, dann kann man
            // sich selber ein maskottchen erstellen").
            item {
                val viewModel: SettingsViewModel = hiltViewModel()
                val mascotConfig by viewModel.mascotConfig.collectAsState()
                val scope = rememberCoroutineScope()
                MascotSettingsCard(
                    config = mascotConfig,
                    onToggle = { enabled ->
                        scope.launch { viewModel.setMascotEnabled(enabled) }
                    },
                    onSelectColor = { colorId ->
                        scope.launch { viewModel.setMascotColor(colorId) }
                    },
                    onSelectAntennaStyle = { style ->
                        scope.launch { viewModel.setMascotAntennaStyle(style) }
                    },
                    onToggleCheeks = { cheeks ->
                        scope.launch { viewModel.setMascotCheeks(cheeks) }
                    }
                )
            }
            // Aktivitäten & Ziele — alles, was der User selbst gestaltet.
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_your_activities),
                    entries = listOf(
                        SettingsEntry(stringResource(R.string.settings_activity_types), stringResource(R.string.settings_activity_types_desc), onOpenActivityTypes),
                        SettingsEntry(stringResource(R.string.common_categories), stringResource(R.string.settings_categories_desc), onOpenCategories),
                        SettingsEntry(stringResource(R.string.settings_goals), stringResource(R.string.settings_goals_desc), onOpenGoals),
                        SettingsEntry(stringResource(R.string.settings_todos_manage), stringResource(R.string.settings_todos_desc), onOpenTodos),
                        SettingsEntry(stringResource(R.string.settings_allowances_manage), stringResource(R.string.settings_allowances_desc), onOpenDailyAllowances)
                    )
                )
            }
            // Automatisierung — alles, was Aevum von selbst erkennt.
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_section_automation),
                    entries = listOf(
                        // M18.57: Die Seite "Berechtigungen" wurde in
                        // "Trigger & Erkennung" fusioniert — nur noch diese
                        // eine Seite existiert (inkl. Berechtigungs-Status).
                        SettingsEntry(stringResource(R.string.settings_trigger_detection), stringResource(R.string.settings_trigger_detection_desc), onOpenTriggerSettings),
                        SettingsEntry(stringResource(R.string.settings_geofences_manage), stringResource(R.string.settings_geofences_manage_desc), onOpenGeofences),
                        SettingsEntry(stringResource(R.string.settings_trigger_events), stringResource(R.string.settings_trigger_events_desc), onOpenTriggers),
                        // M18.83: Orts-Timeline — eigener Punkt unter Einstellungen.
                        SettingsEntry(stringResource(R.string.settings_place_timeline), stringResource(R.string.settings_place_timeline_desc), onOpenPlaceTimeline),
                        // M18.129: Kalender-Integration — Regeln, Auto-Aufzeichnung, Sync
                        SettingsEntry(stringResource(R.string.calendar_rules_title), stringResource(R.string.calendar_rules_desc), onOpenCalendarRules),
                        // PC-Tracker gehört fachlich zur Automatisierung.
                        SettingsEntry(stringResource(R.string.settings_pc_tracker), stringResource(R.string.settings_pc_tracker_desc), onOpenPcTracker)
                    )
                )
            }
            // Verbindungen — externe Geräte/Dienste.
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_section_connections),
                    entries = listOf(
                        SettingsEntry(stringResource(R.string.settings_garmin_connect), stringResource(R.string.settings_garmin_connect_desc), onOpenFitnessTrackers)
                    )
                )
            }
            // M18.55: Datenschutz, Export, Backup — echte Seiten statt Platzhalter
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_privacy_data),
                    entries = listOf(
                        SettingsEntry(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_desc), onOpenPrivacy),
                        SettingsEntry(stringResource(R.string.common_export), stringResource(R.string.settings_export_desc), onOpenExport),
                        SettingsEntry(stringResource(R.string.common_backup), stringResource(R.string.settings_backup_desc), onOpenBackup)
                    )
                )
            }
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
 * App-Einstellungen: Sprache + Design (M18.106) in EINER Karte.
 * Sprachauswahl als Dropdown (Flagge + Text, alphabetisch sortiert),
 * Theme-Auswahl analog (dark = Default).
 */
@Composable
private fun AppSettingsSection(
    currentLanguage: String,
    currentTheme: String,
    onSelectLanguage: (String) -> Unit,
    onSelectTheme: (String) -> Unit
) {
    // M34: Alle zehn Sprachen + "System". Die Anzeigenamen sind Endonyme
    // (Deutsch, Español, Français …) und stehen als Ressourcen zur Verfügung.
    val languageOptions = com.d_drostes_apps.aevum.util.AppLanguage.all.map { language ->
        LanguageOption(language.code, language.flag, stringResource(language.labelRes))
    }
    // M18.106: Theme-Optionen — Dark zuerst (Default).
    val themeOptions = listOf(
        LanguageOption(com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_DARK, "🌙", stringResource(R.string.theme_dark)),
        LanguageOption(com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_LIGHT, "☀️", stringResource(R.string.theme_light)),
        LanguageOption(com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_SYSTEM, "🌓", stringResource(R.string.theme_system))
    )

    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(stringResource(R.string.settings_app_section), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_language_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsPickerRow(
                options = languageOptions,
                selectedCode = currentLanguage,
                fallbackCode = LanguageRepository.LANGUAGE_DEFAULT,
                onSelect = onSelectLanguage
            )
            // M18.106: Design-Zeile.
            Text(
                stringResource(R.string.settings_theme),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.settings_theme_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsPickerRow(
                options = themeOptions,
                selectedCode = currentTheme,
                fallbackCode = com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_DEFAULT,
                onSelect = onSelectTheme
            )
        }
    }
}

/** Wiederverwendbare Dropdown-Zeile (Sprache/Theme teilen sich das Muster). */
@Composable
private fun SettingsPickerRow(
    options: List<LanguageOption>,
    selectedCode: String,
    fallbackCode: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.code == selectedCode }
        ?: options.firstOrNull { it.code == fallbackCode }
        ?: options.first()

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
                        if (option.code != selectedCode) onSelect(option.code)
                    }
                )
            }
        }
    }
}

private data class LanguageOption(
    val code: String,
    val flag: String,
    val label: String
)

/**
 * Hinweis (App 1.0.35): Ein Update-Knopf lebt ausschließlich in der
 * PC-Anwendung — die Handy-App wird über den Play Store aktualisiert
 * (Devon: „der update button sollte nur in der pc anwendung sein. Natürlich
 * sollte die handy anwendung nach wie vor über play store heruntergeladen
 * werden."). Die 1.0.34-Karte „App-Update" ist deshalb wieder entfernt.
 */
/**
 * M33: Gruppe = Label außerhalb, eine Karte mit echten Zeilen darin.
 * Vorher trug jede Karte einen 20sp-Titel und jede Zeile einen „Öffnen"-
 * Button — neun Karten, neun Buttons, keine Hierarchie. Jetzt: fünf
 * Gruppenlabels, darunter ruhige Zeilen mit Chevron.
 */
@Composable
private fun SettingsGroupLabel(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = AevumSpacing.sm, top = AevumSpacing.xs)
    )
}

@Composable
private fun SettingsGroup(title: String, entries: List<SettingsEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsGroupLabel(title)
        AevumCard(contentPadding = PaddingValues(0.dp)) {
            Column {
                entries.forEachIndexed { index, entry ->
                    SettingsRow(
                        entry = entry,
                        showDivider = index < entries.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = entry.onClick)
                .padding(horizontal = AevumSpacing.md, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                entry.subtitle?.let { subtitle ->
                    Text(
                        subtitle,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = AevumSpacing.md),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )
        }
    }
}

/**
 * M33: Jede Zeile besteht aus Titel + optionalem Untertitel + Ziel.
 * Der frühere `status`-Parameter existierte nur, um den „Öffnen"-Button
 * zu ersetzen — er ist mit dem Umbau entfallen.
 */
private data class SettingsEntry(
    val title: String,
    val subtitle: String? = null,
    val onClick: () -> Unit
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
    private val languageRepository: LanguageRepository,
    // M18.106: Theme-Auswahl (dark/light/system, Default dark).
    private val themeRepository: com.d_drostes_apps.aevum.data.repository.ThemeRepository,
    // M19.5: Maskottchen (an/aus + Erstellen: Farbe/Ohren/Bäckchen).
    private val mascotRepository: com.d_drostes_apps.aevum.data.repository.MascotRepository
) : ViewModel() {
    val geofences: StateFlow<List<PlaceGeofence>> = placeGeofenceRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Aktuell gewählte App-Sprache ("system", "de", "en"). Default: en (M18.91). */
    val language: StateFlow<String> = languageRepository.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LanguageRepository.LANGUAGE_DEFAULT)

    /** M18.106: Aktuell gewähltes App-Theme ("dark", "light", "system"). Default: dark. */
    val theme: StateFlow<String> = themeRepository.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_DEFAULT)

    /** M19.5: Maskottchen-Konfiguration (Default aus ⇒ alles wie bisher). */
    val mascotConfig: StateFlow<com.d_drostes_apps.aevum.data.repository.MascotRepository.MascotConfig> =
        mascotRepository.config.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            mascotRepository.currentSync()
        )

    suspend fun setLanguage(language: String) {
        languageRepository.setLanguage(language)
    }

    /** M18.106: Theme persistieren (DataStore + synchroner Spiegel). */
    suspend fun setTheme(theme: String) {
        themeRepository.setTheme(theme)
    }

    /** M19.5: Maskottchen aktivieren/deaktivieren (Default aus). */
    suspend fun setMascotEnabled(enabled: Boolean) {
        mascotRepository.setEnabled(enabled)
    }

    /** M19.5: Körperfarbe des Maskottchens persistieren. */
    suspend fun setMascotColor(colorId: String) {
        mascotRepository.setColor(colorId)
    }

    /** M19.6: Antennen-Spitze des Maskottchens persistieren. */
    suspend fun setMascotAntennaStyle(style: String) {
        mascotRepository.setAntennaStyle(style)
    }

    /** M19.5: Bäckchen an/aus. */
    suspend fun setMascotCheeks(cheeks: Boolean) {
        mascotRepository.setCheeks(cheeks)
    }
}