package com.d_drostes_apps.aevum.ui.screens.timeline
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Tag
import com.d_drostes_apps.aevum.domain.activity.SessionValidationResult
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.domain.trigger.TriggerEventMarker
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.AevumTimePicker
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.CategoryChip
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.ui.components.categoryColor
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    onCreateActivity: (Long) -> Unit,
    onEditActivity: (String) -> Unit,
    onEditCandidate: (String) -> Unit,
    onOpenActivity: (String) -> Unit,
    // M18.61: Kalender-Icon in der Timeline → öffnet die Kalenderansicht
    onOpenCalendar: () -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    // M18.66-FIX14: Wochenansicht-Modus aus dem ViewModel
    val weekView by viewModel.weekView.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onCreateActivity(TimeFormatting.startOfDayMillis(state.selectedDate)) },
                text = { Text("Aktivität") },
                icon = { Text("+") }
            )
        }
    ) { padding ->
        // M18.26: KEINE aeußere LazyColumn mehr fuer die Tag-Ansicht!
        // Vorher: LazyColumn mit item { DayCalendarTimeline(...) } — die
        // Tag-Ansicht (ZoomableDayTimeline) hatte einen eigenen
        // verticalScroll mit fester Hoehe (560dp). Ergebnis: Nested-Scroll-
        // Konflikt — beim Wischen scrollte mal die Timeline, mal das
        // Fragment (genau der gemeldete Usability-Bug).
        //
        // Jetzt: Column mit fixem Header/Summary, und die Timeline bekommt
        // einen EIGENEN Viewport (weight 1f). Es gibt NUR NOCH einen
        // Scroll-Container — die Timeline selbst. Das Fragment scrollt
        // nie mehr mit.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
        ) {
            TimelineHeader(
                state,
                viewModel::previousDay,
                viewModel::nextDay,
                viewModel::today,
                viewModel::runGapDetectionNow,
                onOpenCalendar = onOpenCalendar
            )
            SummaryCard(state)
            if (state.candidates.isNotEmpty()) {
                CandidateReviewCard(
                    candidates = state.candidates,
                    activityTypes = state.activityTypes,
                    onAccept = viewModel::acceptCandidate,
                    onEdit = onEditCandidate,
                    onDismiss = viewModel::dismissCandidate,
                    onConvertGap = viewModel::convertGapToSession
                )
            }
            // M18.58: Slide-Animation beim Tag-Wechsel (nur über die
            // Pfeil-Buttons — KEIN Gesten-Swipe).
            // M18.59-FIX (User: "zurück = nach rechts swipen, vor = nach
            // links swipen"): Die Paare waren vertauscht — nextDay nutzte
            // slideLeft+exitRight (beide von rechts), previousDay das
            // Gegenteil. Jetzt klassische Blätter-Metapher:
            //   vor (nextDay)     → neuer Tag von RECHTS rein, alter nach LINKS raus
            //   zurück (prevDay)  → neuer Tag von LINKS rein, alter nach RECHTS raus
            androidx.compose.animation.AnimatedContent(
                targetState = state.selectedDate,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val slideLeft = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val slideRight = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { -it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val exitLeft = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { -it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    val exitRight = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    if (targetState > initialState) {
                        // Vorwärts (nextDay): nach links swipen
                        (slideLeft togetherWith exitLeft).using(androidx.compose.animation.SizeTransform(clip = false))
                    } else {
                        // Rückwärts (previousDay): nach rechts swipen
                        (slideRight togetherWith exitRight).using(androidx.compose.animation.SizeTransform(clip = false))
                    }
                },
                label = "day-slide"
            ) { date ->
                if (state.sessions.isEmpty() && state.triggerEvents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(
                            title = "Noch keine Aktivitäten",
                            message = "Erfasse deinen Tag manuell oder aktiviere Geofencing. Trigger erscheinen künftig direkt auf dem Tageskalender.",
                            actionLabel = "Erste Aktivität anlegen",
                            onActionClick = { onCreateActivity(TimeFormatting.startOfDayMillis(date)) }
                        )
                    }
                } else {
                    DayCalendarTimeline(
                        sessions = state.sessions,
                        triggers = state.triggerEvents,
                        onOpen = onOpenActivity,
                        onEdit = onEditActivity,
                        onDeleteTrigger = viewModel::deleteTrigger,
                        onDeleteSession = viewModel::deleteSession,
                        // M18.66-FIX18 (User: "wenn man auf die Timeline klickt,
                        // öffnet sich ein Popup — das sollte nicht sein.
                        // Stattdessen dieselbe Seite wie beim Plus-Button, mit
                        // Startzeit = geklickte Zeit, Endzeit = +1h"): Kein
                        // QuickCreateDialog mehr — direkte Navigation zum
                        // ActivityEditor mit der geklickten Minute als
                        // Startzeit (Endzeit = Start + 1h im Editor-Default).
                        onCreateAt = { minute ->
                            val date = state.selectedDate
                            val startMillis = TimeFormatting.parseHourMinuteToMillis(
                                date, minute / 60, minute % 60
                            )
                            onCreateActivity(startMillis)
                        },
                        weekView = weekView,
                        weekSessions = state.weekSessions,
                        onSetWeekView = viewModel::setWeekView,
                        onSelectDay = viewModel::selectDate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AevumSpacing.md)
                    )
                }
            }
            // M18.36: KEIN Spacer mehr hier! Der 88dp-Spacer erzeugte
            // toten Platz UNTER der Timeline. Der FAB-Schutz liegt jetzt
            // im internen Scroll-Container der Listenansicht (Bottom-
            // Padding dort), die Tag-Ansicht scrollt intern und braucht
            // kein Padding.
        }
    }
    // M18.66-FIX18: QuickCreateDialog entfernt — Tap auf leere Zeitstelle
    // navigiert jetzt direkt zum ActivityEditor (Startzeit = Klick-Zeit,
    // Endzeit = +1h). Das Popup war der User-Beschwerdepunkt.
}

@Composable
fun ActivityEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: ActivityEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.savedSessionId) { state.savedSessionId?.let(onSaved) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { EditorHeader(state.isEditing, state.duration, onBack, viewModel::save) }
            item { BasicFields(state, viewModel::setTitle, viewModel::setDescription) }
            item { UnifiedActivitySelector(state.activityTypes, state.form.activityTypeId, viewModel::setActivityType) }
            item {
                VisualTimeEditorCard(
                    state = state,
                    onStartMinute = viewModel::setStartMinuteOfDay,
                    onEndMinute = viewModel::setEndMinuteOfDay,
                    onStartHour = viewModel::setStartHour,
                    onStartQuarter = viewModel::setStartMinute,
                    onEndHour = viewModel::setEndHour,
                    onEndQuarter = viewModel::setEndMinute,
                    onSnapStart = viewModel::snapStartTo,
                    onSnapEnd = viewModel::snapEndTo,
                    openEnded = state.form.endAt == null,
                    onOpenEndedChange = viewModel::setOpenEnded
                )
            }
            item { ValidationCard(state.validation, state.form.errorMessage) }
            item { Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text(if (state.isEditing) "Änderungen speichern" else "Aktivität speichern") } }
            item { Spacer(Modifier.height(AevumSpacing.xxl)) }
        }
    }
}

@Composable
fun ActivityDetailScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: ActivityDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val session = state.session
        if (session == null) {
            // Leerezustand — Aktivitaet nicht gefunden
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(AevumSpacing.lg),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyState(
                    title = "Aktivitaet nicht gefunden",
                    message = "Der Eintrag existiert nicht mehr.",
                    actionLabel = "Zurueck",
                    onActionClick = onBack
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                // Zurueck-Button
                item {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("< Zurueck")
                    }
                }

                // Grosser Header-Bereich mit Aktivitaets-Icon, Name und Kategorie
                item { DetailHeaderCard(session, state) }

                // Zeit-Range als grosse Monospace-Anzeige
                item { DetailTimeRangeCard(state.range, session) }

                // Dauer als hervorgehobene Statistik-Karte
                item { DetailDurationCard(state.duration, state.activityType?.color ?: 0L) }

                // Statistik-Karten: Startzeit, Endzeit, Quelle
                item { DetailStatsGrid(session, state) }

                // Positivitaets-Score als farbiger Balken (falls verfuegbar)
                state.activityType?.let { type ->
                    if (type.positivityScore != 50) {
                        item { DetailPositivityCard(type.positivityScore) }
                    }
                }

                // Beschreibungstext falls vorhanden
                session.description?.let { desc ->
                    if (desc.isNotBlank()) {
                        item { DetailDescriptionCard(desc) }
                    }
                }

                // Bearbeiten / Loeschen Buttons am Ende
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Button(
                            onClick = { onEdit(session.id) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = AevumSpacing.md)
                        ) { Text("Bearbeiten", fontSize = 16.sp) }
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            ),
                            contentPadding = PaddingValues(vertical = AevumSpacing.md)
                        ) { Text("Loeschen", fontSize = 16.sp) }
                    }
                }
            }
        }
    }
    // Loesch-Bestaetigungsdialog
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Aktivitaet loeschen?") },
            text = { Text("Der Eintrag verschwindet aus Timeline und Dashboard.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Loeschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
        )
    }
}

