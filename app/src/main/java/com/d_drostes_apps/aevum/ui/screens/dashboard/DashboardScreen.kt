package com.d_drostes_apps.aevum.ui.screens.dashboard

import com.d_drostes_apps.aevum.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.data.model.AppUsageSample
import com.d_drostes_apps.aevum.ui.components.AnimatedGradientBar
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.BubbleStream
import com.d_drostes_apps.aevum.ui.components.FlipTimeText
import com.d_drostes_apps.aevum.ui.components.OrbitLauncherSheet
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.QualityOverrideDialog
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.ui.components.shimmering
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.ui.screens.goals.GoalAccents
import com.d_drostes_apps.aevum.ui.screens.goals.GoalCelebrationEvent
import com.d_drostes_apps.aevum.ui.screens.goals.GoalCelebrationOverlay
import com.d_drostes_apps.aevum.ui.screens.goals.GoalWithProgress
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon

/**
 * M18.7: KOMPLETT NEUES Dashboard — "Der Puls deines Tages".
 *
 * Design-Philosophie (nach User-Feedback "App hat Fokus verloren,
 * überladen"):
 *
 *   Nur Daten, die die eine Frage beantworten:
 *   "Wie gut habe ich meine Zeit heute genutzt?"
 *
 * Layout (Top → Bottom, nach Wichtigkeit):
 *  1. Puls-Hero (M19.5: beim Scrollen GEPINNT — „der komplette obere
 *     bereich bleibt stehen", das Sheet rutscht darüber) — Tag-Navigation,
 *     die 3 fundamentalen Zeit-Blöcke (Erfasst / Schlaf / Bildschirm),
 *     das Güte-Gauge (Maskottchen, wenn aktiviert) und darunter der
 *     Live-Banner (Aufnahme läuft) bzw. die Bereitschafts-Zeile.
 *     QualityRing (Zeitqualität) + Tagesfluss + Tagesfortschritt: eine
 *     Karte, vier Aussagen.
 *  3. Schnellstart — LiveActivityCard NUR wenn nichts läuft. Sonst
 *     übernimmt das Banner die Kontrolle (keine Dopplung).
 *  4. Reflexions-Karte — "Wo deine Zeit hingeht" (Top-5-Kategorien,
 *     Balkenbreite = Dauer, Balkenfarbe = Positivität rot→grün) und
 *     Insights (max 2) in EINER Karte (M18.81: zwei Sektionen fusioniert,
 *     weniger Scrolltiefe).
 *  5. Review-Hinweis — nur wenn Vorschläge warten.
 *
 * ENTFERNT (bewusst, gegen Überladung):
 *  - 5 KeyMetric-Karten (Erfasst/Fokus/Schlaf/Bewegung/Bildschirm/Top-Kat):
 *    Fokus/Bewegung/Top-Kat sind Interpretationen, keine Fakten. Die
 *    Top-Kategorie zeigt bereits der Balken-Block. Die 3 fundamentalen
 *    Blöcke leben jetzt im Hero.
 *  - TopAppsStrip: Top-Apps sind Detail-Data (gehören in die Statistik),
 *    nicht aufs Dashboard. Die App soll Zeit-QUALITÄT zeigen, nicht
 *    App-Nutzung.
 *  - "DEIN TAG"-Textblock mit narrative: Text reduziert auf das Minimum.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onOpenTimeline: () -> Unit = {},
    onOpenReview: () -> Unit = onOpenTimeline,
    onOpenSleepStatus: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    // M18.37: Todos-Karte auf dem Dashboard
    onOpenTodos: () -> Unit = {},
    // M21: Ziele auf dem Dashboard — Karte öffnet den Ziele-Screen,
    // ein Tipp auf ein Ziel dessen Detail-Ansicht.
    onOpenGoals: () -> Unit = {},
    onOpenGoalDetail: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
    goalsViewModel: DashboardGoalsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val dashboardGoals by goalsViewModel.goals.collectAsState()
    val goalCelebration by goalsViewModel.celebration.collectAsState()
    val liveState by viewModel.liveActivityManager.liveState.collectAsState()
    val nowMs by viewModel.liveActivityManager.nowMs.collectAsState()
    val recents by viewModel.liveActivityManager.recentActivityTypes.collectAsState()
    val favorites by viewModel.liveActivityManager.favoriteActivityTypes.collectAsState()
    // M18.58: Güte-Verlauf (7/30/365 Tage)
    val qualityTrend by viewModel.qualityTrend.collectAsState()
    // M18.58: Garmin-Kachel-Daten
    val garminSummary by viewModel.garminSummary.collectAsState()
    val garminActivities by viewModel.garminActivities.collectAsState()
    // M18.66-FIX3: Aktuelle Geofence-Zone für den Zone-Banner
    val currentZone by viewModel.currentZone.collectAsState()
    val zoneDebugInfo by viewModel.zoneDebugInfo.collectAsState()
    // M19.5: Maskottchen-Konfiguration (Default aus — dann verhält sich
    // alles exakt wie vorher).
    val mascotConfig by viewModel.mascotConfig.collectAsState()
    DashboardContent(
        modifier = modifier,
        state = state,
        liveState = liveState,
        nowMs = nowMs,
        recents = recents,
        favorites = favorites,
        qualityTrend = qualityTrend,
        garminSummary = garminSummary,
        garminActivities = garminActivities,
        currentZone = currentZone,
        zoneDebugInfo = zoneDebugInfo,
        mascotConfig = mascotConfig,
        onOpenTimeline = onOpenTimeline,
        onOpenReview = onOpenReview,
        onOpenSleepStatus = onOpenSleepStatus,
        onOpenUsageSettings = onOpenUsageSettings,
        onOpenTodos = onOpenTodos,
        // M21: Ziele
        goals = dashboardGoals,
        goalsCelebration = goalCelebration,
        onOpenGoals = onOpenGoals,
        onOpenGoalDetail = onOpenGoalDetail,
        onGoalCheckIn = goalsViewModel::checkIn,
        onGoalUndoCheckIn = goalsViewModel::undoLastCheckin,
        onConsumeGoalCelebration = goalsViewModel::consumeCelebration,
        onStartLive = viewModel::startLiveActivity,
        onPauseLive = viewModel::pauseLiveActivity,
        onResumeLive = viewModel::resumeLiveActivity,
        onStopLive = viewModel::stopLiveActivity,
        onDiscardLive = viewModel::discardLiveActivity,
        onToggleFavorite = viewModel::toggleFavorite,
        // M18.12: Neue Aktivität anlegen + starten
        onCreateActivity = viewModel::createAndStartActivity,
        // M18.23: Aktivität wechseln
        onSwitchLive = viewModel::switchActivity,
        // M18.60: Tages-Navigation + Pauschal-Overrides
        onNavigateDay = viewModel::navigateDay,
        onResetToToday = viewModel::resetToToday,
        // M18.100: Kalender-Popup (Datum im Hero-Kopf)
        onJumpToDate = viewModel::jumpToDate,
        onSetAllowanceOverride = viewModel::setAllowanceOverride,
        onClearAllowanceOverride = viewModel::clearAllowanceOverride,
        // AEVUM-3: Tages-Güte manuell anpassen (Tipp auf die Güte-Zahl).
        onSetDayQuality = viewModel::setDayQualityOverride
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    modifier: Modifier = Modifier,
    state: DashboardUiState,
    liveState: LiveActivityState,
    nowMs: Long,
    recents: List<com.d_drostes_apps.aevum.domain.liveactivity.RecentActivityType>,
    favorites: List<com.d_drostes_apps.aevum.data.model.ActivityType>,
    // M18.58: Güte-Verlauf-Daten
    qualityTrend: List<DailyQualityPoint> = emptyList(),
    // M18.58: Garmin-Kachel-Daten
    garminSummary: com.d_drostes_apps.aevum.data.model.GarminDailySummary? = null,
    garminActivities: List<com.d_drostes_apps.aevum.data.model.GarminActivity> = emptyList(),
    // M18.66-FIX3: Aktuelle Geofence-Zone für den Zone-Banner
    currentZone: com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider.ZoneInfo? = null,
    zoneDebugInfo: String = "",
    // M19.5: Maskottchen-Konfiguration (enabled = false ⇒ exakt altes
    // Verhalten: Güte-Zahl statt Wesen).
    mascotConfig: com.d_drostes_apps.aevum.data.repository.MascotRepository.MascotConfig =
        com.d_drostes_apps.aevum.data.repository.MascotRepository.MascotConfig(),
    onOpenTimeline: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenSleepStatus: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    // M18.37: Todos-Karte auf dem Dashboard
    onOpenTodos: () -> Unit = {},
    // M21: Ziele-Karte
    goals: List<GoalWithProgress> = emptyList(),
    goalsCelebration: GoalCelebrationEvent? = null,
    onOpenGoals: () -> Unit = {},
    onOpenGoalDetail: (String) -> Unit = {},
    onGoalCheckIn: (String) -> Unit = {},
    onGoalUndoCheckIn: (String) -> Unit = {},
    onConsumeGoalCelebration: () -> Unit = {},
    onStartLive: (String, String?, Long) -> Unit,
    onPauseLive: () -> Unit,
    onResumeLive: () -> Unit,
    onStopLive: () -> Unit,
    onDiscardLive: () -> Unit = {},
    onToggleFavorite: (com.d_drostes_apps.aevum.data.model.ActivityType) -> Unit,
    // M18.12: Neue Aktivität anlegen + starten
    // M18.93v8: Icon kommt mit (IKEA-Effekt).
    onCreateActivity: (String, String) -> Unit = { _, _ -> },
    // M18.23: Aktivität wechseln
    onSwitchLive: (String, String?) -> Unit = { _, _ -> },
    // M18.60: Tages-Navigation + Pauschal-Overrides
    onNavigateDay: (Int) -> Unit = {},
    onResetToToday: () -> Unit = {},
    // M18.100: Kalender-Popup — direkt zu einem Datum springen
    onJumpToDate: (java.time.LocalDate) -> Unit = {},
    onSetAllowanceOverride: (String, Int) -> Unit = { _, _ -> },
    onClearAllowanceOverride: (String) -> Unit = {},
    // AEVUM-3: Tages-Güte manuell anpassen (Tipp auf die Güte-Zahl im Hero).
    // score = null entfernt die Overrides des Tages (automatische Berechnung).
    onSetDayQuality: (Int?) -> Unit = {}
) {
    val isLive = liveState is LiveActivityState.Running || liveState is LiveActivityState.Paused
    // M18.58: Das +-Popup (fancy Bottom-Sheet mit allen Start-Optionen).
    var showStartPicker by remember { mutableStateOf(false) }
    // M18.60: Pauschal-Popup — welche Pauschale wurde angeklickt?
    var allowancePopup by remember { mutableStateOf<AllowancePopupTarget?>(null) }
    // AEVUM-3: Tages-Güte-Popup — Tipp auf die Güte-Zahl (QualityRing)
    // im Hero öffnet den Slider für den GEWÄHLTEN Tag. Der Override wird
    // auf die Sessions des Tages geschrieben, nie auf die Einstellung.
    var showQualityDialog by remember { mutableStateOf(false) }
    // M18.100: Kalender-Popup — Klick auf das Datum im Hero-Kopf öffnet
    // den DatePicker; die Auswahl springt direkt zu dem Tag.
    var showDatePickerDialog by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            DashboardAtmosphere()
            // M18.96/M18.100/M18.101, neu geordnet in M19.4/M19.5:
            // ZWEI-EBENEN-LAYOUT. Ebene 1 (hinten): die Deko-Farbfläche
            // (DashboardHeroBackground, fest am oberen Rand, mit Überstand
            // unter den Hero). Ebene 2 (vorn): die LazyColumn mit dem
            // Hero-INHALT als Item 0 (klickbar — die Pfeile waren vor
            // M18.100 unklickbar, weil die transparente
            // contentPadding-Fläche der LazyColumn alle Taps im
            // Hero-Bereich schluckte) und EINEM Sheet-Item, das sich beim
            // Scrollen über den Hero legt. M19.5: Der Hero ist beim
            // Scrollen GEPINNT (graphicsLayer-Kompensation gegen den
            // Scroll) — er steht still, nur das Sheet rutscht darüber.
            val listState = rememberLazyListState()
            val density = androidx.compose.ui.platform.LocalDensity.current
            // M19.4/M19.5: Die Hero-Höhe wird GEMESSEN (das neue Cockpit
            // ist höher als die alte Konstante) — Pinning-Kompensation und
            // Deko-Fläche hängen beide an heroItemHeightPx.
            var heroItemHeightPx by remember { mutableIntStateOf(0) }
            // M19.5-DYNAMIK (Devon: „Ich will, wenn ich runter scrolle,
            // dass der komplette obere bereich stehen bleibt und der untere
            // bereich darüber rutscht."): KEIN Collapse mehr — der Hero
            // wird per graphicsLayer EXAKT gegen den Scroll verschoben
            // (netto steht er still, als wäre er gepinnt), und nur das
            // Sheet-Item wandert beim Scrollen über ihn. Der frühere
            // Fade-/Scale-/Parallax-Effekt ist komplett entfernt.
            //
            // Die Deko-Fläche bleibt FEST am oberen Rand stehen; sie reicht
            // DashboardHeroPeek UNTER den Hero hinaus — durch die
            // abgerundeten Sheet-Ecken schimmert sie hindurch, das Sheet
            // liegt optisch ÜBER dem Farbbereich.
            val heroBgHeight = if (heroItemHeightPx > 0) {
                with(density) { heroItemHeightPx.toDp() + DashboardHeroPeek }
            } else {
                DashboardHeroFallbackHeight
            }
            DashboardHeroBackground(
                height = heroBgHeight,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            // M18.60-FIX (User: "Tage wechseln — da sollte eine Animation
            // passieren, die das gesamte Fragment nach links/rechts
            // verschiebt"): AnimatedContent um den kompletten Inhalt,
            // getriggert durch das angezeigte Datum. Vor: Tag-Wechsel
            // ohne jede visuelle Reaktion. Jetzt: Slide-Richtung je
            // Navigationsrichtung (tagKey enthaelt das Datum; die
            // Richtung wird ueber den Vergleich der LocalDates bestimmt).
            val dayKey = state.displayedDate.toString()
            // M21: Güte-Verlauf nur am heutigen Tag zeigen (Nutzerwunsch:
            // beim Tages-Skippen komplett ausblenden).
            val isShowingToday = state.displayedDate == java.time.LocalDate.now()
            var lastDayKey by remember { mutableStateOf(dayKey) }
            var slideDirection by remember { mutableStateOf(1) }
            if (lastDayKey != dayKey) {
                val old = java.time.LocalDate.parse(lastDayKey)
                val new = java.time.LocalDate.parse(dayKey)
                slideDirection = if (new.isAfter(old)) 1 else -1
                lastDayKey = dayKey
            }
            androidx.compose.animation.AnimatedContent(
                targetState = dayKey,
                transitionSpec = {
                    val offset = slideDirection * 80
                    (
                        androidx.compose.animation.slideInHorizontally(initialOffsetX = { offset }) +
                            androidx.compose.animation.fadeIn(animationSpec = tween(300))
                        ) togetherWith (
                        androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -offset }) +
                            androidx.compose.animation.fadeOut(animationSpec = tween(200))
                        )
                },
                label = "day-slide"
            ) { _ ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f),
            // M19.4: KEIN statusBarsPadding mehr an der Liste — der Hero
            // bringt es selbst mit (Item 0), und das Sheet läuft beim
            // Hochscrollen randlos unter die Statusleiste. Seitliches
            // Padding sitzt im Hero bzw. im Sheet.
            contentPadding = PaddingValues(bottom = AevumSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // 0) M19.5: Hero-Inhalt als Item 0 — GEPINNT. Beim Scrollen
            // wird er per graphicsLayer exakt gegen den Scroll verschoben
            // (netto steht er still — „der komplette obere bereich bleibt
            // stehen"), während das Sheet-Item unbeeinflusst über ihn
            // rutscht („der untere bereich rutscht darüber").
            item(key = "hero") {
                Box(
                    modifier = Modifier
                        .onSizeChanged { size ->
                            if (size.height > 0) heroItemHeightPx = size.height
                        }
                        .graphicsLayer {
                            translationY = DashboardHeroMetrics.heroStickyTranslation(
                                offsetPx = listState.firstVisibleItemScrollOffset,
                                heroHeightPx = heroItemHeightPx,
                                belowHero = listState.firstVisibleItemIndex > 0
                            ).toFloat()
                        }
                ) {
                    DashboardHeroContent(
                        state = state,
                        liveState = liveState,
                        nowMs = nowMs,
                        currentZone = currentZone,
                        mascot = mascotConfig,
                        onQualityClick = { showQualityDialog = true },
                        onOpenTimeline = onOpenTimeline,
                        onPauseLive = onPauseLive,
                        onResumeLive = onResumeLive,
                        onStopLive = onStopLive,
                        onSwitchLive = onSwitchLive,
                        onPreviousDay = { onNavigateDay(-1) },
                        onNextDay = { onNavigateDay(1) },
                        onResetToToday = onResetToToday,
                        onDateClick = { showDatePickerDialog = true }
                    )
                }
            }
            // 1) M19.4: DAS SHEET — eine große, oben abgerundete Fläche
            // mit allen Karten des Dashboards; sie schiebt sich beim
            // Scrollen über den gepinnten Hero („das untere schiebt sich
            // über den oberen Bereich", Aufbau des Referenzbilds).
            item(key = "sheet") {
                DashboardSheet {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                        // M19.5 (Devon: „unter der anzeige der güte nicht
                        // das … sondern der kasten … der mit der animation
                        // die aktuelle aufzeichnung anzeigt"): Der Live-
                        // Banner lebt jetzt IM HERO (direkt unter dem
                        // Güte-Gauge) — im Sheet bleibt er weg, damit
                        // nichts doppelt erscheint.
                        //
                        // M18.100/M19.4: Nur die Deko-Farbfläche liegt als
                        // eigene Ebene HINTER der LazyColumn
                        // (DashboardHeroBackground) und wird beim Scrollen
                        // vom Sheet überdeckt.

                        // M21 (Devon: „Die Ziele hätte ich gerne richtig fancy
                        // im dashboard abgebildet"): Ziele-Block ganz oben im
                        // Sheet — farbige Schimmer-Bar je Ziel, Abhaken für
                        // manuelle Ziele direkt hier.
                        if (goals.isNotEmpty()) {
                            DashboardGoalsCard(
                                goals = goals,
                                onCheckIn = onGoalCheckIn,
                                onUndo = onGoalUndoCheckIn,
                                onOpenGoal = onOpenGoalDetail,
                                onOpenAll = onOpenGoals
                            )
                        }

                        // M18.58: Güte-Verlauf — 7/30/365-Tage-Statistik
                        // (User-Wunsch: "Statistik über den Güte Verlauf
                        // der letzten Tage, wobei man INNERHALB der
                        // Statistik einstellen können soll, ob in den
                        // letzten 7, 30, 365 Tagen").
                        // M21: NUR am heutigen Tag — beim Blättern durch
                        // die Tage komplett ausblenden (Nutzerwunsch).
                        if (isShowingToday) {
                            QualityTrendCard(trend = qualityTrend)
                        }

                        // M18.58: Garmin-Kacheln — moderne kleine Kacheln
                        // (User-Wunsch). Nur sichtbar, wenn Daten
                        // synchronisiert wurden.
                        GarminTilesRow(
                            sleepDurationMs = state.lastSleepDurationMs,
                            summary = garminSummary,
                            activities = garminActivities
                        )

                        // M18.81 (Dashboard-Redesign): Reflexion — "Wo
                        // deine Zeit hingeht" (Top-5 mit Score-Farbe) +
                        // Insights jetzt in EINER Karte. Vorher zwei
                        // getrennte Sektionen.
                        if (state.qualityBreakdown.isNotEmpty() || state.insights.isNotEmpty()) {
                            DayReflectionCard(
                                state = state,
                                onAllowanceClick = { id, name, minutes ->
                                    allowancePopup = AllowancePopupTarget(
                                        allowanceId = id,
                                        name = name,
                                        currentMinutes = minutes,
                                        hasOverride = state.allowanceOverrides.containsKey(id)
                                    )
                                }
                            )
                        }

                        // M18.37: Kompakte Todos-Karte — das Herzstueck
                        // zeigt, was heute noch ansteht. Nur sichtbar,
                        // wenn Todos existieren.
                        if (state.todoTotalCount > 0) {
                            DashboardTodosCard(
                                done = state.todoDoneCount,
                                open = state.todoOpenCount,
                                total = state.todoTotalCount,
                                onOpenTodos = onOpenTodos
                            )
                        }

                        // M21: Der eigene Pauschalen-Block ist entfallen —
                        // Pauschalen stehen jetzt als Zeile in „Wo deine
                        // Zeit hingeht" (mit Suffix „Pauschale", klickbar
                        // wie zuvor für den Tages-Override).

                        // 6) Review-Hinweis — nur wenn Vorschläge warten
                        if (state.candidateCount > 0 || state.sleepCandidateCount > 0) {
                            ReviewHintCard(reviewCount = state.candidateCount, sleepCount = state.sleepCandidateCount, onOpenReview = onOpenReview)
                        }
                    } // Ende Sheet-Column
                } // Ende DashboardSheet
            } // Ende Sheet-Item
            } // Ende LazyColumn (innerhalb AnimatedContent)
            } // Ende AnimatedContent

            // M18.58: Schwebender +-Button unten rechts (User: "der gesamte
            // Bereich zum starten einer Activity kann weg. Stattdessen
            // könnte man irgendwo ein + hin machen. Dieses sollte aber
            // passend in eine moderne App integriert werden.").
            // M18.60-FIX (User: "Solange eine Aktivität aufgezeichnet wird
            // kann der floating button verschwinden"): Nur sichtbar, wenn
            // KEINE Session läuft (das Banner übernimmt dann die Steuerung).
            // Animierter FAB: dreht sich beim Öffnen.
            if (!isLive) {
                FloatingPlusButton(
                    expanded = showStartPicker,
                    onClick = { showStartPicker = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = AevumSpacing.lg, bottom = AevumSpacing.lg)
                )
            }

        }
    }

    // M18.82: Das Orbit-Launcher-Overlay löst das alte Bottom-Sheet ab —
    // Sternenhimmel mit Planeten (Favoriten innen), Tap auf Planet ->
    // Detail-Panel (Sofort / Vorlaufzeit / exakte Uhrzeit).
    if (showStartPicker) {
        OrbitLauncherSheet(
            activityTypes = state.activityTypes,
            recents = recents,
            onStart = { typeId ->
                onStartLive(typeId, null, System.currentTimeMillis())
                showStartPicker = false
            },
            onStartWithTime = { typeId, note, time ->
                onStartLive(typeId, note, time)
                showStartPicker = false
            },
            onCreateActivity = onCreateActivity,
            onDismiss = { showStartPicker = false }
        )
    }

    // M18.60: Pauschal-Popup — Bearbeiten/Loeschen der Pauschale NUR fuer
    // den angezeigten Tag. Loeschen mit Bestaetigungsdialog, Bearbeiten
    // mit fancy Minuten-Picker (Slider + Schnell-Buttons + Live-Anzeige).
    val popupTarget = allowancePopup
    if (popupTarget != null) {
        AllowanceDayPopup(
            target = popupTarget,
            displayedDate = state.displayedDate,
            onDismiss = { allowancePopup = null },
            onEdit = { allowanceId, minutes ->
                onSetAllowanceOverride(allowanceId, minutes)
                allowancePopup = null
            },
            onDelete = { allowanceId ->
                onClearAllowanceOverride(allowanceId)
                allowancePopup = null
            }
        )
    }

    // M21: Feier-Overlay für erreichte Ziele — auch auf dem Dashboard,
    // wenn ein Ziel hier (oder im Hintergrund) erfüllt wird. Der
    // [GoalCelebrationStore] dedupliziert je Ziel+Periode, damit die Feier
    // nicht doppelt im Ziele-Screen erscheint.
    GoalCelebrationOverlay(
        event = goalsCelebration,
        onDismiss = onConsumeGoalCelebration
    )

    // AEVUM-3: Tages-Güte-Dialog — Tipp auf die Güte-Zahl im Hero.
    // Der Slider passt die Güte des GEWÄHLTEN Tages an (Override auf die
    // Sessions des Tages). Die Einstellung bleibt unverändert; am nächsten
    // Tag gilt wieder die automatische Berechnung.
    if (showQualityDialog) {
        QualityOverrideDialog(
            title = stringResource(R.string.dashboard_quality_day_title),
            message = stringResource(
                R.string.dashboard_quality_day_message,
                state.displayedDate.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM"))
            ),
            initialScore = state.qualityScore,
            hasOverride = state.hasDayQualityOverride,
            onDismiss = { showQualityDialog = false },
            onSave = { score ->
                onSetDayQuality(score)
                showQualityDialog = false
            }
        )
    }

    // M18.100: Kalender-Popup — Klick auf das Datum im Hero-Kopf.
    // Material3-DatePicker; Auswahl springt direkt zu dem Tag.
    if (showDatePickerDialog) {
        val selectedMillis = remember(state.displayedDate) {
            state.displayedDate
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedMillis,
            yearRange = IntRange(
                java.time.LocalDate.now().year - 1,
                java.time.LocalDate.now().year
            )
        )
        DatePickerDialog(
            onDismissRequest = { showDatePickerDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                        onJumpToDate(picked)
                    }
                    showDatePickerDialog = false
                }) {
                    Text(stringResource(R.string.dashboard_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** M18.60: Klick-Ziel des Pauschal-Popups. */
