package com.d_drostes_apps.aevum.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.domain.liveactivity.RecentActivityType
import com.d_drostes_apps.aevum.ui.theme.AevumCategoryColors
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Activity launcher — ersetzt das frühere Orbit-/Planetensystem (M33).
 *
 * Warum: Das Planetensystem war hübsch anzusehen, aber als Auswahlfläche
 * unbrauchbar — bewegte Ziele, lange Suchwege, keine eindeutige Reihenfolge.
 * Der neue Starter ist bewusst ruhig und lesbar:
 *
 *  - Kopf mit Titel, Suche, „+ Neu" und Schließen — alles ohne Scrollen.
 *  - „Zuletzt" als horizontale Schnellstart-Reihe (nur wenn vorhanden).
 *  - „Alle Aktivitäten" als ruhiges 3-Spalten-Raster: Icon, Name, Favoriten
 *    zuerst. Keine Rotation, kein Drift, keine versteckten Treffer.
 *  - Tap öffnet ein Detail-Panel (Sofort / Vorlaufzeit) — der bewährte
 *    Startpfad bleibt erhalten.
 *  - „+ Neu" führt in den Anlegen-Screen (nicht in einen Dialog) — der User
 *    baut dort Name, Symbol, Farbe und Kategorie in einem eigenen Schritt.
 *
 * Alle Farben kommen aus Theme bzw. Aktivitäts-Akzent; keine externen
 * Bibliotheken.
 */

const val ACTIVITY_LAUNCHER_TAG = "activity_launcher"
const val ACTIVITY_LAUNCHER_NEW_TAG = "activity_launcher_new"
const val ACTIVITY_LAUNCHER_TILE_PREFIX = "activity_launcher_tile_"

private val TILE_SHAPE = RoundedCornerShape(20.dp)
private val PANEL_SHAPE = RoundedCornerShape(26.dp)