/**
 * Header-Karte: grosses Aktivitaets-Icon in farbigem Kreis,
 * Aktivitaetsname gross, Kategorie als Chip.
 */
@Composable
private fun DetailHeaderCard(session: ActivitySession, state: ActivityDetailUiState) {
    val activityColor = if (state.activityType?.color != null && state.activityType.color != 0L) {
        Color(state.activityType.color)
    } else {
        categoryColor(state.category?.name ?: "Sonstiges")
    }
    val icon = state.activityType?.icon?.takeIf { it.isNotBlank() } ?: "\u2022"

    AevumCard(variant = CardVariant.Gradient) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Aktivitaets-Icon in farbigem Kreis
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(activityColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    fontSize = 36.sp,
                    color = activityColor
                )
            }
            // Aktivitaetsname gross
            Text(
                session.title,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            // Kategorie als Chip
            CategoryChip(categoryId = state.category?.name ?: "Sonstiges")
        }
    }
}

/**
 * Zeit-Range-Karte: Start - Ende als grosse Monospace-Anzeige.
 */
@Composable
private fun DetailTimeRangeCard(range: String, session: ActivitySession) {
    AevumCard(variant = CardVariant.Filled) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                range,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // Datum der Aktivitaet
            val dateStr = remember(session.startAt) {
                val ldt = Instant.ofEpochMilli(session.startAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                ldt.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM"))
            }
            Text(
                dateStr,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Dauer-Karte: hervorgehobene Statistik in einem farbigen Kasten.
 */
@Composable
private fun DetailDurationCard(duration: String, activityColorLong: Long) {
    val accentColor = if (activityColorLong != 0L) Color(activityColorLong) else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.lg),
        color = accentColor.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Dauer",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    duration,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = accentColor
                )
            }
            // Stundenglas-Emoji als visuelles Element
            Text("\u23F1", fontSize = 40.sp)
        }
    }
}

/**
 * Statistik-Grid: 2x2 Kacheln mit Startzeit, Endzeit, Dauer, Quelle.
 */
@Composable
private fun DetailStatsGrid(session: ActivitySession, state: ActivityDetailUiState) {
    // Formatiere Start- und Endzeit
    val startTimeStr = remember(session.startAt) {
        val lt = Instant.ofEpochMilli(session.startAt)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        lt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
    val endTimeStr = remember(session.endAt) {
        session.endAt?.let { end ->
            val lt = Instant.ofEpochMilli(end)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
            lt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
    }
    // Quell-Icon und Label bestimmen
    val (sourceIcon, sourceLabel) = when (session.sourceType) {
        "MANUAL" -> "\u270F\uFE0F" to "Manuell"
        "GEOFENCE_AUTO" -> "\uD83D\uDCCD" to "Geofence"
        "HEALTH_SLEEP_AUTO" -> "\uD83D\uDE34" to "Schlaf (Auto)"
        "ACTIVITY_RECOGNITION_AUTO" -> "\uD83D\uDEB4" to "Bewegung (Auto)"
        else -> "\uD83D\uDCE5" to session.sourceType
    }

    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        // Reihe 1: Startzeit und Endzeit
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            DetailStatCard(
                icon = "\uD83D\uDD51",
                label = "Start",
                value = startTimeStr,
                modifier = Modifier.weight(1f)
            )
            DetailStatCard(
                icon = "\uD83D\uDD52",
                label = if (session.endAt == null) "Laeuft noch" else "Ende",
                value = endTimeStr ?: "offen",
                modifier = Modifier.weight(1f)
            )
        }
        // Reihe 2: Dauer und Quelle
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            DetailStatCard(
                icon = "\u23F1",
                label = "Dauer",
                value = state.duration,
                modifier = Modifier.weight(1f)
            )
            DetailStatCard(
                icon = sourceIcon,
                label = "Quelle",
                value = sourceLabel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Einzelne Statistik-Kachel mit Icon, Label und Wert.
 */
@Composable
private fun DetailStatCard(
    icon: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    AevumCard(modifier = modifier, variant = CardVariant.Elevated, contentPadding = PaddingValues(AevumSpacing.md)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
            horizontalAlignment = Alignment.Start
        ) {
            Text(icon, fontSize = 22.sp)
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Positivitaets-Score als farbiger Balken mit Prozentangabe.
 */
@Composable
private fun DetailPositivityCard(score: Int) {
    val barColor = positivityColor(score)
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Positivitaet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "$score%",
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = barColor
                )
            }
            // Farbigere Hintergrundbahn
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                // Gefuellter Balken
                Box(
                    modifier = Modifier
                        .fillMaxWidth(score / 100f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(AevumRadius.full))
                        .background(barColor)
                )
            }
        }
    }
}

/**
 * Beschreibungskarte mit Text.
 */