private data class AllowancePopupTarget(
    val allowanceId: String,
    val name: String,
    val currentMinutes: Int,
    val hasOverride: Boolean
)

/**
 * M18.58: Der schwebende +-Button. Modern futuristisch: Gradient,
 * weicher Schatten, rotierendes Plus beim Öffnen des Popups.
 */
@Composable
private fun FloatingPlusButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "fab-rotation"
    )
    Box(
        modifier = modifier
            .size(60.dp)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary
                    )
                )
            )
            // M19.3: Der Schimmer der App wandert auf ihren Haupt-Knopf —
            // sein bisheriger Platz (Projektmanagement-Knopf) ist mit dem
            // PM-Rückbau entfallen, die Idee („gerne auch mit schimmern bei
            // manchen Buttons") bleibt.
            .shimmering(intensity = 0.22f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "+",
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

@Composable
private fun DashboardAtmosphere() {
    // M19.4: Orbs in der App-Identität (Eisblau/Feuerrot/Gold) statt der
    // alten Lila/Mint-Töne der Ur-Theme — dieselbe ruhige Tiefe, aber
    // konsistent mit M19.2.
    val primary = MaterialTheme.colorScheme.primary
    val fire = MaterialTheme.colorScheme.secondary
    val gold = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = primary.copy(alpha = 0.07f),
            radius = size.minDimension * 0.52f,
            center = Offset(size.width * 0.92f, size.height * 0.04f)
        )
        drawCircle(
            color = fire.copy(alpha = 0.045f),
            radius = size.minDimension * 0.46f,
            center = Offset(size.width * 0.05f, size.height * 0.66f)
        )
        drawCircle(
            color = gold.copy(alpha = 0.045f),
            radius = size.minDimension * 0.36f,
            center = Offset(size.width * 0.88f, size.height * 0.86f)
        )
    }
}