@Composable
fun ActivityLauncherSheet(
    activityTypes: List<ActivityType>,
    recents: List<RecentActivityType>,
    onStart: (String) -> Unit,
    onStartWithTime: (String, String?, Long) -> Unit,
    onCreateActivity: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showTimeMode by remember { mutableStateOf(false) }
    var retroMinutes by remember { mutableIntStateOf(0) }
    var exactHour by remember { mutableIntStateOf(-1) }
    var exactMinute by remember { mutableIntStateOf(-1) }

    val typeById = remember(activityTypes) { activityTypes.associateBy { it.id } }
    val ordered = remember(activityTypes, recents) {
        val recentIndex = recents.mapIndexed { index, recent -> recent.id to index }.toMap()
        activityTypes.sortedWith(
            compareByDescending<ActivityType> { it.isFavorite }
                .thenBy { recentIndex[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
    }
    val quickStart = remember(activityTypes, recents) {
        val recentTypes = recents.mapNotNull { typeById[it.id] }
        val fallback = activityTypes.filter { it.isFavorite }
        (if (recentTypes.isNotEmpty()) recentTypes else fallback)
            .distinctBy { it.id }
            .take(6)
    }
    val filtered = remember(ordered, query) {
        val needle = query.trim()
        if (needle.isEmpty()) ordered
        else ordered.filter { it.name.contains(needle, ignoreCase = true) }
    }
    val selected = selectedId?.let { typeById[it] }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
    }

    fun closeSelection() {
        selectedId = null
        showTimeMode = false
    }

    BackHandler {
        when {
            showTimeMode -> showTimeMode = false
            selected != null -> closeSelection()
            else -> onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ACTIVITY_LAUNCHER_TAG)
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.surface,
                        scheme.background,
                        scheme.background,
                    )
                )
            )
            // Freie Fläche schließt die Auswahl — und schluckt jeden Tap,
            // damit nichts ins Dashboard dahinter durchsickert.
            .pointerInput(Unit) {
                detectTapGestures { closeSelection() }
            }
            .imePadding()
    ) {
        // Ruhiger Lichtkegel oben — Tiefe statt Deko.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(scheme.primary.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width / 2f, 0f),
                    radius = size.width * 0.85f,
                ),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * 0.85f,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            LauncherHeader(
                query = query,
                onQueryChange = { query = it },
                onNewActivity = {
                    keyboard?.hide()
                    onCreateActivity()
                },
                onDismiss = {
                    keyboard?.hide()
                    onDismiss()
                },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = AevumSpacing.md,
                    end = AevumSpacing.md,
                    top = AevumSpacing.xs,
                    bottom = 180.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (query.isBlank() && quickStart.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(stringResource(R.string.launcher_recent))
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickStart.size, key = { quickStart[it].id }) { index ->
                                val type = quickStart[index]
                                QuickStartChip(
                                    type = type,
                                    onClick = { selectedId = type.id; showTimeMode = false },
                                )
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(Modifier.height(AevumSpacing.sm))
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel(stringResource(R.string.launcher_all))
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = filtered.size.toString(),
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                items(filtered, key = { it.id }) { type ->
                    ActivityTile(
                        type = type,
                        selected = type.id == selectedId,
                        appear = appear.value,
                        onClick = { selectedId = type.id; showTimeMode = false },
                    )
                }

                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AevumSpacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.launcher_empty),
                                fontSize = 14.sp,
                                color = scheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(AevumSpacing.sm))
                            TextButton(onClick = onCreateActivity) {
                                Text(stringResource(R.string.launcher_new_activity))
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(AevumSpacing.lg))
                }
            }
        }

        // Detail-Panel — bewährter Startpfad, nur ruhiger inszeniert.
        AnimatedVisibility(
            visible = selected != null && !showTimeMode,
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.md),
        ) {
            selected?.let { type ->
                val accent = accentForType(type)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PANEL_SHAPE)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    scheme.surfaceVariant,
                                    scheme.surface,
                                )
                            )
                        )
                        .border(1.dp, accent.copy(alpha = 0.38f), PANEL_SHAPE)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(AevumSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconOrb(type = type, accent = accent, size = 48.dp)
                        Spacer(Modifier.width(AevumSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                type.name,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.launcher_pick_start),
                                fontSize = 11.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { closeSelection() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_cancel),
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Surface(
                            onClick = {
                                keyboard?.hide()
                                onStart(type.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = accent,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.launcher_start_now),
                                    color = pickOnColor(accent),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                        Surface(
                            onClick = { showTimeMode = true },
                            shape = RoundedCornerShape(16.dp),
                            color = accent.copy(alpha = 0.14f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                accent.copy(alpha = 0.42f),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.launcher_lead_time),
                                    color = accent,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selected != null && showTimeMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.md),
            ) {
                RetroactivePanel(
                    accent = accentForType(selected!!),
                    minutes = retroMinutes,
                    onMinutesChange = { retroMinutes = it },
                    onExactTime = {
                        val cal = java.util.Calendar.getInstance()
                        exactHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        exactMinute = cal.get(java.util.Calendar.MINUTE)
                    },
                    onStart = { minutes ->
                        val startMs = System.currentTimeMillis() - minutes * 60_000L
                        onStartWithTime(selected!!.id, null, startMs)
                        onDismiss()
                    },
                    onBack = { showTimeMode = false },
                )
            }
        }

        if (exactHour >= 0 && selected != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { exactHour = -1 },
                title = {
                    Text(
                        stringResource(R.string.launcher_exact_time_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                text = {
                    AevumTimePicker(
                        initialHour = exactHour,
                        initialMinute = exactMinute,
                        accent = accentForType(selected!!),
                        onTimeChange = { hour, minute ->
                            exactHour = hour
                            exactMinute = minute
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cal = java.util.Calendar.getInstance()
                        cal.set(java.util.Calendar.HOUR_OF_DAY, exactHour)
                        cal.set(java.util.Calendar.MINUTE, exactMinute)
                        cal.set(java.util.Calendar.SECOND, 0)
                        cal.set(java.util.Calendar.MILLISECOND, 0)
                        if (cal.timeInMillis > System.currentTimeMillis()) {
                            cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                        }
                        onStartWithTime(selected!!.id, null, cal.timeInMillis)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.launcher_start_at_time))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { exactHour = -1 }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Kopf
// ---------------------------------------------------------------------------

@Composable
private fun LauncherHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onNewActivity: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AevumSpacing.md)
            .padding(top = AevumSpacing.sm, bottom = AevumSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.launcher_title),
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    stringResource(R.string.launcher_subtitle),
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            Surface(
                onClick = onNewActivity,
                shape = RoundedCornerShape(14.dp),
                color = scheme.primary.copy(alpha = 0.16f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    scheme.primary.copy(alpha = 0.35f),
                ),
                modifier = Modifier.testTag(ACTIVITY_LAUNCHER_NEW_TAG),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.launcher_new),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = scheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_cancel),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(AevumSpacing.md))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    stringResource(R.string.launcher_search),
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(19.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.launcher_clear_search),
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.30f),
                focusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.38f),
                unfocusedBorderColor = scheme.outlineVariant.copy(alpha = 0.45f),
                focusedBorderColor = scheme.primary.copy(alpha = 0.65f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Bausteine
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun QuickStartChip(
    type: ActivityType,
    onClick: () -> Unit,
) {
    val accent = accentForType(type)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.13f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                type.icon.ifBlank { "•" },
                fontSize = 17.sp,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                type.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** Kleinste-Bein-Länge: 112dp hält Icon, zwei Namenszeilen und den Favoriten-
 *  Punkt ohne Abschneiden. */
private val TILE_HEIGHT = 112.dp

@Composable
private fun ActivityTile(
    type: ActivityType,
    selected: Boolean,
    appear: Float,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = accentForType(type)
    val shape = TILE_SHAPE
    // Gestaffeltes Einblenden ohne Endlos-Animation: stabil, ruhig, testbar.
    val alpha = appear.coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .height(TILE_HEIGHT)
            .graphicsLayer {
                this.alpha = alpha
                translationY = (1f - alpha) * 18f
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = if (type.isFavorite) 0.22f else 0.14f),
                        scheme.surfaceVariant.copy(alpha = 0.55f),
                    )
                )
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = accent.copy(alpha = if (selected) 0.95f else 0.26f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .testTag(ACTIVITY_LAUNCHER_TILE_PREFIX + type.id)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(type.icon.ifBlank { "•" }, fontSize = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            type.name,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (type.isFavorite) {
            Text(
                "★",
                fontSize = 8.sp,
                color = accent,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun IconOrb(
    type: ActivityType,
    accent: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.20f))
            .border(1.dp, accent.copy(alpha = 0.40f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(type.icon.ifBlank { "•" }, fontSize = 22.sp)
    }
}

// ---------------------------------------------------------------------------
// Vorlaufzeit-Ring
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetroactivePanel(
    accent: Color,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    onExactTime: () -> Unit,
    onStart: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val onColor = scheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PANEL_SHAPE)
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.surfaceVariant,
                        scheme.surface,
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.38f), PANEL_SHAPE)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.launcher_lead_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onColor,
                )
                Text(
                    stringResource(R.string.launcher_lead_sub),
                    fontSize = 11.sp,
                    color = onColor.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.common_back), color = onColor.copy(alpha = 0.7f))
            }
        }

        val agoTemplate = stringResource(R.string.launcher_started_ago)
        val nowChipLabel = stringResource(R.string.launcher_now_chip)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            var ringSize by remember { mutableStateOf(IntSize.Zero) }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { ringSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset -> minutesFromAngle(ringSize, offset, onMinutesChange) },
                            onDrag = { change, _ ->
                                change.consume()
                                minutesFromAngle(ringSize, change.position, onMinutesChange)
                            },
                        )
                    },
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val ringR = min(size.width, size.height) / 2f - 26.dp.toPx()
                drawCircle(
                    color = onColor.copy(alpha = 0.10f),
                    radius = ringR,
                    center = center,
                    style = Stroke(18.dp.toPx()),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.35f), accent)),
                    startAngle = -90f,
                    sweepAngle = 360f * (minutes / 120f),
                    useCenter = false,
                    topLeft = Offset(center.x - ringR, center.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2f, ringR * 2f),
                    style = Stroke(18.dp.toPx()),
                )
                for (tick in listOf(0, 15, 30, 45, 60, 90, 120)) {
                    val angle = Math.toRadians(-90.0 + 360.0 * tick / 120.0)
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(
                        color = if (kotlin.math.abs(minutes - tick) <= 2) accent
                        else onColor.copy(alpha = 0.25f),
                        start = center + direction * (ringR - 16.dp.toPx()),
                        end = center + direction * (ringR + 16.dp.toPx()),
                        strokeWidth = 2.5.dp.toPx(),
                    )
                }
                val knobAngle = Math.toRadians(-90.0 + 360.0 * minutes / 120.0)
                val knob = center + Offset(cos(knobAngle).toFloat(), sin(knobAngle).toFloat()) * ringR
                drawCircle(color = scheme.surface, radius = 11.dp.toPx(), center = knob)
                drawCircle(color = accent, radius = 8.dp.toPx(), center = knob)

                val label = if (minutes == 0) nowChipLabel
                else "-${formatMinutesLabel(minutes)}"
                val labelText = textMeasurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(label),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onColor,
                    ),
                )
                drawText(
                    textLayoutResult = labelText,
                    topLeft = Offset(
                        center.x - labelText.size.width / 2f,
                        center.y - labelText.size.height / 2f - 14.dp.toPx(),
                    ),
                )
                val minutesLabel = formatMinutesLabel(minutes)
                val subText = if (minutesLabel.isEmpty()) "" else agoTemplate.format(minutesLabel)
                if (subText.isNotEmpty()) {
                    val subLayout = textMeasurer.measure(
                        text = androidx.compose.ui.text.AnnotatedString(subText),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 11.sp,
                            color = onColor.copy(alpha = 0.55f),
                        ),
                    )
                    drawText(
                        textLayoutResult = subLayout,
                        topLeft = Offset(
                            center.x - subLayout.size.width / 2f,
                            center.y - subLayout.size.height / 2f + 18.dp.toPx(),
                        ),
                    )
                }
            }
        }

        // Zwei Reihen statt einer scrollbaren: Material3 erzwingt 48dp
        // Mindest-Touchbreite je Chip; sieben Chips (360dp) passen auf
        // 360dp-Geräten nicht in eine Reihe. FlowRow zeigt alle Optionen
        // vollständig — kein verstecktes Scrollen, keine abgeschnittenen
        // Ränder.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
            maxItemsInEachRow = 4,
        ) {
            listOf(0, 5, 10, 15, 30, 45, 60).forEach { value ->
                val active = minutes == value
                Surface(
                    onClick = { onMinutesChange(value) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) accent else onColor.copy(alpha = 0.08f),
                ) {
                    Text(
                        if (value == 0) stringResource(R.string.launcher_now_chip) else "-${value}m",
                        fontSize = 10.sp,
                        color = if (active) pickOnColor(accent) else onColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onExactTime,
                shape = RoundedCornerShape(12.dp),
                color = onColor.copy(alpha = 0.08f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = onColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(R.string.launcher_exact_time),
                        fontSize = 11.sp,
                        color = onColor.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { onStart(minutes) },
                shape = RoundedCornerShape(12.dp),
                color = accent,
            ) {
                Text(
                    if (minutes == 0) stringResource(R.string.launcher_start_now)
                    else stringResource(R.string.launcher_start_with_time),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = pickOnColor(accent),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
internal fun accentForType(type: ActivityType): Color =
    if (type.color != 0L) Color(type.color)
    else when (type.defaultCategoryId) {
        "work" -> AevumCategoryColors.work
        "sleep" -> AevumCategoryColors.sleep
        "sport" -> AevumCategoryColors.sport
        "learning" -> AevumCategoryColors.learning
        "health" -> Color(0xFF2DD4BF)
        "leisure" -> AevumCategoryColors.leisure
        "relationships" -> AevumCategoryColors.relationships
        "transport" -> AevumCategoryColors.driving
        "digital" -> AevumCategoryColors.smartphone
        "household" -> AevumCategoryColors.household
        else -> AevumCategoryColors.unknown
    }

internal fun formatMinutesLabel(minutes: Int): String = when {
    minutes == 0 -> ""
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/**
 * Wählt die Textfarbe mit dem HÖHEREN WCAG-Kontrast statt per Bauchgefühl-
 * Schwelle: Auf kräftigem Grün/Orange/Blau ist dunkle Schrift besser lesbar,
 * auf Indigo/Violett weiße. Beide Kandidaten werden berechnet, der Gewinner
 * gewinnt — dadurch bleibt kein Akzent mit grenzwertigem Kontrast zurück.
 */
internal fun pickOnColor(accent: Color): Color {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    val ink = Color(0xFF141826)
    val background = luminance(accent)
    val contrastWhite = 1.05 / (background + 0.05)
    val contrastInk = (background + 0.05) / (luminance(ink) + 0.05)
    return if (contrastInk >= contrastWhite) ink else Color.White
}

private fun minutesFromAngle(size: IntSize, position: Offset, onChange: (Int) -> Unit) {
    if (size.width <= 0 || size.height <= 0) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val vector = position - center
    if (hypot(vector.x, vector.y) < 40f) return
    var degrees = Math.toDegrees(atan2(vector.y, vector.x).toDouble()).toFloat() + 90f
    if (degrees < 0f) degrees += 360f
    onChange((degrees / 360f * 120f).roundToInt().coerceIn(0, 120))
}