@Composable
private fun DetailDescriptionCard(description: String) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
            Text("Beschreibung", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(description, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Hilfsfunktion: Hex-Color-String (z.B. "#FF6366F1" oder "FF6366F1") zu Compose Color.
 * Gibt null zurueck, wenn der String nicht geparst werden kann.
 */
private fun parseHexColorOrNull(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    return try {
        Color(cleaned.toLong(16))
    } catch (_: NumberFormatException) {
        null
    }
}

@Composable
private fun TimelineHeader(
    state: TimelineUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onRunGapDetection: () -> Unit = {},
    // M18.61: Kalender-Icon → öffnet die Kalenderansicht
    onOpenCalendar: () -> Unit = {}
) {
    // M18.36: Header radikal kompakt — EINE Zeile, kein Overlap moeglich.
    // Vorher: zwei Zeilen (Titel/Datum + Chips) — der Datumstext konnte
    // mit dem Heute-Button kollidieren. Jetzt: [‹] [Titel+Datum] [Heute] [›]
    // in einer einzigen Row. "Luecken pruefen"-Button entfernt (User-Wunsch).
    AevumCard(variant = CardVariant.Gradient, contentPadding = PaddingValues(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousDay, modifier = Modifier.size(36.dp)) {
                Text("‹", fontSize = 24.sp)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    state.dayTitle,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.formattedDate,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Heute-Chip kompakt zwischen den Pfeilen
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onToday)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    "Heute",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onNextDay, modifier = Modifier.size(36.dp)) {
                Text("›", fontSize = 24.sp)
            }
            // M18.61: Kalender-Icon — öffnet die Kalenderansicht
            // (User: "in der timeline ein kalender icon haben und wenn man
            // darauf klickt kommt man zur kalender ansicht")
            IconButton(onClick = onOpenCalendar, modifier = Modifier.size(36.dp)) {
                Text("📅", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun SummaryCard(state: TimelineUiState) {
    // M18.32: Kompaktere Summary — kleinere Werte, weniger vertikaler Platz.
    AevumCard(contentPadding = PaddingValues(vertical = AevumSpacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SummaryValue("Erfasst", state.totalTracked)
            SummaryValue("Einträge", state.sessionCount.toString())
            SummaryValue("Konflikte", if (state.hasOverlaps) "Prüfen" else "Keine")
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CandidateReviewCard(
    candidates: List<CandidateReviewUi>,
    activityTypes: List<ActivityType>,
    onAccept: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onConvertGap: (String, String, String) -> Unit = { _, _, _ -> }
) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            // M15: Lücken-Candidates bekommen eine eigene Karte.
            val gapCandidates = candidates.filter { it.isGap }
            if (gapCandidates.isNotEmpty()) {
                Text("Lücken im Tag", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                gapCandidates.forEach { candidate ->
                    GapCandidateCard(
                        candidate = candidate,
                        activityTypes = activityTypes,
                        onConvert = onConvertGap,
                        onDismiss = onDismiss
                    )
                }
            }
            val regularCandidates = candidates.filter { !it.isGap }
            if (regularCandidates.isNotEmpty()) {
                Text("Wir haben Aktivität erkannt", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                regularCandidates.forEach { candidate ->
                    AevumCard(variant = CardVariant.Filled) {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(candidate.title, fontWeight = FontWeight.SemiBold)
                                Text("${candidate.confidence}%", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                            }
                            Text("${candidate.timeRange} · ${candidate.duration}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(candidate.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // M16.6: Buttons gleichberechtigt sichtbar — drei
                            // Spalten mit weight(1f), damit "Verwerfen" nicht
                            // durch Text-Wrapping oder Layout-Quetschung
                            // unsichtbar wird. "Verwerfen" rot gerändert für
                            // sofortige Unterscheidung von "Bearbeiten".
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                            ) {
                                Button(
                                    onClick = { onAccept(candidate.id) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 4.dp, vertical = 10.dp
                                    )
                                ) { Text("Übernehmen", fontSize = 12.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { onEdit(candidate.id) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 4.dp, vertical = 10.dp
                                    )
                                ) { Text("Bearbeiten", fontSize = 12.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { onDismiss(candidate.id) },
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 4.dp, vertical = 10.dp
                                    )
                                ) { Text("Verwerfen", fontSize = 12.sp, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * M15: Spezielle Karte für Gap-Candidates (gestrichelter grauer Rand,
 * Schnellauswahl der häufigsten Kategorien). Wird vom User typischerweise
 * antippt, wenn er eine Lücke im Tag bemerkt und schnell schließen will.
 *
 * M16.4: Erweitert um "Andere Aktivität wählen"-Button, der einen
 * vollständigen ActivityType-Picker öffnet. Wenn die richtige Aktivität
 * nicht in den Schnellauswahl-Buttons ist, kann der User sie trotzdem
 * schnell finden.
 */
@Composable
private fun GapCandidateCard(
    candidate: CandidateReviewUi,
    activityTypes: List<ActivityType>,
    onConvert: (String, String, String) -> Unit,
    onDismiss: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Gestrichelter Rand: Linie mit dash-Intervallen
                drawRect(
                    color = borderColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = size,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(12f, 8f), 0f
                        )
                    )
                )
            }
            .padding(AevumSpacing.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Unbekannte Zeit", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(candidate.timeRange + " · " + candidate.duration, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(
                    onClick = { onDismiss(candidate.id) },
                    label = { Text("Verwerfen", fontSize = 11.sp) }
                )
            }
            Text("Was hast du in dieser Zeit gemacht?", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 4 Schnellauswahl-Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                AssistChip(
                    onClick = { onConvert(candidate.id, "social", "social") },
                    label = { Text("Freunde", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onConvert(candidate.id, "learning", "learning") },
                    label = { Text("Lernen", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onConvert(candidate.id, "household", "household") },
                    label = { Text("Einkaufen", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onConvert(candidate.id, "work", "work") },
                    label = { Text("Arbeit", fontSize = 11.sp) }
                )
            }
            // M16.4: Button zum Öffnen des vollständigen ActivityPickers
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Andere Aktivität wählen…", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showPicker) {
        GapActivityPickerDialog(
            activityTypes = activityTypes,
            onDismiss = { showPicker = false },
            onPicked = { pickedType ->
                showPicker = false
                onConvert(candidate.id, pickedType.defaultCategoryId ?: "unknown", pickedType.id)
            }
        )
    }
}

/**
 * M16.4: Modal-Dialog mit allen verfügbaren ActivityTypes für die
 * Gap-Erfassung. Gefiltert auf sichtbare (enabled) Typen, gruppiert
 * nach Kategorie für schnelles Finden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GapActivityPickerDialog(
    activityTypes: List<ActivityType>,
    onDismiss: () -> Unit,
    onPicked: (ActivityType) -> Unit
) {
    val visibleTypes = remember(activityTypes) {
        // M16.4: Alle ActivityTypes sind aktuell aktiv (kein enabled-Flag
        // im Schema). Falls in Zukunft ein enabled-Flag eingeführt wird,
        // hier filtern.
        activityTypes.sortedBy { it.name }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aktivität wählen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                if (visibleTypes.isEmpty()) {
                    Text(
                        "Keine Aktivitäten verfügbar.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                } else {
                    visibleTypes.forEach { type ->
                        ActivityTypeRow(type = type, onClick = { onPicked(type) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun ActivityTypeRow(type: ActivityType, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(AevumRadius.md),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text(type.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("→", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * M18.44: Quick-Create-Dialog (Google-Calendar-Prinzip).
 *
 * Erscheint, wenn der User in der Tagesansicht auf eine LEERE Zeitstelle
 * tippt. Enthält:
 *  - die getippte Uhrzeit (Startzeit, groß + farbig)
 *  - eine Aktivitäts-Auswahl (Icon + Name, scrollbar)
 *  - einen kleinen AevumTimePicker für die ENDZEIT (Standard: +1h)
 *  - zwei Aktionen: "Erstellen" (fixe Session) ODER "Jetzt aufzeichnen"
 *    (Session läuft ab der getippten Zeit weiter — endAt=null)
 */
@Composable
private fun QuickCreateDialog(
    minuteOfDay: Int,
    types: List<ActivityType>,
    onDismiss: () -> Unit,
    onCreate: (typeId: String, startMinute: Int, endMinute: Int) -> Unit,
    onStartNow: (typeId: String, startMinute: Int) -> Unit
) {
    var selectedTypeId by remember { mutableStateOf<String?>(null) }
    // M18.45 (User: "start und zielzeit manuell festlegen können"):
    // Die getippte Zeit ist nur der VORSCHLAG — der User kann die
    // Startzeit im Dialog frei anpassen.
    var startMinute by remember(minuteOfDay) { mutableStateOf(minuteOfDay.coerceIn(0, 1439)) }
    // Über Mitternacht darf die Endzeit kleiner als die Startzeit sein. Die
    // ViewModel-Schicht interpretiert das korrekt als Folgetag.
    var endMinute by remember(minuteOfDay) { mutableStateOf((minuteOfDay + 60) % 1440) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    // M18.45 (User: "oder statt zielzeit die auswahl, dass die app von
    // der startzeit aufgezeichnet werden sollte und weiter läuft"):
    // Segment-Umschalter: "Mit Endzeit" (fixe Session) oder
    // "Weiter aufzeichnen" (endAt = null, läuft ab Startzeit).
    var continueMode by remember { mutableStateOf(false) }
    val startLabel = "%02d:%02d".format(startMinute / 60, startMinute % 60)
    val endLabel = "%02d:%02d".format(endMinute / 60, endMinute % 60)
    val visibleTypes = remember(types) { types.sortedBy { it.name } }

    val hasValidFixedRange = endMinute != startMinute

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Neue Aktivität", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                // M18.45: Beide Zeiten sind antippbar (öffnen den Picker).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStartPicker = !showStartPicker; showEndPicker = false },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Text(
                        "Start $startLabel",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF5A623)
                    )
                    Text(
                        if (continueMode) "→ läuft weiter…" else "→ Ende $endLabel",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                // M18.49 (User: "Das Popup hat immer noch nicht die
                // Funktionalität, Start und Zielzeit anzupassen bevor man
                // speichert, obwohl sie das haben sollte"): Die Picker waren
                // hinter kleinen TextButtons ("Startzeit ändern") unter der
                // langen Activity-Liste versteckt — bei vielen Aktivitäten
                // musste man erst scrollen und der 220dp-Picker quetschte
                // sich neben die Liste. Jetzt: Sobald ein Picker geöffnet
                // ist, wird die Activity-Liste ausgeblendet und der Picker
                // bekommt den kompletten Dialog-Platz. Ein Zurück-Button
                // ("← Aktivität wählen") führt zur Liste zurück.
                if (showStartPicker || showEndPicker) {
                    // ── Picker-Modus: volle Breite für die Zeitwahl ────
                    TextButton(onClick = {
                        showStartPicker = false
                        showEndPicker = false
                    }) { Text("← Aktivität wählen") }
                    if (showStartPicker) {
                        AevumTimePicker(
                            initialHour = startMinute / 60,
                            initialMinute = startMinute % 60,
                            accent = Color(0xFFF5A623),
                            onTimeChange = { h, m -> startMinute = (h * 60 + m).coerceIn(0, 1439) },
                            label = "STARTZEIT",
                            showDigitalDisplay = true
                        )
                    }
                    if (showEndPicker) {
                        AevumTimePicker(
                            initialHour = endMinute / 60,
                            initialMinute = endMinute % 60,
                            accent = MaterialTheme.colorScheme.primary,
                            onTimeChange = { h, m -> endMinute = (h * 60 + m).coerceIn(0, 1439) },
                            label = "ENDZEIT",
                            showDigitalDisplay = true
                        )
                    }
                } else {
                    // ── Normal-Modus: Modus-Umschalter + Aktivitäts-Wahl ──
                    // ── Modus-Umschalter (fancy Segment) ──────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AevumRadius.md))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        SegmentButton(
                            label = "Mit Endzeit",
                            selected = !continueMode,
                            modifier = Modifier.weight(1f)
                        ) { continueMode = false }
                        SegmentButton(
                            label = "● Weiter aufzeichnen",
                            selected = continueMode,
                            modifier = Modifier.weight(1f)
                        ) { continueMode = true }
                    }
                    // ── Aktivitäts-Auswahl (Icon + Name) ─────────────────
                    if (visibleTypes.isEmpty()) {
                        Text("Keine Aktivitäten verfügbar.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    } else {
                        visibleTypes.forEach { type ->
                            val selected = type.id == selectedTypeId
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTypeId = type.id },
                                shape = RoundedCornerShape(AevumRadius.md),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                                ) {
                                    if (type.icon.isNotBlank()) {
                                        Text(type.icon, fontSize = 20.sp)
                                    }
                                    Text(type.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    if (selected) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    // ── Zeit-Buttons: öffnen den Picker-Modus ──────────
                    // M18.49: Die Zeitzeile oben im Titel öffnet ebenfalls
                    // den Picker (showStartPicker/showEndPicker togglen).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                    ) {
                        OutlinedButton(
                            onClick = { showStartPicker = true; showEndPicker = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("Startzeit ändern") }
                        if (!continueMode) {
                            OutlinedButton(
                                onClick = { showEndPicker = true; showStartPicker = false },
                                modifier = Modifier.weight(1f)
                            ) { Text("Endzeit ändern") }
                        }
                    }
                    if (continueMode) {
                        Text(
                            "Die Aufzeichnung startet ab $startLabel und läuft weiter, bis du sie stoppst.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                if (continueMode) {
                    // M18.45: "Weiter aufzeichnen" — Session ab Startzeit, endAt = null
                    Button(
                        onClick = { selectedTypeId?.let { onStartNow(it, startMinute) } },
                        enabled = selectedTypeId != null
                    ) { Text("● Aufzeichnen", color = Color.White, fontWeight = FontWeight.Bold) }
                } else {
                    // Feste Session mit Start- UND Endzeit
                    Button(
                        onClick = { selectedTypeId?.let { onCreate(it, startMinute, endMinute) } },
                        enabled = selectedTypeId != null && hasValidFixedRange
                    ) { Text("Erstellen") }
                }
            }
        }
    )
}

/** M18.45: Segment-Button für den Modus-Umschalter (fancy, aktiv = Primary). */
@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(AevumRadius.md)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 6.dp)
        )
    }
}

@Composable
private fun DayCalendarTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDeleteTrigger: (String) -> Unit,
    // M18.48: Löschen einer Session aus der Liste (mit Bestätigungsdialog).
    onDeleteSession: (String) -> Unit,
    // M18.44: Quick-Create aus der Tagesansicht (leere Stelle antippen)
    onCreateAt: (Int) -> Unit,
    // M18.66-FIX14: Wochenansicht
    weekView: Boolean,
    weekSessions: Map<LocalDate, List<TimelineSessionUi>>,
    onSetWeekView: (Boolean) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var isListMode by remember { mutableStateOf(false) }
    var pixelsPerHour by remember { mutableStateOf(TimelineUiState.DEFAULT_PIXELS_PER_HOUR) }
    // M18.58: Slide-Animation beim Wechsel Liste ↔ Tag. Die Richtung folgt
    // dem Ziel-Modus: Wechsel zu "Tag" schiebt von rechts rein (wie
    // Vorwärtsblättern), Wechsel zu "Liste" von links.
    val modeTransition = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Left
    val lanes = remember(sessions) { assignTimelineLanes(sessions) }
    val maxLane = (lanes.values.maxOrNull() ?: 0).coerceAtLeast(0)
    val laneCount = maxLane + 1

    // M18.26: Karte füllt den kompletten Viewport. Der Inhalt (Liste
    // ODER Tag-Ansicht) scrollt eigenständig — es gibt keinen
    // Nested-Scroll-Konflikt mehr mit einer äußeren LazyColumn.
    AevumCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Header: title + view mode toggle + zoom controls (fix)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Tageskalender", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isListMode) "Eine Zeile pro Ereignis" else "00:00–24:00 · Pinch zum Zoomen",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                    ModeToggleButton("Liste", isListMode) { isListMode = true; onSetWeekView(false) }
                    ModeToggleButton("Tag", !isListMode && !weekView) { isListMode = false; onSetWeekView(false) }
                    // M18.66-FIX14: Tag/Woche-Icons rechts neben den Liste/Tag-Toggles.
                    // CalendarViewDay = Tagesansicht, DateRange = Wochenansicht.
                    IconButton(
                        onClick = { isListMode = false; onSetWeekView(false) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarViewDay,
                            contentDescription = "Tagesansicht",
                            tint = if (!isListMode && !weekView) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { isListMode = false; onSetWeekView(true) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Wochenansicht",
                            tint = if (weekView) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            // M18.66-FIX15: Icon vergrößert (18→24dp) — das
                            // 7-Tage-Icon war zu klein neben den Tag-Toggles.
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            // Zoom slider (in Tag- und Wochenansicht, nicht in Listenansicht)
            if (!isListMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AevumSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Text("−", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = pixelsPerHour,
                        onValueChange = { pixelsPerHour = it },
                        valueRange = TimelineUiState.MIN_PIXELS_PER_HOUR..TimelineUiState.MAX_PIXELS_PER_HOUR,
                        modifier = Modifier.weight(1f)
                    )
                    Text("+", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // M18.58: Slide-Animation beim Wechsel Liste ↔ Tag (nur über den
            // Umschalter — KEIN Gesten-Swipe). Wechsel zu "Tag" schiebt von
            // rechts rein (wie Vorwärtsblättern), Wechsel zu "Liste" von links.
            androidx.compose.animation.AnimatedContent(
                targetState = isListMode,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val slideInFromRight = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val slideInFromLeft = androidx.compose.animation.slideInHorizontally(
                        animationSpec = tween(320),
                        initialOffsetX = { -it }
                    ) + androidx.compose.animation.fadeIn(tween(320))
                    val exitToLeft = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { -it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    val exitToRight = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = tween(320),
                        targetOffsetX = { it }
                    ) + androidx.compose.animation.fadeOut(tween(320))
                    if (targetState) {
                        // → Liste: Liste kommt von links, Tag geht nach rechts
                        (slideInFromLeft togetherWith exitToRight).using(androidx.compose.animation.SizeTransform(clip = false))
                    } else {
                        // → Tag: Tag kommt von rechts, Liste geht nach links
                        (slideInFromRight togetherWith exitToLeft).using(androidx.compose.animation.SizeTransform(clip = false))
                    }
                },
                label = "mode-slide"
            ) { listMode ->
                if (listMode) {
                    // M18.22+: Sichtbare und ziehbare Scrollbar.
                    // Die EventListTimeline bekommt den vollen Viewport und
                    // verticalScroll. Zusaetzlich wird ein echter Scrollbar-Thumb
                    // rechts neben der Liste gerendert, dessen Position vom
                    // ScrollState abhaengt.
                    val listScrollState = rememberScrollState()
                    // M18.26: Viewport-Höhe dynamisch messen (statt hart 520dp),
                    // damit der Scrollbar-Thumb immer korrekt skaliert.
                    var viewportHeightPx by remember { mutableStateOf(0) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportHeightPx = it.height }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(listScrollState)
                                .padding(horizontal = AevumSpacing.md)
                                // M18.36: Bottom-Padding NUR hier — der FAB
                                // ueberdeckt den letzten Eintrag nicht, aber
                                // es entsteht kein toter Platz unter der
                                // Tag-Ansicht (die scrollt intern).
                                .padding(bottom = 88.dp)
                        ) {
                            EventListTimeline(
                                sessions = sessions,
                                triggers = triggers,
                                onOpen = onOpen,
                                onEdit = onEdit,
                                onDeleteTrigger = onDeleteTrigger,
                                onDeleteSession = onDeleteSession
                            )
                        }
                        // M18.23: Sichtbarer Scrollbar-Thumb rechts neben der Liste.
                        // M18.25: EIN ScrollState fuer Column UND Thumb — vorher
                        // wurden zwei Instanzen erzeugt, der Thumb las den falschen.
                        val showScrollbar = listScrollState.maxValue > 0 && viewportHeightPx > 0
                        if (showScrollbar) {
                            val viewportPx = viewportHeightPx.toFloat()
                            val density = LocalDensity.current
                            val thumbHeight = (viewportPx * (viewportPx / (viewportPx + listScrollState.maxValue)))
                                .coerceAtLeast(with(density) { 24.dp.toPx() })
                            val thumbOffset = (listScrollState.value.toFloat() / listScrollState.maxValue.coerceAtLeast(1)) *
                                (viewportPx - thumbHeight)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 2.dp)
                                    .width(4.dp)
                                    .height(with(density) { thumbHeight.toDp() })
                                    .offset(y = with(density) { thumbOffset.toDp() })
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            )
                        }
                    }
                } else {
                    if (weekView) {
                        WeekTimeline(
                            weekSessions = weekSessions,
                            pixelsPerHour = pixelsPerHour,
                            onColumnTap = { day ->
                                onSetWeekView(false)
                                onSelectDay(day)
                            }
                        )
                    } else {
                        ZoomableDayTimeline(
                            sessions = sessions,
                            triggers = triggers,
                            lanes = lanes,
                            pixelsPerHour = pixelsPerHour,
                            onPixelsPerHourChange = { pixelsPerHour = it },
                            onOpen = onOpen,
                            onEdit = onEdit,
                            onCreateAt = onCreateAt
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .padding(horizontal = AevumSpacing.sm, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * M13: Event-List Timeline.
 * Jeder Eintrag bekommt eine feste Höhe — keine proportionale Anzeige.
 * Damit sind nahe Ereignisse immer lesbar und klickbar.
 *
 * M18.8: Nutzerfreundlichkeits-Upgrade:
 *  - Tagesabschnitt-Header (Nacht/Morgen/Vormittag/Nachmittag/Abend)
 *    machen lange Listen scannbar — man findet eine Session sofort.
 *  - Laufende Sessions bekommen ein pulsierendes LIVE-Badge.
 *  - Größere Zeilen (12dp vertikal) = bessere Touch-Targets.
 */
@Composable
private fun EventListTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDeleteTrigger: (String) -> Unit = {},
    // M18.48: Löschen einer Session aus der Liste (mit Bestätigungsdialog).
    onDeleteSession: (String) -> Unit = {}
) {
    val merged = remember(sessions, triggers) {
        (sessions.map { TimelineEntry.Session(it) } + triggers.map { TimelineEntry.Trigger(it) })
            .sortedBy { entry ->
                when (entry) {
                    is TimelineEntry.Session -> entry.session.startMinuteOfDay
                    is TimelineEntry.Trigger -> entry.trigger.minuteOfDay
                }
            }
    }
    if (merged.isEmpty()) {
        Text(
            "Keine Ereignisse. Erfasse deine erste Aktivität.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = AevumSpacing.md)
        )
        return
    }

    // M18.8: Nach Tagesabschnitten gruppieren — sofort scannbar.
    val grouped = remember(merged) { groupByDayPart(merged) }
    // M18.48: Zu löschende Session (für den Sicherheitsdialog). Erst nach
    // Bestätigung wird onDeleteSession aufgerufen.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        grouped.forEach { (part, entries) ->
            // Abschnitts-Header
            Text(
                part.label.uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
            )
            entries.forEach { entry ->
                when (entry) {
                    is TimelineEntry.Session -> {
                        // M18.5: Positivitäts-Farbcodierung — die Timeline wird
                        // zum visuellen Tagebuch: grüner Punkt = gute Zeit,
                        // roter Punkt = schlechte Zeit (Instagram gucken).
                        // M18.13: Icon (Emoji) + custom Farbe der Aktivität.
                        EventListRow(
                            time = entry.session.time,
                            title = entry.session.title,
                            detail = "${entry.session.range} · ${entry.session.duration}",
                            accent = if (entry.session.activityColor != 0L) Color(entry.session.activityColor) else positivityColor(entry.session.positivityScore),
                            icon = entry.session.activityIcon,
                            kind = if (entry.session.isAuto) "Auto" else "Erfasst",
                            isLive = entry.session.isRunning,
                            onClick = { onOpen(entry.session.id) },
                            onEdit = { onEdit(entry.session.id) },
                            onDelete = { pendingDeleteId = entry.session.id }
                        )
                    }
                    is TimelineEntry.Trigger -> {
                        // Trigger-Eintrag mit Loeschen-Button (Trash-Icon)
                        EventListRow(
                            time = entry.trigger.time,
                            title = "◆ ${entry.trigger.label}",
                            detail = "${entry.trigger.confidence}% Konfidenz",
                            accent = MaterialTheme.colorScheme.secondary,
                            kind = "Trigger",
                            onClick = {},
                            onEdit = {},
                            onDelete = { onDeleteTrigger(entry.trigger.id) }
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                    thickness = 1.dp
                )
            }
        }
    }

    // M18.48: Sicherheitsdialog für das Löschen einer Session aus der Liste.
    // Der User muss die Löschung explizit bestätigen — kein Sofort-Löschen.
    pendingDeleteId?.let { id ->
        val session = sessions.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Aktivität löschen?") },
            text = {
                Text(
                    "„${session?.title ?: "Diese Aktivität"}“ (${
                        session?.range ?: ""
                    }) wird aus Timeline und Dashboard entfernt. Dieser Schritt kann nicht rückgängig gemacht werden."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(id)
                        pendingDeleteId = null
                    }
                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Abbrechen") } }
        )
    }
}

/** M18.8: Tagesabschnitte für scannbare Listen-Gruppierung. */
private enum class DayPart(val label: String, val startMin: Int, val endMin: Int) {
    Nacht("Nacht", 0, 5 * 60),
    Morgen("Morgen", 5 * 60, 10 * 60),
    Vormittag("Vormittag", 10 * 60, 13 * 60),
    Nachmittag("Nachmittag", 13 * 60, 17 * 60),
    Abend("Abend", 17 * 60, 21 * 60),
    Spaet("Später Abend", 21 * 60, 24 * 60);

    companion object {
        fun of(minute: Int): DayPart = entries.first { minute in it.startMin until it.endMin }
    }
}

/** M18.8: Entries nach Tagesabschnitt gruppieren (Reihenfolge beibehalten). */
private fun groupByDayPart(
    entries: List<TimelineEntry>
): List<Pair<DayPart, List<TimelineEntry>>> {
    return entries.groupBy { entry ->
        val minute = when (entry) {
            is TimelineEntry.Session -> entry.session.startMinuteOfDay
            is TimelineEntry.Trigger -> entry.trigger.minuteOfDay
        }
        DayPart.of(minute)
    }.toList().sortedBy { it.first.startMin }
}

private sealed class TimelineEntry {
    data class Session(val session: TimelineSessionUi) : TimelineEntry()
    data class Trigger(val trigger: TriggerEventUi) : TimelineEntry()
}

@Composable
private fun EventListRow(
    time: String,
    title: String,
    detail: String,
    accent: Color,
    kind: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    // M18.8: Laufende Session -> pulsierendes LIVE-Badge
    isLive: Boolean = false,
    // M18.13: Icon (Emoji) der Aktivität
    icon: String = "•",
    // Loeschen-Callback. Wenn gesetzt, wird ein Trash-Button angezeigt.
    onDelete: (() -> Unit)? = null
) {
    // M18.20: Farbige Karte — Akzentbalken links, farbiger Hintergrund,
    // Icon-Kreis, farbiger Zeit-Chip. Jede Zeile ist jetzt ein buntes
    // Element statt einer nackten Textzeile.
    // M18.32: Kreativ-Upgrade — sanfter Farbverlauf statt flacher Flaeche,
    // Dauer-Chip rechts, dezenter Zeit-Pfeil. Die Zeile wirkt jetzt wie
    // eine kleine Karte mit Tiefe statt einer flachen Liste.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.16f),
                        accent.copy(alpha = 0.05f),
                        Color.Transparent
                    )
                )
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // Akzentbalken links — volle Aktivitätsfarbe
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(44.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                .background(accent)
        )
        // M18.13: Icon in farbigem Kreis (größer, kräftiger)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (icon.isBlank()) "•" else icon,
                fontSize = 18.sp
            )
        }
        // Zeit als farbiger Chip
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                .background(accent.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                time,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // M18.32: Dauer-Chip rechts (Monospace, farbig) — die Dauer war
        // vorher nur im Detail-Text versteckt, jetzt sofort lesbar.
        val durationText = detail.substringAfter("· ").ifEmpty { "" }
        if (durationText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    durationText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
            }
        }
        // M18.8: LIVE-Badge für laufende Sessions — sofort erkennbar
        if (isLive) {
            Row(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Text(
                    "LIVE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    letterSpacing = 0.8.sp
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.full))
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    kind,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
            }
        }
        // Trash-Button fuer loeschbare Eintraege (z.B. Trigger)
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "\u2715",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * M13: Zoomable Day Timeline.
 * - Vertikaler Single-Finger-Scroll.
 * - Pinch-Zoom NUR via Slider (gesture-conflict-frei).
 * - Lane-System zur Vermeidung von Text-Overlap.
 */
@Composable
private fun ZoomableDayTimeline(
    sessions: List<TimelineSessionUi>,
    triggers: List<TriggerEventUi>,
    lanes: Map<String, Int>,
    pixelsPerHour: Float,
    onPixelsPerHourChange: (Float) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCreateAt: (Int) -> Unit
) {
    val totalHeight = (24 * pixelsPerHour).dp
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val blockX = 56.dp
    val axisX = 48.dp
    val blockRightPadding = 12.dp
    val laneCount = (lanes.values.maxOrNull() ?: 0).coerceAtLeast(0) + 1
    // M18.15: TextMeasurer für Emoji-Icons im Canvas (drawText).
    val textMeasurer = rememberTextMeasurer()

    // Track now-time for the marker line — only computed at composition
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val nowMinute = TimeFormatting.minutesOfDay(now, zone).coerceIn(0, 1440)

    // M18.26: Voller Viewport statt fixe 560dp. Die Tag-Ansicht ist der
    // EINZIGE Scroll-Container des Screens — kein Nested-Scroll mehr.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Pinch-to-zoom area: vertikaler Scroll + zwei separate
            // Gesten-Handler für Zoom (wir machen es über den Slider,
            // nicht über Gesten — Konflikt-frei).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    // M18.44: Google-Calendar-Prinzip — Tap auf eine LEERE
                    // Zeitstelle öffnet den Quick-Create-Dialog. Tap auf
                    // einen Session-Block öffnet die Session (gleiche
                    // Logik wie die Text-Labels, nur über die volle
                    // Block-Fläche). Die Position kommt relativ zum Canvas
                    // (der Scroll-Versatz wird von Compose automatisch
                    // zurückgerechnet), daher kein scrollState nötig.
                    .pointerInput(sessions, pixelsPerHour, lanes, laneCount) {
                        detectTapGestures { offset ->
                            val pxHour = pixelsPerHour.dp.toPx()
                            // Canvas-Höhe = 24 * pxHour. Eine Stunde entspricht
                            // 60 Minuten; die alte Formel teilte zusätzlich durch
                            // 24 und ordnete fast den gesamten Tag den ersten
                            // 60 Minuten zu.
                            val minute = ((offset.y / pxHour) * 60f).toInt().coerceIn(0, 1439)
                            val hit = sessions.firstOrNull { s ->
                                val startMin = s.startMinuteOfDay.coerceIn(0, 1440)
                                val rawEnd = s.endMinuteOfDay
                                val endMin = when {
                                    rawEnd <= 0 -> startMin + 1
                                    rawEnd < startMin + 1 -> startMin + 1
                                    rawEnd > 1440 -> 1440
                                    else -> rawEnd
                                }
                                // M18.66-FIX17: Hit-Test mit IDENTISCHER Geometrie
                                // wie die Zeichnung (Lane-Verschiebung + 18dp
                                // Mindesthöhe). Vorher prüfte der Hit-Test nur
                                // die reale Blockhöhe OHNE Lane und OHNE
                                // Mindesthöhe — bei kurzen Activities (gezeichnet
                                // mit 18dp Minimum) war nur der obere Teil
                                // klickbar. Jetzt ist der GESAMTE sichtbare
                                // Block Trefferfläche.
                                val lane = lanes[s.id] ?: 0
                                val top = (startMin / 60f) * pxHour
                                val bottom = (endMin / 60f) * pxHour
                                val totalH = (bottom - top).coerceAtLeast(18.dp.toPx())
                                val laneH = (if (totalH > 8f) (totalH - 4f) / (laneCount.coerceAtLeast(1)).toFloat() else totalH)
                                    .coerceAtLeast(18.dp.toPx())
                                val laneY = top + 2f + lane * laneH
                                val laneHeight = (laneH - 2f).coerceAtLeast(2f)
                                // Nur der Block-Bereich (rechts der Uhr-Achse)
                                // ist Trefferfläche — ein Tap auf der Achse
                                // oder daneben erzeugt eine neue Aktivität.
                                offset.x >= blockX.toPx() && offset.y >= laneY - 2f && offset.y <= laneY + laneHeight + 2f
                            }
                            if (hit != null) onOpen(hit.id) else onCreateAt(minute)
                        }
                    }
                ) {
                    val pxHour = pixelsPerHour.dp.toPx()
                    val axisXLocal = axisX.toPx()
                    val blockXLocal = blockX.toPx()
                    val blockRightPaddingLocal = blockRightPadding.toPx()
                    val blockWidth = size.width - blockXLocal - blockRightPaddingLocal

                    // M18.26: Tagesabschnitt-Tönung (Google-Calendar-Prinzip).
                    // Nacht = tiefblau, Morgen = warmes Gelb, Mittag = hell,
                    // Abend = Orange-Rot. Jede Zone ist ein sanfter Farbverlauf
                    // ueber die volle Breite — der Tag wird auf einen Blick
                    // lesbar und die aktuelle Uhrzeit gefuehlt.
                    val zoneGradient = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0xFF1E1B4B).copy(alpha = 0.55f), // 00:00 Nacht
                            0.21f to Color(0xFF1E1B4B).copy(alpha = 0.45f), // ~05:00 Nacht-Ende
                            0.24f to Color(0xFFF59E0B).copy(alpha = 0.14f), // ~06:00 Morgen
                            0.42f to Color(0xFFFDE68A).copy(alpha = 0.10f), // ~10:00 Vormittag
                            0.54f to Color(0xFFFEF3C7).copy(alpha = 0.08f), // ~13:00 Mittag
                            0.71f to Color(0xFFFDBA74).copy(alpha = 0.12f), // ~17:00 Nachmittag
                            0.79f to Color(0xFFFB7185).copy(alpha = 0.16f), // ~19:00 Abend
                            1.00f to Color(0xFF1E1B4B).copy(alpha = 0.55f)  // 24:00 Nacht
                        )
                    )
                    drawRect(brush = zoneGradient, size = Size(size.width, size.height))

                    // Hour grid + axis
                    drawLine(
                        color = Color.White.copy(alpha = 0.18f),
                        start = Offset(axisXLocal, 0f),
                        end = Offset(axisXLocal, size.height),
                        strokeWidth = 2f
                    )
                    for (hour in 0..24) {
                        val y = hour * pxHour
                        val isMajor = hour % 3 == 0
                        drawLine(
                            color = Color.White.copy(alpha = if (isMajor) 0.22f else 0.08f),
                            start = Offset(axisXLocal, y),
                            end = Offset(size.width, y),
                            strokeWidth = if (isMajor) 1.5f else 0.5f
                        )
                    }
                    // Triggers
                    triggers.forEach { trigger ->
                        val y = (trigger.minuteOfDay / 60f) * pxHour
                        drawCircle(
                            color = Color(0xFF2DD4BF),
                            radius = 4.dp.toPx(),
                            center = Offset(axisXLocal, y)
                        )
                        drawLine(
                            color = Color(0xFF2DD4BF).copy(alpha = 0.32f),
                            start = Offset(axisXLocal, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }
                    // Sessions — lane-aware rendering
                    sessions.forEach { session ->
                        // M16.5: Mitternacht-Schlaf hat startMinuteOfDay=0 und
                        // endMinuteOfDay=510 im Folgetag. Der alte
                        // coerceIn(startMin+5, 1440) hätte das Ende auf
                        // startMin+5 verschoben (bei Mitternacht auf 5 = 00:05)
                        // und die Session unsichtbar gemacht. Jetzt:
                        // endMin.coerceAtLeast(startMin+1) — nur floor,
                        // kein Ceiling bei 1440. Damit wird die korrekte
                        // Tagessichtbarkeit garantiert.
                        val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
                        val rawEnd = session.endMinuteOfDay
                        val endMin = when {
                            rawEnd <= 0 -> startMin + 1
                            rawEnd < startMin + 1 -> startMin + 1
                            rawEnd > 1440 -> 1440
                            else -> rawEnd
                        }
                        val lane = lanes[session.id] ?: 0
                        val topY = (startMin / 60f) * pxHour
                        val bottomY = (endMin / 60f) * pxHour
                        val totalH = (bottomY - topY).coerceAtLeast(20f)
                        // M18.21: Mindesthöhe für kurze Aktivitäten (z.B. 5 min).
                        // Ohne Minimum wäre ein 5-min-Block bei 60px/h nur ~5px
                        // hoch — Farbe und Icon unsichtbar. Jetzt: mindestens
                        // 18dp, damit JEDER Block sichtbar farbig + mit Icon
                        // bleibt (Google-Calendar-Prinzip: kurze Termine werden
                        // auf Mindesthöhe gezeichnet).
                        val laneH = (if (totalH > 8f) (totalH - 4f) / (laneCount.coerceAtLeast(1)).toFloat() else totalH)
                            .coerceAtLeast(18.dp.toPx())
                        val laneY = topY + 2f + lane * laneH
                        val laneHeight = (laneH - 2f).coerceAtLeast(2f)
                        // M18.15: Custom-Farbe der Aktivität bevorzugen,
                        // sonst Kategorie-Farbe.
                        val color = if (session.activityColor != 0L) Color(session.activityColor) else categoryColor(session.categoryName)
                        // M18.48 (User: "die Farbe etwas prägnanter"): Der
                        // Block-Fill wurde von 0.42 auf 0.62 angehoben, damit
                        // die Aktivitätsfarbe klar erkennbar ist. Bei
                        // Überlappungen 0.8 (weiterhin leicht abgesetzt).
                        val fillAlpha = if (session.isOverlapping) 0.80f else 0.62f
                        drawRoundRect(
                            color = color.copy(alpha = fillAlpha),
                            topLeft = Offset(blockXLocal, laneY),
                            size = Size(blockWidth.coerceAtLeast(0f), laneHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                        )
                        // M18.60-FIX (User: "der Strich ragt zur Hälfte über
                        // die Activity"): ROOT CAUSE — der Akzentbalken war
                        // eine drawLine mit dicker strokeWidth bei y=laneY+1.
                        // drawLine zentriert den Stroke um die y-Position:
                        // bei laneHeight=100 ging der Strich von laneY-48 bis
                        // laneY+51 — ragt ~50% über die Block-Oberkante und
                        // endet in der Block-Mitte. Fix: drawRoundRect mit
                        // exakt der Blockhöhe, bündig an der Oberkante.
                        drawRoundRect(
                            color = color.copy(alpha = 0.95f),
                            topLeft = Offset(blockXLocal, laneY),
                            size = Size(4.dp.toPx(), laneHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                        )
                        drawLine(
                            color = color.copy(alpha = 0.85f),
                            start = Offset(blockXLocal, laneY),
                            end = Offset(blockXLocal + blockWidth, laneY),
                            strokeWidth = 1.5f
                        )
                        // M18.15: Icon (Emoji) der Aktivität im Block zeichnen.
                        // M18.21: Schwelle auf 16dp gesenkt — dank Mindesthöhe
                        // (18dp) haben auch kurze Aktivitäten ein sichtbares Icon.
                        // M18.48 (User: "Icons sollten sichtbar sein, aber bei
                        // kleinen Elementen nicht überladen"): Icon leicht größer
                        // (14sp) und auf einem kleinen weißen, halbtransparenten
                        // Pill-Hintergrund — dadurch hebt es sich von der Farbe
                        // ab und bleibt bei kurzen Blöcken lesbar, ohne den
                        // Block zu überladen.
                        if (laneHeight >= 18.dp.toPx() && session.activityIcon.isNotBlank() && session.activityIcon != "•") {
                            val iconSize = 14.dp.toPx()
                            val pillW = 24.dp.toPx()
                            val pillH = (iconSize + 4.dp.toPx())
                            val iconX = blockXLocal + 6.dp.toPx()
                            val iconY = laneY + (laneHeight - pillH) / 2f
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.28f),
                                topLeft = Offset(iconX, iconY),
                                size = Size(pillW, pillH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f, pillH / 2f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = session.activityIcon,
                                topLeft = Offset(iconX + 3.dp.toPx(), iconY + (pillH - iconSize) / 2f),
                                style = TextStyle(fontSize = 14.sp)
                            )
                        }
                    }
                    // Now line
                    if (nowMinute in 0..1440) {
                        val nowY = (nowMinute / 60f) * pxHour
                        drawLine(
                            color = Color(0xFFEC4899),
                            start = Offset(axisXLocal, nowY),
                            end = Offset(size.width, nowY),
                            strokeWidth = 1.5f
                        )
                    }
                }

                // Hour labels overlaid (Compose Text, not Canvas) for proper typography
                for (hour in 0..24 step 2) {
                    Text(
                        "%02d:00".format(hour),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, top = (hour * pixelsPerHour - 6).dp.coerceAtLeast(0.dp))
                    )
                }

                // Session labels with click + edit (positioned by lane)
                // M18.66-FIX12: Labels zoom-abhängig ein-/ausblenden.
                // Bei weit herausgezoomten Blöcken (wenige Pixel pro Minute)
                // werden kurze Activities zu winzigen Balken — die Labels
                // überlappen dann. Jetzt: Label nur anzeigen, wenn die
                // Block-Höhe in Pixeln groß genug ist (>= 22dp). Das ist
                // abhängig vom Zoom (pixelsPerHour) und der Activity-Dauer.
                // Bei Reinzoomen erscheinen die Labels automatisch wieder.
                val minLabelHeightPx = with(LocalDensity.current) { 22.dp.toPx() }
                sessions.forEach { session ->
                    // M16.5: Mitternacht-sicheres Clipping (siehe oben).
                    val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
                    val rawEnd = session.endMinuteOfDay
                    val endMin = when {
                        rawEnd <= 0 -> startMin + 1
                        rawEnd < startMin + 1 -> startMin + 1
                        rawEnd > 1440 -> 1440
                        else -> rawEnd
                    }
                    val lane = lanes[session.id] ?: 0
                    val topY = (startMin / 60f) * pixelsPerHour
                    val totalH = (endMin / 60f - startMin / 60f) * pixelsPerHour
                    val laneH = if (totalH > 8f) (totalH - 4f) / (laneCount.coerceAtLeast(1)).toFloat() else totalH
                    val laneY = topY + 2f + lane * laneH
                    val laneHeightPx = (laneH - 2f).coerceAtLeast(2f)

                    // M18.66-FIX12: Label nur anzeigen, wenn genug Platz.
                    // Bedingung: Block-Höhe >= 22dp (Label-Höhe + Padding).
                    // Bei pixelsPerHour=30 (sehr weit raus) = 0.5dp/min.
                    // Eine 5-min-Activity = 2.5dp → kein Label.
                    // Eine 60-min-Activity = 30dp → Label sichtbar.
                    // Bei pixelsPerHour=120 (rein) = 2dp/min.
                    // Eine 5-min-Activity = 10dp → kein Label.
                    // Eine 15-min-Activity = 30dp → Label sichtbar.
                    if (laneHeightPx >= minLabelHeightPx) {
                        Box(
                            modifier = Modifier
                                .padding(start = blockX + 6.dp, top = laneY.dp)
                                .pointerInput(session.id) {
                                    detectTapGestures(
                                        onTap = { onOpen(session.id) },
                                        onLongPress = { onEdit(session.id) }
                                    )
                                }
                        ) {
                            Text(
                                text = "${session.title} · ${session.duration}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Pinch-Indikator + Zoom-Slider ist bereits in DayCalendarTimeline
            // — hier nur der aktuelle Wert als Hinweis.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.sm, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${"%.0f".format(pixelsPerHour)} px/h",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * M18.66-FIX14: Wochenansicht — 7 Tage (Mo–So) nebeneinander.
 * Jede Spalte ist 1/7 der Breite und zeigt einen vertikalen Zeitstrahl
 * mit farbigen Activity-Blöcken (wie die Tagesansicht, aber schmaler
 * und ohne Labels — nur die farbigen Blöcke).
 *
 * Tap auf eine Spalte schaltet zurück in die Tagesansicht des getappten Tages.
 * Der Zoom (pixelsPerHour) ist derselbe wie in der Tagesansicht.
 * Vertikales Scrollen für 24h wie in der Tagesansicht.
 */
@Composable
private fun WeekTimeline(
    weekSessions: Map<LocalDate, List<TimelineSessionUi>>,
    pixelsPerHour: Float,
    onColumnTap: (LocalDate) -> Unit
) {
    val totalHeight = (24 * pixelsPerHour).dp
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val nowMinute = TimeFormatting.minutesOfDay(now, zone).coerceIn(0, 1440)
    val today = LocalDate.now()

    // M18.66-FIX14: Wochentage deutsch kurz (Mo, Di, Mi, Do, Fr, Sa, So)
    val dayLabels = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    // Sortiere die Map nach Datum (Mo zuerst)
    val sortedDays = weekSessions.entries.sortedBy { it.key }
    // Falls die Map leer ist (sollte nicht passieren), berechne die Tage
    // aus dem heutigen Datum als Fallback.
    val days = if (sortedDays.size == 7) {
        sortedDays.map { it.key to it.value }
    } else {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        (0..6).map { offset ->
            val day = monday.plusDays(offset.toLong())
            day to (weekSessions[day] ?: emptyList())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: 7 Wochentage + Datum nebeneinander
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                days.forEachIndexed { index, (day, _) ->
                    val isToday = day == today
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(AevumRadius.sm))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                dayLabels[index],
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isToday) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "%d.".format(day.dayOfMonth),
                                fontSize = 9.sp,
                                color = if (isToday) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // Scrollbarer Bereich mit 7 Spalten
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    days.forEachIndexed { _, (day, daySessions) ->
                        val isToday = day == today
                        WeekColumn(
                            daySessions = daySessions,
                            pixelsPerHour = pixelsPerHour,
                            isToday = isToday,
                            nowMinute = if (isToday) nowMinute else -1,
                            modifier = Modifier
                                .weight(1f)
                                .pointerInput(day) {
                                    detectTapGestures { onColumnTap(day) }
                                }
                        )
                    }
                }
            }
        }
    }
}

/**
 * M18.66-FIX14: Eine einzelne Wochenspalte — vertikaler Zeitstrahl
 * mit farbigen Session-Blöcken. Ohne Labels, ohne Trigger-Marker.
 * Nur die farbigen Blöcke, wie in der Tagesansicht aber schmaler.
 */
@Composable
private fun WeekColumn(
    daySessions: List<TimelineSessionUi>,
    pixelsPerHour: Float,
    isToday: Boolean,
    nowMinute: Int,
    modifier: Modifier = Modifier
) {
    val totalHeight = (24 * pixelsPerHour).dp
    // M18.66-FIX15: Activity-Icons in der Wochenansicht — wie in der
    // Tagesansicht zoom-abhängig: Icon nur wenn der Block hoch genug
    // ist (>= 18dp). Bei weit herausgezoomten kurzen Activities
    // verschwinden die Icons; beim Reinzoomen erscheinen sie wieder.
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minIconHeightPx = with(density) { 18.dp.toPx() }
    val iconSize = with(density) { 12.dp.toPx() }      // etwas kleiner als Tagesansicht (schmale Spalten)
    val pillW = with(density) { 20.dp.toPx() }
    val pillH = iconSize + with(density) { 4.dp.toPx() }
    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .height(totalHeight)
    ) {
        val pxHour = pixelsPerHour.dp.toPx()
        val w = size.width
        val h = size.height

        // Sanfter Hintergrund für "heute"
        if (isToday) {
            drawRect(
                color = Color.White.copy(alpha = 0.04f),
                size = Size(w, h)
            )
        }

        // Stundengitter (alle 3 Stunden etwas heller)
        for (hour in 0..24) {
            val y = hour * pxHour
            val isMajor = hour % 3 == 0
            drawLine(
                color = Color.White.copy(alpha = if (isMajor) 0.14f else 0.05f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (isMajor) 1f else 0.5f
            )
        }

        // Session-Blöcke — nur farbige Blöcke, keine Labels
        daySessions.forEach { session ->
            val startMin = session.startMinuteOfDay.coerceIn(0, 1440)
            val rawEnd = session.endMinuteOfDay
            val endMin = when {
                rawEnd <= 0 -> startMin + 1
                rawEnd < startMin + 1 -> startMin + 1
                rawEnd > 1440 -> 1440
                else -> rawEnd
            }
            val topY = (startMin / 60f) * pxHour
            val bottomY = (endMin / 60f) * pxHour
            val blockH = (bottomY - topY).coerceAtLeast(3f)
            val blockW = (w - 2f).coerceAtLeast(0f)
            val color = if (session.activityColor != 0L) Color(session.activityColor) else categoryColor(session.categoryName)
            val fillAlpha = if (session.isOverlapping) 0.80f else 0.62f
            drawRoundRect(
                color = color.copy(alpha = fillAlpha),
                topLeft = Offset(1f, topY),
                size = Size(blockW, blockH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            // Akzentbalken links (wie Tagesansicht)
            drawRoundRect(
                color = color.copy(alpha = 0.95f),
                topLeft = Offset(1f, topY),
                size = Size(3.dp.toPx().coerceAtMost(blockW), blockH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
            // M18.66-FIX15: Activity-Icon im Block (zoom-abhängig).
            // Gleiche Logik wie Tagesansicht: Icon nur wenn Block-Höhe
            // >= 18dp. Eine 5-min-Activity bei pixelsPerHour=18 (weit
            // raus) = 1.5dp → kein Icon. Bei pixelsPerHour=180 (rein)
            // = 15dp → immer noch unter 18dp — erst ab ~7min Dauer
            // erscheint das Icon. Das verhindert Überlappung bei kurzen
            // Activities, genau wie der User es beschrieben hat.
            if (blockH >= minIconHeightPx &&
                session.activityIcon.isNotBlank() && session.activityIcon != "•"
            ) {
                val iconX = 5.dp.toPx()
                val iconY = topY + (blockH - pillH) / 2f
                // Pill-Hintergrund nur wenn er in die Spaltenbreite passt
                if (pillW <= w - 4.dp.toPx()) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.28f),
                        topLeft = Offset(iconX, iconY),
                        size = Size(pillW, pillH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH / 2f, pillH / 2f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = session.activityIcon,
                        topLeft = Offset(iconX + 2.dp.toPx(), iconY + (pillH - iconSize) / 2f),
                        style = TextStyle(fontSize = 12.sp)
                    )
                }
            }
        }

        // Jetzt-Linie (nur für "heute")
        if (isToday && nowMinute in 0..1440) {
            val nowY = (nowMinute / 60f) * pxHour
            drawLine(
                color = Color(0xFFEC4899),
                start = Offset(0f, nowY),
                end = Offset(w, nowY),
                strokeWidth = 1.5f
            )
        }
    }
}

@Composable
private fun EditorHeader(isEditing: Boolean, duration: String, onBack: () -> Unit, onSave: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(if (isEditing) "Aktivität bearbeiten" else "Neue Aktivität", fontSize = 28.sp, fontWeight = FontWeight.SemiBold); Text("Dauer: $duration", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace) }; Column(horizontalAlignment = Alignment.End) { TextButton(onClick = onBack) { Text("Abbrechen") }; Button(onClick = onSave) { Text("Speichern") } } } }
}

@Composable
private fun BasicFields(state: ActivityEditorUiState, onTitle: (String) -> Unit, onDescription: (String) -> Unit) {
    AevumCard(variant = CardVariant.Gradient) { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) { OutlinedTextField(value = state.form.title, onValueChange = onTitle, modifier = Modifier.fillMaxWidth(), label = { Text("Was hast du gemacht?") }, placeholder = { Text("z. B. Deep Work, Motorradfahrt, Sport") }, singleLine = true); OutlinedTextField(value = state.form.description, onValueChange = onDescription, modifier = Modifier.fillMaxWidth(), label = { Text("Notiz optional") }, minLines = 2, maxLines = 4) } }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedActivitySelector(types: List<ActivityType>, selectedId: String?, onSelect: (ActivityType) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    val selected = types.firstOrNull { it.id == selectedId }
    AevumCard { Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { Text("Aktivität", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("Eine Auswahl reicht — Kategorie wird intern automatisch gesetzt.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onClick = { showSheet = true }, modifier = Modifier.fillMaxWidth()) { Text(selected?.name ?: "Aktivität auswählen") } } }
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.padding(AevumSpacing.md), verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                Text("Aktivität auswählen", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = "", onValueChange = {}, modifier = Modifier.fillMaxWidth(), enabled = false, label = { Text("Suche vorbereitet") }, placeholder = { Text("M6+: Aktivität suchen") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { types.forEach { type -> FilterChip(selected = type.id == selectedId, onClick = { onSelect(type); showSheet = false }, label = { Text(type.name) }) } }
                Spacer(Modifier.height(AevumSpacing.lg))
            }
        }
    }
}

@Composable
private fun VisualTimeEditorCard(
    state: ActivityEditorUiState,
    onStartMinute: (Int) -> Unit,
    onEndMinute: (Int) -> Unit,
    onStartHour: (Int) -> Unit,
    onStartQuarter: (Int) -> Unit,
    onEndHour: (Int) -> Unit,
    onEndQuarter: (Int) -> Unit,
    onSnapStart: (TriggerEventMarker) -> Unit,
    onSnapEnd: (TriggerEventMarker) -> Unit,
    // M18.66-FIX17: "Ende offen"-Modus — endAt = null → Session läuft
    // ab Startzeit weiter. Schalter zwischen "Endzeit" und "Ende offen";
    // bei "Ende offen" wird der Endzeit-Picker ausgeblendet.
    openEnded: Boolean,
    onOpenEndedChange: (Boolean) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(state.form.startAt).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(state.form.endAt ?: state.form.startAt).atZone(zone).toLocalTime()
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Zeitfenster", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Uhr antippen & drehen · Snap 5 min", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (openEnded) "läuft weiter…" else state.duration,
                    color = if (openEnded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp
                )
            }
            // M18.66-FIX17: Schalter "Endzeit" / "Ende offen" (Segment-UI,
            // konsistent mit dem Quick-Create-Dialog).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AevumRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                SegmentButton(
                    label = "Mit Endzeit",
                    selected = !openEnded,
                    modifier = Modifier.weight(1f)
                ) { onOpenEndedChange(false) }
                SegmentButton(
                    label = "● Ende offen",
                    selected = openEnded,
                    modifier = Modifier.weight(1f)
                ) { onOpenEndedChange(true) }
            }
            // M18.44-REDESIGN (User: "statt +15/−60 Buttons einfach Uhrzeit-
            // Picker, richtig fancy"): Die 380dp-Drag-Rail mit den Bump-
            // Chips (−h/+h/−15/+15) ist ersetzt durch ZWEI analoge
            // AevumTimePicker-Uhren (Start = Sonnengold, Ende = Primary).
            // Die Uhren haben einen 5-Minuten-Snap, eine digitale
            // Anzeige zum Antippen (Stunde/Minute direkt waehlen) und
            // +/− Tasten fuer Feintuning — alles ohne Standard-Library.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    AevumTimePicker(
                        initialHour = start.hour,
                        initialMinute = start.minute,
                        accent = Color(0xFFF5A623),
                        onTimeChange = { h, m ->
                            onStartHour(h)
                            onStartQuarter(m)
                        },
                        label = "START",
                        showDigitalDisplay = true
                    )
                }
                // M18.66-FIX17: Endzeit-Picker nur bei "Mit Endzeit".
                if (!openEnded) {
                    Box(modifier = Modifier.weight(1f)) {
                        AevumTimePicker(
                            initialHour = end.hour,
                            initialMinute = end.minute,
                            accent = MaterialTheme.colorScheme.primary,
                            onTimeChange = { h, m ->
                                onEndHour(h)
                                onEndQuarter(m)
                            },
                            label = "ENDE",
                            showDigitalDisplay = true
                        )
                    }
                }
            }
            if (openEnded) {
                Text(
                    "Die Aufzeichnung startet ab ${TimeFormatting.formatTime(state.form.startAt)} und läuft weiter, bis du sie manuell beendest.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // M18.44: Trigger-Snap bleibt als dezente Quick-Action erhalten —
            // ein Tap setzt Start/Ende exakt auf den Trigger-Zeitpunkt.
            if (state.triggerMarkers.isNotEmpty()) {
                TriggerSnapRow(state.triggerMarkers, onSnapStart, onSnapEnd)
            }
        }
    }
}

@Composable
private fun TriggerSnapRow(markers: List<TriggerEventMarker>, onSnapStart: (TriggerEventMarker) -> Unit, onSnapEnd: (TriggerEventMarker) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) { Text("Trigger Marker (Architektur vorbereitet)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) { markers.forEach { marker -> AssistChip(onClick = { onSnapStart(marker) }, label = { Text("Start: ${TimeFormatting.formatTime(marker.occurredAt)} ${marker.label}") }); AssistChip(onClick = { onSnapEnd(marker) }, label = { Text("Ende: ${TimeFormatting.formatTime(marker.occurredAt)}") }) } } }
}

@Composable
private fun ValidationCard(validation: SessionValidationResult, errorMessage: String?) { val message = errorMessage ?: when (validation) { SessionValidationResult.Valid -> "Zeitfenster plausibel. Du kannst speichern."; is SessionValidationResult.Invalid -> validation.message; is SessionValidationResult.Warning -> validation.message }; AevumCard(variant = CardVariant.Filled) { Text(message, fontSize = 13.sp, color = if (validation is SessionValidationResult.Invalid || errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }
