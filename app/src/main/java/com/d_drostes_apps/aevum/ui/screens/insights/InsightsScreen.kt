package com.d_drostes_apps.aevum.ui.screens.insights

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.AnimatedGradientBar
import com.d_drostes_apps.aevum.ui.components.AnimatedNumberCounter
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Duration

/**
 * M17.4: Statistik-Screen — futuristic Glassmorphism-Redesign.
 *
 * - Hero-Header mit animated Number-Counter (Total-Minuten)
 * - Period-Toggle (Heute / Woche / Monat)
 * - M18.146: Perioden-Navigation (‹ Label › + Picker-Sheet zum Durchskippen)
 * - Breakdown-Toggle (Aktivität / Kategorie) — animiert
 * - Animierte Gradient-Bars (verzögert pro Item → Kaskaden-Effekt)
 * - Glass-Cards mit Gradient-Border
 * - M18.146: Klick auf eine Aktivität → Detail-Statistikseite
 */
@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    // M18.35: Link zur Lebenszeit-Ansicht
    onOpenLifeView: () -> Unit = {},
    // M18.146: Klick auf eine Aktivität der Top-Liste → Statistikseite.
    onOpenActivityStats: (String) -> Unit = {},
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // M18.146: Das Auswahl-Sheet ist reiner UI-Zustand — es gehört dem Screen,
    // nicht dem ViewModel (derselbe Grundsatz wie beim Auf-/Zuklappen).
    var showPickerSheet by remember { mutableStateOf(false) }
    InsightsScreenContent(
        uiState = uiState,
        onOpenLifeView = onOpenLifeView,
        onSelectPeriod = viewModel::selectPeriod,
        onSelectBreakdownMode = viewModel::setBreakdownMode,
        onToggleExpanded = viewModel::toggleTopActivitiesExpanded,
        onRetry = viewModel::retry,
        // M18.146: Perioden-Navigation
        onStepPeriod = viewModel::stepAnchor,
        onResetPeriod = viewModel::resetAnchorToToday,
        onOpenPicker = { showPickerSheet = true },
        onOpenActivityStats = onOpenActivityStats
    )
    // M18.146: Zeitraum-Auswahl-Sheet — durch Tage/Wochen/Monate skippen.
    if (showPickerSheet) {
        PeriodPickerSheet(
            period = uiState.selectedPeriod,
            anchor = uiState.anchorDate,
            onSelect = { anchor ->
                viewModel.selectAnchor(anchor)
                showPickerSheet = false
            },
            onDismiss = { showPickerSheet = false }
        )
    }
}

/**
 * M18.137 (Kanban t_386782be): Der zustandsfreie Rumpf des Screens.
 *
 * WARUM AUSGELAGERT: Solange Screen und ViewModel eine Einheit waren, liess
 * sich die VERDRAHTUNG (liest der Screen wirklich `allBreakdown`? haengt die
 * Liste am Toggle? erscheint bei Fehler die Fehlerkarte?) nur ueber einen
 * Hilt-Graph im Test pruefen — praktisch also gar nicht. Genau diese Naht
 * ist aber der Auftrag dieser Karte.
 *
 * Der Rumpf nimmt den [InsightsUiState] und die Handler als Parameter. Damit
 * kann ein Test JEDEN Zustand rendern (Kaltstart, Fehler, 7 Aktivitaeten)
 * und die tatsaechliche Compose-Hierarchie pruefen, ohne das ViewModel zu
 * faken. Der Wrapper oben bleibt die einzige Stelle, die Hilt kennt.
 */