/**
 * M18.7: Puls-Hero — das neue Herzstück des Dashboards.
 *
 * M18.96: VERSCHOBEN nach DashboardHero.kt (DashboardHeroLayer) — der
 * Hero ist jetzt eine eigene Ebene HINTER der LazyColumn (Kalorien-
 * Tracker-Optik: Inhalt schiebt sich beim Scrollen über den Hero).
 * Diese Datei enthält nur noch die Content-Karten der LazyColumn.
 */

/** M18.7: Eine Kennzahl-Zeile im Hero: Emoji + Wert (Monospace) + Label. */

/**
 * M18.60: Animierte Hero-Metrik — der Wert zaehlt beim Tag-Wechsel
 * (oder Daten-Update) von alt zu neu (600ms, FastOutSlowIn). Liefert
 * den "Werte laden sich auf"-Effekt, den der User beim Dashboard-
 * Tageswechsel sehen will.
 */

/** M18.7: Tagesfortschritt als dünne Gradient-Bar mit Prozent. */

/**
 * Mini Day-Flow Canvas — kompakt, mit "jetzt"-Marker.
 */

/**
 * M18.81 (Dashboard-Redesign): Reflexions-Karte — "Wo deine Zeit hingeht"
 * (Top 5, animierte Bars) + bis zu 2 Insights in EINER Karte. Fusion der
 * beiden Einzel-Sektionen gegen Scrolltiefe (UI_GUIDELINES: Kartenanzahl
 * reduzieren).
 */
// ── M21: Ziele-Karte („Dopamin-Block") ──────────────────────────────────

/**
 * Ziele auf dem Dashboard: je Ziel eine farbige Schimmer-Bar mit
 * animiertem Fortschritt, Streak-Flamme und — bei manuellen Zielen —
 * einem direkten Abhak-Knopf. Ein Tipp auf die Zeile öffnet das Ziel,
 * „Alle" den Ziele-Screen.
 */
@Composable
private fun DashboardGoalsCard(
    goals: List<GoalWithProgress>,
    onCheckIn: (String) -> Unit,
    onUndo: (String) -> Unit,
    onOpenGoal: (String) -> Unit,
    onOpenAll: () -> Unit
) {
    val relevant = goals.filter { it.isDueToday || it.progress > 0f }
    val shown = relevant.take(DashboardGoalsViewModel.MAX_CARD_GOALS)
    val hidden = (relevant.size - shown.size).coerceAtLeast(0)

    AevumCard(variant = CardVariant.Elevated) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dashboard_goals_title),
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onOpenAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        stringResource(R.string.dashboard_goals_all),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            shown.forEach { goal ->
                DashboardGoalRow(
                    goal = goal,
                    onCheckIn = onCheckIn,
                    onUndo = onUndo,
                    onOpen = onOpenGoal
                )
            }
            if (hidden > 0) {
                Text(
                    stringResource(R.string.dashboard_goals_more, hidden),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenAll)
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun DashboardGoalRow(
    goal: GoalWithProgress,
    onCheckIn: (String) -> Unit,
    onUndo: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    val accent = GoalAccents.resolve(
        key = goal.goal.accent,
        activityColor = goal.activityColorLong,
        fallback = MaterialTheme.colorScheme.secondary
    )
    // Weiches Einlaufen des Balkens — beim Abhaken und bei Daten-Updates.
    val animatedProgress by animateFloatAsState(
        targetValue = goal.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "dashboard-goal-progress"
    )
    // Manuelle Ziele (Haken/Zähler) lassen sich hier direkt abhaken.
    val isManual = goal.goal.trackingMode == "MANUAL"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(goal.goal.id) }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                goal.goal.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (goal.streakCurrent >= 2) {
                Text(
                    "🔥${goal.streakCurrent}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
            Text(
                goal.progressText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isManual) {
                GoalQuickCheckButton(
                    accent = accent,
                    checked = goal.todayCheckins > 0,
                    onClick = { onCheckIn(goal.goal.id) }
                )
            }
        }
        GoalShimmerBar(
            progress = animatedProgress,
            accent = accent,
            met = goal.isMet
        )
        if (isManual && goal.todayCheckins > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.dashboard_goal_checked_today, goal.todayCheckins),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "↩",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onUndo(goal.goal.id) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** Kompakter Abhak-Knopf (28 dp) — „+" → „✓" sobald heute abgehakt. */
@Composable
private fun GoalQuickCheckButton(
    accent: Color,
    checked: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (checked) accent else accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (checked) Icons.Filled.Check else Icons.Filled.Add,
            contentDescription = null,
            tint = if (checked) Color.White else accent,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Farbige Fortschritts-Bar mit Schimmer. Bei erreichtem Ziel läuft der
 * Glanz schneller und stärker — der „Dopamin"-Effekt.
 */
@Composable
private fun GoalShimmerBar(progress: Float, accent: Color, met: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.78f), accent)
                        )
                    )
                    .shimmering(
                        enabled = true,
                        periodMillis = if (met) 2200 else 3800,
                        intensity = if (met) 0.45f else 0.22f
                    )
            )
        }
    }
}

@Composable
private fun DayReflectionCard(
    state: DashboardUiState,
    onAllowanceClick: (String, String, Int) -> Unit = { _, _, _ -> }
) {
    AevumCard(variant = CardVariant.Elevated) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            if (state.qualityBreakdown.isNotEmpty()) {
                QualityBreakdownBars(
                    slices = state.qualityBreakdown.take(5),
                    onAllowanceClick = onAllowanceClick
                )
            }
            if (state.insights.isNotEmpty()) {
                InsightStrip(state = state)
            }
        }
    }
}

@Composable
private fun InsightStrip(state: DashboardUiState) {
    // Show only the most important 2 insights — no card overload
    val top = state.insights.take(2)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        top.forEach { insight ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(AevumRadius.lg),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            ) {
                Row(
                    modifier = Modifier.padding(AevumSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(insight.icon, fontSize = 22.sp)
                    Column {
                        Text(insight.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            insight.message,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewHintCard(reviewCount: Int, sleepCount: Int, onOpenReview: () -> Unit) {
    AevumCard(variant = CardVariant.Outlined, onClick = onOpenReview) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (reviewCount > 0) {
                        stringResource(R.string.dashboard_review_suggestions, reviewCount)
                    } else {
                        stringResource(R.string.dashboard_review_sleep_suggestions, sleepCount)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.dashboard_review_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("→", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// Helper functions
private fun formatHours(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1200)
@Composable
private fun DashboardScreenPreview() {
    AevumTheme(darkTheme = true) {
        DashboardContent(
            state = DashboardUiState(
                headline = "Du bist gut im Flow.",
                narrative = "Mehrere Bereiche sind aktiv — du hast schon einiges erfasst.",
                currentActivity = "Deep Work",
                currentDuration = "1h 20m",
                balanceScore = 72,
                totalTracked = "5h 10m",
                openTime = "6h 30m",
                sessionCount = 3,
                reviewCount = 2,
                distribution = listOf(
                    DashboardCategorySlice("work", "Arbeit", 12_000_000),
                    DashboardCategorySlice("sport", "Sport", 3_000_000)
                ),
                timeline = listOf(DashboardTimelineRow("1", "08:00", "Deep Work", "Arbeit", "2h", "Erfasst", false)),
                flowSegments = listOf(
                    DashboardFlowSegment("1", "Deep Work", "Arbeit", "work", 8 * 60, 11 * 60, "08:00–11:00", "3h", false),
                    DashboardFlowSegment("2", "Sport", "Sport", "sport", 18 * 60, 19 * 60, "18:00–19:00", "1h", false)
                ),
                flowGaps = listOf(),
                currentMinute = 16 * 60,
                insights = listOf(
                    DashboardInsight("Größter Block", "Arbeit macht 70% deiner erfassten Zeit aus.", "◷"),
                    DashboardInsight("Kurz prüfen", "2 Vorschläge warten auf deine Entscheidung.", "✓")
                ),
                topCategory = "Arbeit",
                topCategoryDuration = "3h 20m",
                hasData = true,
                dayProgress = .64f,
                lastSleepDurationMs = 7L * 3_600_000 + 45 * 60_000,
                digitalScreenTimeFormatted = "2h 15m",
                qualityScore = 72,
                qualityBreakdown = listOf(
                    QualitySlice("fitness", "Fitness", 60L * 60_000, 85, positivityColor(85)),
                    QualitySlice("work", "Arbeit", 3L * 3_600_000 + 20 * 60_000, 50, positivityColor(50)),
                    QualitySlice("digital", "Digital", 2L * 3_600_000, 15, positivityColor(15))
                )
            ),
            liveState = LiveActivityState.Idle,
            nowMs = 0,
            recents = emptyList(),
            favorites = emptyList(),
            onOpenTimeline = {},
            onOpenReview = {},
            onStartLive = { _, _, _ -> },
            onPauseLive = {},
            onResumeLive = {},
            onStopLive = {},
            onToggleFavorite = {}
        )
    }
}

/**
 * M18.7: "Wo deine Zeit hingeht" — horizontale Balken pro Aktivität.
 * Balkenbreite = Dauer-Anteil, Balkenfarbe = Score (rot→gelb→grün).
 * Max 4 Einträge — die Top 4 des Tages. Kaskaden-Animation (80ms).
 *
 * M18.66-FIX16: Gleiche Optik wie Insights → Heute → "Top Aktivitäten":
 * Icon im farbigen Kreis, Prozent-Anteil, Dauer, animierte Bar. Top 5.
 */
@Composable
private fun QualityBreakdownBars(
    slices: List<QualitySlice>,
    onAllowanceClick: (String, String, Int) -> Unit = { _, _, _ -> }
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.dashboard_where_time_goes),
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        val maxMs = slices.maxOf { it.durationMs }.coerceAtLeast(1L)
        slices.forEachIndexed { index, slice ->
            // M21: Pauschalen stehen wie Aktivitäten in der Liste — eine
            // Zeile, gleiche Optik, aber klickbar (Tages-Override) und mit
            // Suffix „Pauschale" hinter dem Namen.
            val allowanceId = slice.allowanceId
            val clickableModifier = if (slice.isAllowance && allowanceId != null) {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onAllowanceClick(
                            allowanceId,
                            slice.label,
                            (slice.durationMs / 60_000L).toInt()
                        )
                    }
                    .padding(vertical = 2.dp)
            } else {
                Modifier.fillMaxWidth()
            }
            Column(modifier = clickableModifier) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    // M18.66-FIX16: Icon im farbigen Kreis (wie Insights)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(slice.color.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (slice.icon.isBlank()) "•" else slice.icon,
                            fontSize = 13.sp
                        )
                    }
                    Text(
                        if (slice.isAllowance) {
                            stringResource(R.string.dashboard_allowance_suffix, slice.label)
                        } else {
                            slice.label
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (slice.isAllowance) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    // M18.66-FIX16: Prozent-Anteil (wie Insights)
                    Text(
                        "${slice.percent}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        formatHours(slice.durationMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedGradientBar(
                    progress = slice.durationMs.toFloat() / maxMs.toFloat(),
                    color = slice.color,
                    modifier = Modifier.fillMaxWidth(),
                    height = 8.dp,
                    animationDelayMs = index * 80
                )
            }
        }
    }
}

/**
 * M18.37: Kompakte Todos-Karte auf dem Dashboard.
 *
 * Das Dashboard ist das Herzstueck und schon dicht — deshalb bewusst
 * MINIMAL: eine Zeile mit Icon, "X von Y erledigt", Fortschrittsbalken
 * und einem dezente Pfeil. Kein Listen-Detail, keine Checkboxen —
 * dafuer gibt es den Todos-Tab. Ein Tipp auf die Karte oeffnet Todos.
 */
@Composable
private fun DashboardTodosCard(
    done: Int,
    open: Int,
    total: Int,
    onOpenTodos: () -> Unit
) {
    val progress = if (total > 0) done.toFloat() / total else 0f
    val accent = MaterialTheme.colorScheme.primary
    AevumCard(
        variant = CardVariant.Gradient,
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AevumRadius.md))
                .clickable(onClick = onOpenTodos)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // Icon-Kreis
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("✅", fontSize = 16.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.dashboard_todos),
                    fontSize = 11.sp,
                    letterSpacing = 1.0.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (open > 0) {
                        stringResource(R.string.dashboard_todos_progress, open, done)
                    } else {
                        stringResource(R.string.dashboard_todos_done)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Dünner Fortschrittsbalken
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accent, MaterialTheme.colorScheme.tertiary)
                                )
                            )
                    )
                }
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * M18.37: Kompakte Pauschalen-Zeile auf dem Dashboard.
 *
 * Zeigt jede enabled Tagespauschale explizit (Name + Minuten/Tag) —
 * vorher gingen sie nur in der Gesamtsumme auf und der User sah
 * "Fertig machen 30m" nie. Bewusst schlank: eine Zeile pro Pauschale,
 * Chip-Optik, kein Scroll, kein Detail.
 *
 * M18.60: Jede Pauschale ist antippbar → Popup mit "Bearbeiten" +
 * "Löschen" für den angezeigten Tag. Overrides (abweichender Tageswert)
 * werden mit ✎-Marker + abweichender Minuten-Anzeige hervorgehoben.
 */
@Composable
private fun DashboardAllowancesRow(
    summary: List<Pair<Triple<String, String, Int>, Boolean>>,
    onAllowanceClick: (allowanceId: String, name: String, effectiveMinutes: Int) -> Unit
) {
    AevumCard(
        variant = CardVariant.Gradient,
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.dashboard_allowances),
                fontSize = 11.sp,
                letterSpacing = 1.0.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.dashboard_allowances_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            summary.forEach { (entry, hasOverride) ->
                val (id, name, minutes) = entry
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AevumRadius.sm))
                        .clickable { onAllowanceClick(id, name, minutes) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (hasOverride) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                            )
                    )
                    Text(
                        name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasOverride) {
                        Text(
                            "✎",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        stringResource(R.string.dashboard_minutes_value, minutes),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (hasOverride) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * M18.60/M18.81 (Dashboard-Redesign): Dezente Tag-Navigation — jetzt als
 * Chip-Zeile im Hero-Kopf statt als eigene Sektion unterm Hero (spart eine
 * Scroll-Sektion). Schlanke Pill-Zeile: ‹  Datum  ›. Wochentag +
 * "Heute"/"Gestern" statt des vollen Datums. Rechte Pfeiltaste gedimmt bei
 * "heute"; "Heute"-Reset-Chip nur sichtbar, wenn man weg navigiert hat.
 *
 * M18.96: VERSCHOBEN nach DashboardHero.kt (HeroDayNavigation) — der
 * Hero ist jetzt eine eigene Ebene hinter der LazyColumn.
 */

/**
 * M18.60: Popup für eine Tagespauschale am angezeigten Tag.
 *  - "Bearbeiten" → fancy Minuten-Picker (Slider + Schnell-Buttons),
 *    speichert einen Override NUR für diesen Tag.
 *  - "Löschen" → Bestätigungsdialog, entfernt den Override (bei
 *    Override) bzw. blendet die Pauschale für den Tag aus (bei
 *    Standard-Pauschale ohne Override).
 */
@Composable
private fun AllowanceDayPopup(
    target: AllowancePopupTarget,
    displayedDate: java.time.LocalDate,
    onDismiss: () -> Unit,
    onEdit: (allowanceId: String, minutes: Int) -> Unit,
    onDelete: (allowanceId: String) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val dateLabel = if (displayedDate == java.time.LocalDate.now()) stringResource(R.string.dashboard_today_lower) else
        displayedDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.dashboard_allowance_delete_title)) },
            text = {
                Text(
                    if (target.hasOverride) {
                        stringResource(
                            R.string.dashboard_allowance_delete_override,
                            dateLabel,
                            target.currentMinutes
                        )
                    } else {
                        stringResource(R.string.dashboard_allowance_delete_standard, dateLabel)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(target.allowanceId) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    } else if (showEditor) {
        // Fancy Minuten-Picker: großer Live-Wert, Slider, Schnell-Buttons
        var minutes by remember { mutableStateOf(target.currentMinutes) }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(stringResource(R.string.dashboard_time_adjust)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(R.string.dashboard_allowance_date, target.name, dateLabel),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.dashboard_minutes_value, minutes),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Slider(
                        value = minutes.toFloat(),
                        onValueChange = { minutes = it.toInt().coerceIn(0, 720) },
                        valueRange = 0f..720f,
                        steps = 143 // 5-Minuten-Schritte
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(-30, -15, -5, 5, 15, 30).forEach { delta ->
                            Text(
                                if (delta > 0) "+$delta" else "$delta",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AevumRadius.sm))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .clickable { minutes = (minutes + delta).coerceIn(0, 720) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(15, 30, 45, 60, 90, 120).forEach { preset ->
                            Text(
                                "$preset",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (minutes == preset) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(AevumRadius.sm))
                                    .background(
                                        if (minutes == preset) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                    .clickable { minutes = preset }
                                    .padding(vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    if (target.hasOverride) {
                        Text(
                            stringResource(R.string.dashboard_allowance_override_hint),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onEdit(target.allowanceId, minutes) }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(target.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(
                            R.string.dashboard_allowance_summary,
                            dateLabel,
                            target.currentMinutes
                        ),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.dashboard_allowance_day_only),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showEditor = true }) { Text(stringResource(R.string.dashboard_edit_with_icon)) }
            },
            dismissButton = {
                TextButton(onClick = { onDelete(target.allowanceId) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}