@Composable
internal fun InsightsScreenContent(
    uiState: InsightsUiState,
    onOpenLifeView: () -> Unit = {},
    onSelectPeriod: (InsightPeriod) -> Unit = {},
    onSelectBreakdownMode: (BreakdownMode) -> Unit = {},
    onToggleExpanded: () -> Unit = {},
    onRetry: () -> Unit = {},
    // M18.146: Perioden-Navigation + Verlinkung zur Aktivitäts-Statistik.
    onStepPeriod: (Long) -> Unit = {},
    onResetPeriod: () -> Unit = {},
    onOpenPicker: () -> Unit = {},
    onOpenActivityStats: (String) -> Unit = {}
) {
    // M18.137 (Kanban t_70a06809): Ausklapp-Zustand der Top-Liste.
    val topActivitiesExpanded = uiState.topActivitiesExpanded
    // M18.137 (Kanban t_386782be): Lade-/Fehler-/Inhalts-Phase.
    //
    // Der Screen hat drei sich ausschliessende Zustaende. Vorher gab es nur
    // zwei: Inhalt oder "Noch keine Daten". Ein fehlgeschlagener Aufbau
    // (errorMessage != null) landete damit optisch bei "Noch keine Daten" —
    // der Nutzer sah eine leere Auswertung statt einer Fehlermeldung und
    // hatte keinen Weg zurueck.
    val phase = insightsPhaseOf(
        isLoading = uiState.isLoading,
        errorMessage = uiState.errorMessage,
        hasData = uiState.hasData
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AevumSpacing.lg)
                // M18.137 (Kanban t_386782be): Tag fuer die UI-Tests — erlaubt
                // gezieltes Scrollen zu den hinteren Zeilen (der aufgeklappten
                // Liste), das sonst am Viewport scheitert.
                .testTag(INSIGHTS_LIST_TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AevumSpacing.lg)
        ) {
            // 1) Hero-Header
            item { InsightsHero(uiState, onOpenLifeView) }

            // M18.137 (Kanban t_386782be): Ab hier haengt der Inhalt an der
            // Phase. Der Hero-Header bleibt in JEDER Phase sichtbar — er zeigt
            // den Zeitraum und den (ggf. noch leeren) Gesamtwert, also genau
            // den Rahmen, in dem der Lade-/Fehlerzustand stattfindet.
            when (phase) {
                // Kaltstart: die Daten stehen noch aus. Ohne diesen Zweig
                // stand hier ein leerer Bildschirm mit "Noch keine Daten" —
                // eine falsche Aussage, solange nur noch nichts geladen ist.
                InsightsPhase.Loading -> item { InsightsLoadingCard() }

                // Fehlgeschlagener Aufbau: benannter Fehler + echter Retry.
                // Bewusst STATT des Inhalts (nicht zusaetzlich): der Zustand
                // traegt in diesem Fall keine Daten, ein zusaetzlich
                // gerendertes "Noch keine Daten" waere irrefuehrend.
                InsightsPhase.Error -> item {
                    InsightsErrorCard(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = onRetry
                    )
                }

                InsightsPhase.Content -> {
                    // 2) Period-Toggle
                    item {
                        PeriodSelector(
                            selected = uiState.selectedPeriod,
                            onSelect = onSelectPeriod
                        )
                    }

                    // 2b) M18.146: Perioden-Navigationszeile — ‹ Label ›
                    // durchskippen + Sheet öffnen. Das Label zeigt die KONKRETE
                    // Periode (z.B. "KW 40 · 28. Sep. – 4. Okt."), nicht nur
                    // "Diese Woche" — genau das, was beim Skippen fehlte.
                    item {
                        PeriodNavigatorRow(
                            uiState = uiState,
                            onStep = onStepPeriod,
                            onReset = onResetPeriod,
                            onOpenPicker = onOpenPicker
                        )
                    }

                    // 3) Breakdown-Toggle (Aktivität / Kategorie)
                    item {
                        BreakdownToggle(
                            mode = uiState.breakdownMode,
                            onSelect = onSelectBreakdownMode
                        )
                    }

                    // 4) Top-Liste (animierte Bars)
                    // M18.137 (Kanban t_70a06809): Ausklappbar — zugeklappt die
                    // ersten 5, aufgeklappt wirklich ALLE Aktivitäten der Periode.
                    // Die Karte bringt den Icon-Toggle selbst mit und rendert sich
                    // bei leerer Liste gar nicht.
                    // M18.146: Jede Zeile mit echtem ActivityType verlinkt auf
                    // die Detail-Statistikseite (Kategorie-Zeilen und
                    // Titel-Fallbacks bleiben ohne Link).
                    item {
                        TopActivitiesCard(
                            mode = uiState.breakdownMode,
                            // M18.137 (Kanban t_13e9f843): Die QUELLE fuer beide
                            // Zustaende ist `allBreakdown` — die vollständige,
                            // sortierte Liste. Zugeklappt schneidet die Karte selbst
                            // auf die ersten 5 zu, aufgeklappt zeigt sie alles.
                            // `topBreakdown` bleibt die Top-5-Ansicht des
                            // Analytics-Layers und wird hier nicht mehr gebraucht.
                            items = uiState.allBreakdown,
                            expanded = topActivitiesExpanded,
                            onToggleExpanded = onToggleExpanded,
                            // M18.146: Link-Ziel je Zeile (null = kein Link).
                            onOpenActivityStats = { id ->
                                if (id in uiState.knownActivityTypeIds) onOpenActivityStats(id)
                            }
                        )
                    }

                    // 5) Period-Änderungen
                    if (uiState.changes.isNotEmpty()) {
                        item {
                            GlassCard(accentColor = MaterialTheme.colorScheme.primary) {
                                Column {
                                    Text(
                                        stringResource(R.string.insights_changes_title),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(AevumSpacing.md))
                                    uiState.changes.forEachIndexed { index, change ->
                                        ChangeRow(change = change, index = index)
                                        if (index < uiState.changes.lastIndex) {
                                            Spacer(Modifier.height(AevumSpacing.sm))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6) Insight-Cards
                    if (uiState.insightCards.isNotEmpty()) {
                        items(uiState.insightCards) { card ->
                            GlassCard(accentColor = MaterialTheme.colorScheme.tertiary) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    Spacer(Modifier.width(AevumSpacing.md))
                                    Column {
                                        Text(
                                            card.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            card.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 7) Empty-State
                    // Nur in der Content-Phase erreichbar — und dort bedeutet
                    // `!hasData` tatsaechlich "leere Datenbank", nicht "laedt
                    // noch" und nicht "Laden fehlgeschlagen".
                    if (!uiState.hasData) {
                        item {
                            GlassCard(accentColor = MaterialTheme.colorScheme.outline) {
                                Column {
                                    Text(
                                        stringResource(R.string.insights_empty_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(AevumSpacing.sm))
                                    Text(
                                        stringResource(R.string.insights_empty_message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightsHero(uiState: InsightsUiState, onOpenLifeView: () -> Unit) {
    // M18.39: Exakte Anzeige OHNE Rundung. Vorher: Dezimal-Stunden mit
    // 1 Nachkommastelle ("7,5 Std") PLUS Minuten daneben — redundant und
    // wirkte gerundet. Jetzt: exakte Minuten -> "7 Std 32 Min", 100% praezise.
    val totalMinutes = uiState.totalMsIncludingAllowances / 60_000
    val hoursPart = totalMinutes / 60
    val minutesPart = totalMinutes % 60
    val accentColor = MaterialTheme.colorScheme.primary
    GlassCard(accentColor = accentColor) {
        Column {
            Text(
                uiState.periodLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(AevumSpacing.xs))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$hoursPart",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.insights_hours_short),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.width(AevumSpacing.md))
                Text(
                    "$minutesPart",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.insights_minutes_short),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Spacer(Modifier.height(AevumSpacing.xs))
            Text(
                uiState.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            // M18.35: Lebenszeit-Button — der Einstieg in die
            // "Was bleibt dir?"-Ansicht. Bewusst als dezenter Chip,
            // damit die Insights nicht überladen werden.
            Spacer(Modifier.height(AevumSpacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                    .clickable(onClick = onOpenLifeView)
                    .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Text("⏳", fontSize = 18.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.insights_lifetime_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        stringResource(R.string.insights_lifetime_subtitle),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun PeriodSelector(
    selected: InsightPeriod,
    onSelect: (InsightPeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        InsightPeriod.values().forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = {
                    Text(
                        when (period) {
                            InsightPeriod.Today -> stringResource(R.string.common_today)
                            InsightPeriod.Week -> stringResource(R.string.insights_period_this_week)
                            InsightPeriod.Month -> stringResource(R.string.insights_period_this_month)
                        }
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun BreakdownToggle(
    mode: BreakdownMode,
    onSelect: (BreakdownMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        FilterChip(
            selected = mode == BreakdownMode.Activity,
            onClick = { onSelect(BreakdownMode.Activity) },
            label = { Text(stringResource(R.string.insights_breakdown_activity)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        FilterChip(
            selected = mode == BreakdownMode.Category,
            onClick = { onSelect(BreakdownMode.Category) },
            label = { Text(stringResource(R.string.insights_breakdown_category)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Category,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
    }
}

@Composable
internal fun TopSliceRow(
    slice: TopActivitySlice,
    maxMs: Long,
    index: Int,
    testTag: String? = null,
    // M18.146: optionaler Klick (öffnet die Detail-Statistikseite der
    // Aktivität). Null = Zeile bleibt rein informativ (Kategorie-Modus,
    // bestehende Aufrufer/Tests).
    onClick: (() -> Unit)? = null
) {
    val progress = (slice.durationMs.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = (if (testTag == null) Modifier else Modifier.testTag(testTag))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // M18.13: Icon in farbigem Kreis statt nacktem Punkt
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(slice.color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (slice.icon.isBlank()) "•" else slice.icon,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            Text(
                slice.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${slice.percent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(AevumSpacing.sm))
            Text(
                formatDuration(slice.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(4.dp))
        AnimatedGradientBar(
            progress = progress,
            color = slice.color,
            modifier = Modifier.fillMaxWidth(),
            // Kaskaden-Delay: 80ms pro Item
            animationDelayMs = index * 80
        )
    }
}

@Composable
private fun ChangeRow(change: PeriodChange, index: Int) {
    val percent = change.percentDelta
    val color = when {
        change.deltaMs > 0 -> Color(0xFF66BB6A)
        change.deltaMs < 0 -> Color(0xFFEF5350)
        else -> MaterialTheme.colorScheme.outline
    }
    val sign = when {
        change.deltaMs > 0 -> "+"
        change.deltaMs < 0 -> "−"
        else -> ""
    }
    val pct = percent?.let { " ($sign${kotlin.math.abs(it)}%)" }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(change.color)
        )
        Spacer(Modifier.width(AevumSpacing.sm))
        Text(
            change.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${sign}${formatDuration(kotlin.math.abs(change.deltaMs))}$pct",
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val total = ms / 60_000L
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ----------------------------------------------------------------------
// M18.146: Perioden-Navigation + Zeitraum-Auswahl-Sheet
// ----------------------------------------------------------------------

/** Test-Tag der Navigationszeile gesamt. */
internal const val INSIGHTS_PERIOD_NAV_TEST_TAG = "insightsPeriodNav"

/** Test-Tag des ‹-Buttons. */
internal const val INSIGHTS_PERIOD_PREV_TEST_TAG = "insightsPeriodPrev"

/** Test-Tag des ›-Buttons. */
internal const val INSIGHTS_PERIOD_NEXT_TEST_TAG = "insightsPeriodNext"

/** Test-Tag des Label-Buttons (öffnet das Sheet). */
internal const val INSIGHTS_PERIOD_LABEL_TEST_TAG = "insightsPeriodLabel"

/** Test-Tag des "Zurück zu jetzt"-Chips (nur in der Vergangenheit sichtbar). */
internal const val INSIGHTS_PERIOD_RESET_TEST_TAG = "insightsPeriodReset"

/** Test-Tag eines Eintrags im Auswahl-Sheet. */
internal fun periodPickerItemTestTag(index: Int): String = "periodPickerItem_$index"

/**
 * M18.146: Die Navigationszeile der Statistik — ‹ konkretes Label ›.
 *
 * WARUM NEBEN DEM PERIOD-TOGGLE: Der Toggle wählt die GRANULARITÄT
 * (Tag/Woche/Monat), die Navigationszeile BEWEGT sich darin. Beides gehört
 * sichtbar getrennt: sonst wüsste niemand, ob ein Klick auf „Woche" jetzt
 * umschaltet oder eine Woche weiterblättert.
 *
 * Das Label ist klickbar und öffnet das Auswahl-Sheet ([PeriodPickerSheet]) —
 * dort lässt sich „jeweils durch die Wochen (mit Datum und Wochennummer) und
 * Monate und Tage durchskippen". Bei einer vergangenen Periode erscheint
 * zusätzlich ein dezenter „Zurück zu jetzt"-Chip.
 */
@Composable
internal fun PeriodNavigatorRow(
    uiState: InsightsUiState,
    onStep: (Long) -> Unit,
    onReset: () -> Unit,
    onOpenPicker: () -> Unit
) {
    val today = java.time.LocalDate.now()
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = PeriodLabels.periodLabel(uiState.selectedPeriod, uiState.anchorDate, today, context)
    val isPast = PeriodLabels.normalize(uiState.selectedPeriod, uiState.anchorDate) !=
        PeriodLabels.normalize(uiState.selectedPeriod, today)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(INSIGHTS_PERIOD_NAV_TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        // ‹ — eine Periode zurück
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .clickable { onStep(-1L) }
                .testTag(INSIGHTS_PERIOD_PREV_TEST_TAG),
            contentAlignment = Alignment.Center
        ) {
            Text("‹", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        // Label — öffnet das Auswahl-Sheet
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AevumRadius.full))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .clickable(onClick = onOpenPicker)
                .testTag(INSIGHTS_PERIOD_LABEL_TEST_TAG)
                .padding(horizontal = AevumSpacing.md, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                // ▾-Symbol: das Label ist ein Picker, kein Button.
                Text("⌄", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        // "Zurück zu jetzt" — nur, wenn eine vergangene Periode gezeigt wird.
        if (isPast) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(onClick = onReset)
                    .testTag(INSIGHTS_PERIOD_RESET_TEST_TAG)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.insights_picker_now_marker),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // › — eine Periode vor (in die Zukunft maximal bis heute sinnvoll;
        // die Pauschalen-Logik zählt nur bis heute, leere Zukunft bleibt leer).
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .clickable { onStep(1L) }
                .testTag(INSIGHTS_PERIOD_NEXT_TEST_TAG),
            contentAlignment = Alignment.Center
        ) {
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * M18.146: Zeitraum-Auswahl-Sheet — die letzten 12 Perioden der gewählten
 * Granularität, neueste zuerst, mit Datum UND (bei Wochen) Wochennummer.
 * Die aktuell gezeigte Periode trägt die Auswahl-Markierung, „jetzt" eine
 * dezente Zweitmarkierung.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PeriodPickerSheet(
    period: InsightPeriod,
    anchor: java.time.LocalDate,
    onSelect: (java.time.LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val today = java.time.LocalDate.now()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickerItems = remember(period, anchor, today) {
        PeriodLabels.pickerItems(period, anchor, today, context = context)
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AevumSpacing.lg)
        ) {
            Text(
                stringResource(R.string.insights_picker_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.sm)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = AevumSpacing.lg,
                    vertical = AevumSpacing.xs
                ),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                items(
                    count = pickerItems.size,
                    key = { index -> pickerItems[index].label }
                ) { index ->
                    val item = pickerItems[index]
                    val isSelected = item.isShown
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AevumRadius.md))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(item.anchor) }
                            .testTag(periodPickerItemTestTag(index))
                            .padding(horizontal = AevumSpacing.md, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.label,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.isCurrent) {
                            Text(
                                stringResource(R.string.insights_picker_now_marker),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

