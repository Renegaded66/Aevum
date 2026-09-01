package com.d_drostes_apps.aevum.ui.screens.dashboard

import com.d_drostes_apps.aevum.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.domain.liveactivity.RecentActivityType
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.AevumTimePicker
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.categoryColor
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.util.AppLocale

/**
 * M9.2/M10: Live Activity Card — Premium-Produkt-Feel.
 *
 * Drei Zustände, ruhig und hochwertig:
 * - Idle: kompakter Hero + einklappbarer Quick-Start mit Favoriten & Kürzlich
 * - Running: Hero mit großer Typografie, dezenter Puls-Dot, Echtzeit-Timer via nowMs
 * - Paused: Hero mit Gesamt/Aktiv, klare Hierarchie, eingefrorener Timer
 */
@Composable
fun LiveActivityCard(
    state: LiveActivityState,
    nowMs: Long,
    activityTypes: List<ActivityType>,
    recents: List<RecentActivityType>,
    favorites: List<ActivityType>,
    onStart: (String, String?) -> Unit,
    // M11: optional custom start time
    onStartWithTime: (String, String?, Long) -> Unit = { _, _, _ -> },
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit = {},
    onToggleFavorite: (ActivityType) -> Unit,
    // M18.12: Neue Aktivität manuell anlegen (Name → neuer ActivityType)
    onCreateActivity: (String) -> Unit = {},
    // M18.23: Aktivität wechseln — aktueller Timer wird beendet, neue gestartet
    onSwitch: (String, String?) -> Unit = { _, _ -> }
) {
    when (state) {
        is LiveActivityState.Idle -> IdleCard(
            activityTypes = activityTypes,
            recents = recents,
            favorites = favorites,
            onStart = onStart,
            onStartWithTime = onStartWithTime,
            onToggleFavorite = onToggleFavorite,
            onCreateActivity = onCreateActivity
        )
        is LiveActivityState.Running -> RunningCard(
            state = state,
            nowMs = nowMs,
            onPause = onPause,
            onStop = onStop,
            onDiscard = onDiscard,
            onSwitch = onSwitch,
            activityTypes = activityTypes
        )
        is LiveActivityState.Paused -> PausedCard(
            state = state,
            nowMs = nowMs,
            onResume = onResume,
            onStop = onStop,
            onDiscard = onDiscard,
            onSwitch = onSwitch
        )
    }
}

// ============================================================
// IDLE — Premium Quick-Start
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun IdleCard(
    activityTypes: List<ActivityType>,
    recents: List<RecentActivityType>,
    favorites: List<ActivityType>,
    onStart: (String, String?) -> Unit,
    onStartWithTime: (String, String?, Long) -> Unit = { _, _, _ -> },
    onToggleFavorite: (ActivityType) -> Unit,
    // M18.12: Neue Aktivität manuell anlegen
    onCreateActivity: (String) -> Unit = {}
) {
    var showPicker by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // M11: optional start time
    var startTimeMode by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<String?>(null) }
    var customStartTime by remember { mutableStateOf(System.currentTimeMillis()) }

    AevumCard(
        variant = CardVariant.Gradient,
        contentPadding = PaddingValues(AevumSpacing.xl)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg),
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Eyebrow — leise
            Text(
                stringResource(R.string.dashboard_start_question),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.4.sp
            )

            // Primary Action — der eine große Button
            PremiumStartButton(
                label = if (startTimeMode) stringResource(R.string.dashboard_start_now)
                else stringResource(R.string.dashboard_begin_section),
                onClick = {
                    if (startTimeMode && selectedType != null) {
                        onStartWithTime(selectedType!!, null, customStartTime)
                        startTimeMode = false
                        selectedType = null
                    } else {
                        showPicker = true
                    }
                }
            )

            // M11/M11.1: "Startzeit ändern" — öffnet nativen TimePicker
            if (!startTimeMode) {
                OutlinedButton(
                    onClick = { startTimeMode = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.dashboard_change_start_ellipsis), fontSize = 12.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    val calendar = remember { java.util.Calendar.getInstance() }
                    val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                    val currentMinute = calendar.get(java.util.Calendar.MINUTE)
                    var showTimePicker by remember { mutableStateOf(true) }
                    var pickedHour by remember { mutableStateOf(currentHour) }
                    var pickedMinute by remember { mutableStateOf(currentMinute) }

                    Text(stringResource(R.string.dashboard_start_at), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Button(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            com.d_drostes_apps.aevum.domain.time.TimeFormatting.formatTime(customStartTime),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (showTimePicker) {
                        AlertDialog(
                            onDismissRequest = { showTimePicker = false; startTimeMode = false },
                            title = { Text(stringResource(R.string.dashboard_pick_start_time), fontWeight = FontWeight.SemiBold) },
                            text = {
                                AevumTimePicker(
                                    initialHour = pickedHour,
                                    initialMinute = pickedMinute,
                                    accent = MaterialTheme.colorScheme.primary,
                                    onTimeChange = { h, m -> pickedHour = h; pickedMinute = m }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val cal = java.util.Calendar.getInstance()
                                    cal.set(java.util.Calendar.HOUR_OF_DAY, pickedHour)
                                    cal.set(java.util.Calendar.MINUTE, pickedMinute)
                                    cal.set(java.util.Calendar.SECOND, 0)
                                    cal.set(java.util.Calendar.MILLISECOND, 0)
                                    if (cal.timeInMillis > System.currentTimeMillis()) {
                                        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                                    }
                                    customStartTime = cal.timeInMillis
                                    showTimePicker = false
                                }) { Text(stringResource(R.string.dashboard_ok)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTimePicker = false; startTimeMode = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            }
                        )
                    }

                    Text(
                        stringResource(
                            R.string.dashboard_retroactive_start,
                            com.d_drostes_apps.aevum.domain.time.TimeFormatting.formatTime(customStartTime)
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { startTimeMode = false }) {
                        Text(stringResource(R.string.dashboard_standard_now), fontSize = 12.sp)
                    }
                }
            }

            // Quick-Start Shortcuts
            CompactQuickStart(
                recents = recents,
                favorites = favorites,
                expanded = expanded,
                onToggleExpanded = { expanded = !expanded },
                onStart = { id, _ ->
                    if (startTimeMode) {
                        onStartWithTime(id, null, customStartTime)
                        startTimeMode = false
                    } else {
                        onStart(id, null)
                    }
                },
                onToggleFavorite = onToggleFavorite,
                onShowAll = { showPicker = true }
            )
        }
    }

    if (showPicker) {
        ActivityPickerSheet(
            activityTypes = activityTypes,
            recents = recents,
            favorites = favorites,
            onStart = { id -> onStart(id, null); showPicker = false; startTimeMode = false; customStartTime = System.currentTimeMillis() },
            onStartWithTime = { id, _, t -> onStartWithTime(id, null, t); showPicker = false; startTimeMode = false; customStartTime = System.currentTimeMillis() },
            onToggleFavorite = onToggleFavorite,
            onCreateActivity = onCreateActivity,
            onDismiss = { showPicker = false },
            // M18.52 (User: "Activity mit Startzeit starten funktioniert
            // nicht mehr — zählt bei 0"): Die in der Karte eingestellte
            // Startzeit ins Sheet durchreichen, sonst startet das Sheet
            // mit "jetzt" und die eingestellte Zeit geht verloren.
            initialStartTime = customStartTime
        )
    }
}

@Composable
private fun PremiumStartButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    )
                )
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "▶",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Text(
                label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactQuickStart(
    recents: List<RecentActivityType>,
    favorites: List<ActivityType>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onStart: (String, String?) -> Unit,
    onToggleFavorite: (ActivityType) -> Unit,
    onShowAll: () -> Unit
) {
    val displayFavorites = favorites.take(3)
    val displayRecents = recents
        .filter { r -> favorites.none { it.id == r.id } }
        .take(if (displayFavorites.isEmpty()) 4 else 2)

    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
        if (displayFavorites.isNotEmpty()) {
            Text(
                stringResource(R.string.dashboard_favorites),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.6.sp
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                displayFavorites.forEach { fav ->
                    QuietChip(
                        label = fav.name,
                        icon = Icons.Filled.Star,
                        onClick = { onStart(fav.id, null) },
                        onLongClick = { onToggleFavorite(fav) }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                if (displayRecents.isNotEmpty()) {
                    Text(
                        stringResource(R.string.dashboard_recents),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.6.sp
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        displayRecents.forEach { rec ->
                            QuietChip(
                                label = rec.title,
                                icon = null,
                                onClick = { onStart(rec.id, null) }
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) stringResource(R.string.dashboard_less)
                else stringResource(R.string.dashboard_more_activities),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onToggleExpanded)
            )
            if (!expanded) {
                Text(
                    text = stringResource(R.string.dashboard_show_all),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onShowAll)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuietChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val baseModifier = Modifier
        .clip(MaterialTheme.shapes.large)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
        .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm + 2.dp)
    val clickModifier = if (onLongClick != null) {
        baseModifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        baseModifier.clickable(onClick = onClick)
    }
    Box(
        modifier = clickModifier,
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ============================================================
// ACTIVITY PICKER — Premium Sheet
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityPickerSheet(
    activityTypes: List<ActivityType>,
    recents: List<RecentActivityType>,
    favorites: List<ActivityType>,
    onStart: (String) -> Unit,
    onStartWithTime: (String, String?, Long) -> Unit = { _, _, _ -> },
    onToggleFavorite: (ActivityType) -> Unit,
    // M18.12: Neue Aktivität manuell anlegen
    onCreateActivity: (String) -> Unit = {},
    onDismiss: () -> Unit,
    // M18.52: In der Karte eingestellte Startzeit (sonst startet das
    // Sheet mit "jetzt" und die Zeit geht verloren).
    initialStartTime: Long = System.currentTimeMillis()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recentTypeIds = recents.map { it.id }.toSet()

    // M11: optional start time in picker
    var showTimeOption by remember { mutableStateOf(false) }
    var pickerSelectedType by remember { mutableStateOf<String?>(null) }
    // M18.52: Mit der übergebenen Startzeit initialisieren statt "jetzt".
    var pickerStartTime by remember { mutableStateOf(initialStartTime) }

    // M18.12: "Neue Aktivität" — Name eingeben, dann anlegen + starten
    var showCreateDialog by remember { mutableStateOf(false) }
    var newActivityName by remember { mutableStateOf("") }

    // M18.15: Wortsuche — filtert Favoriten/Kürzlich/Alle live.
    var searchQuery by remember { mutableStateOf("") }
    val query = searchQuery.trim()
    val filteredFavorites = if (query.isEmpty()) favorites else favorites.filter { it.name.contains(query, ignoreCase = true) }
    val filteredRecents = if (query.isEmpty()) recents else recents.filter { it.title.contains(query, ignoreCase = true) }
    val filteredAll = if (query.isEmpty()) activityTypes else activityTypes.filter { it.name.contains(query, ignoreCase = true) }

    // M18.52: Startet mit der eingestellten Startzeit, wenn sie von
    // "jetzt" abweicht — sonst normal (ohne Zeit). Vorher ging die in
    // der Karte eingestellte Zeit beim Antippen verloren (Start bei 0).
    fun startWithPendingTime(typeId: String) {
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(pickerStartTime - now) > 60_000L) {
            onStartWithTime(typeId, null, pickerStartTime)
        } else {
            onStart(typeId)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                    Text(
                        if (showTimeOption) stringResource(R.string.dashboard_retroactive_title)
                        else stringResource(R.string.dashboard_pick_activity),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (showTimeOption) stringResource(R.string.dashboard_pick_start_time_sub)
                        else stringResource(R.string.dashboard_picker_hint),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // M18.15: Wortsuche — filtert die Liste live.
            if (!showTimeOption) {
                item {
                    androidx.compose.material3.OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.dashboard_search), fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                androidx.compose.material.icons.Icons.Outlined.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large
                    )
                }
            }

            if (showTimeOption) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        val cal = remember { java.util.Calendar.getInstance() }
                        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        val currentMinute = cal.get(java.util.Calendar.MINUTE)
                        var showTimePicker by remember { mutableStateOf(true) }
                        var pickedHour by remember { mutableStateOf(currentHour) }
                        var pickedMinute by remember { mutableStateOf(currentMinute) }

                        Text(stringResource(R.string.dashboard_start_at), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Button(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                com.d_drostes_apps.aevum.domain.time.TimeFormatting.formatTime(pickerStartTime),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (showTimePicker) {
                            AlertDialog(
                                onDismissRequest = { showTimePicker = false },
                                title = { Text(stringResource(R.string.dashboard_start_time), fontWeight = FontWeight.SemiBold) },
                                text = {
                                    AevumTimePicker(
                                        initialHour = pickedHour,
                                        initialMinute = pickedMinute,
                                        accent = MaterialTheme.colorScheme.primary,
                                        onTimeChange = { h, m -> pickedHour = h; pickedMinute = m }
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val c = java.util.Calendar.getInstance()
                                        c.set(java.util.Calendar.HOUR_OF_DAY, pickedHour)
                                        c.set(java.util.Calendar.MINUTE, pickedMinute)
                                        c.set(java.util.Calendar.SECOND, 0)
                                        c.set(java.util.Calendar.MILLISECOND, 0)
                                        if (c.timeInMillis > System.currentTimeMillis()) {
                                            c.add(java.util.Calendar.DAY_OF_MONTH, -1)
                                        }
                                        pickerStartTime = c.timeInMillis
                                        showTimePicker = false
                                    }) { Text(stringResource(R.string.dashboard_ok)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.common_cancel)) }
                                }
                            )
                        }

                        Text(
                            stringResource(
                                R.string.dashboard_retroactive_start,
                                com.d_drostes_apps.aevum.domain.time.TimeFormatting.formatTime(pickerStartTime)
                            ),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Button(onClick = {
                                pickerSelectedType?.let { onStartWithTime(it, null, pickerStartTime) }
                            }) { Text(stringResource(R.string.dashboard_start_now)) }
                            OutlinedButton(onClick = { showTimeOption = false }) { Text(stringResource(R.string.common_back)) }
                        }
                    }
                }
            } else {
                // M18.15: Gefilterte Listen (Wortsuche) statt der vollen.
                // Favorites section
                if (filteredFavorites.isNotEmpty()) {
                    item {
                    SectionLabel(stringResource(R.string.dashboard_favorites))
                }
                items(filteredFavorites, key = { "fav-${it.id}" }) { type ->
                    ActivityRow(
                        type = type,
                        isFavorite = true,
                        onStart = { startWithPendingTime(type.id) },
                        onToggleFavorite = { onToggleFavorite(type) },
                        // M18.59: ⏱ → Vorlaufzeit-Flow für diese Aktivität
                        onStartWithTime = {
                            pickerSelectedType = type.id
                            pickerStartTime = System.currentTimeMillis()
                            showTimeOption = true
                        }
                    )
                }
            }

            if (filteredRecents.any { it.id !in filteredFavorites.map { f -> f.id } }) {
                item { SectionLabel(stringResource(R.string.dashboard_recents)) }
                items(
                    filteredRecents.filter { it.id !in filteredFavorites.map { f -> f.id } },
                    key = { "rec-${it.id}" }
                ) { recent ->
                    val type = activityTypes.firstOrNull { it.id == recent.id }
                    if (type != null) {
                        ActivityRow(
                            type = type,
                            isFavorite = false,
                            onStart = { startWithPendingTime(type.id) },
                            onToggleFavorite = { onToggleFavorite(type) },
                            // M18.59: ⏱ → Vorlaufzeit-Flow für diese Aktivität
                            onStartWithTime = {
                                pickerSelectedType = type.id
                                pickerStartTime = System.currentTimeMillis()
                                showTimeOption = true
                            }
                        )
                    } else {
                        GenericRow(
                            title = recent.title,
                            onStart = { startWithPendingTime(recent.id) }
                        )
                    }
                }
            }

            item { SectionLabel(stringResource(R.string.dashboard_all)) }
            items(
                filteredAll.filter { it.id !in filteredFavorites.map { f -> f.id } && it.id !in filteredRecents.map { r -> r.id } },
                key = { "all-${it.id}" }
            ) { type ->
                ActivityRow(
                    type = type,
                    isFavorite = false,
                    onStart = { startWithPendingTime(type.id) },
                    onToggleFavorite = { onToggleFavorite(type) },
                    // M18.59: ⏱ → Vorlaufzeit-Flow für diese Aktivität
                    onStartWithTime = {
                        pickerSelectedType = type.id
                        pickerStartTime = System.currentTimeMillis()
                        showTimeOption = true
                    }
                )
            }

            // M18.15: Leerer Suchtreffer — Hinweis statt leerer Liste.
            if (query.isNotEmpty() && filteredFavorites.isEmpty() && filteredRecents.isEmpty() && filteredAll.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.dashboard_no_results, query),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = AevumSpacing.md)
                    )
                }
            }

            // M18.12: Neue Aktivität manuell anlegen — direkt aus dem Picker.
            item {
                OutlinedButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(stringResource(R.string.dashboard_create_activity), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            item { Spacer(Modifier.height(AevumSpacing.lg)) }
            } // close else
        }
    }

    // M18.12: Dialog für neue Aktivität — Name reicht, Icon/Farbe folgen
    // im ActivityTypes-Screen (Settings). Nach dem Anlegen wird direkt
    // gestartet — der User will sofort tracken, nicht erst konfigurieren.
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newActivityName = "" },
            title = { Text(stringResource(R.string.dashboard_new_activity), fontWeight = FontWeight.SemiBold) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newActivityName,
                    onValueChange = { newActivityName = it },
                    label = { Text(stringResource(R.string.dashboard_name_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newActivityName.trim()
                        if (name.isNotEmpty()) {
                            onCreateActivity(name)
                            showCreateDialog = false
                            newActivityName = ""
                        }
                    },
                    enabled = newActivityName.trim().isNotEmpty()
                ) { Text(stringResource(R.string.dashboard_create_and_start)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newActivityName = "" }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = AevumSpacing.sm)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ActivityRow(
    type: ActivityType,
    isFavorite: Boolean,
    onStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    // M18.59-FIX: Vorlaufzeit-Start war toter Code (showTimeOption wurde
    // nie auf true gesetzt, pickerSelectedType nie befüllt). Jetzt öffnet
    // der ⏱-Button den Zeit-Flow für genau diese Aktivität.
    onStartWithTime: (() -> Unit)? = null
) {
    // M18.12: Custom-Farbe der Aktivität (falls gesetzt), sonst Kategorie-Farbe.
    val accent = if (type.color != 0L) Color(type.color) else categoryColor(type.defaultCategoryId ?: "unknown")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .combinedClickable(onClick = onStart, onLongClick = onToggleFavorite)
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // M18.12: Icon (Emoji) in farbigem Kreis statt nacktem Punkt
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (type.icon.isBlank()) "•" else type.icon,
                fontSize = 17.sp
            )
        }
        Text(
            type.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        // M18.59: ⏱-Button — Aktivität mit Vorlaufzeit starten
        // (z. B. wenn man zu spät merkt, dass man aufzeichnen sollte).
        if (onStartWithTime != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onStartWithTime),
                contentAlignment = Alignment.Center
            ) {
                Text("⏱", fontSize = 14.sp)
            }
        }
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(R.string.dashboard_cd_favorite),
            tint = if (isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GenericRow(title: String, onStart: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .combinedClickable(onClick = onStart, onLongClick = {})
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================================================
// RUNNING — Hero Card
// ============================================================

@Composable
private fun RunningCard(
    state: LiveActivityState.Running,
    nowMs: Long,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit = {},
    onSwitch: (String, String?) -> Unit = { _, _ -> },
    activityTypes: List<ActivityType> = emptyList()
) {
    val accentColor = categoryColor(state.categoryId ?: "unknown")

    // M18.23: State fuer Wechsel-Sheet
    var showSwitchSheet by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    AevumCard(
        variant = CardVariant.Gradient,
        contentPadding = PaddingValues(AevumSpacing.xl)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
            modifier = Modifier.fillMaxWidth()
        ) {
            // M18.93 FANCY-HEADER: Activity-Icon mit Glow-Ring + pulsierendem
            // Status-Eyebrow + animiertem Ladefortschritt (Fortschritt =
            // Anteil der laufenden Stunde, die schon aufgezeichnet ist —
            // "etwas lädt auf", volle Stunde = 100%).
            val activityIcon = remember(state.title, state.categoryId) {
                activityTypes.firstOrNull { it.name == state.title }?.icon ?: ""
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                // Icon mit pulsierendem Glow-Ring (nur wenn Icon existiert).
                if (activityIcon.isNotBlank()) {
                    Box(contentAlignment = Alignment.Center) {
                        // Glow-Ring (pulsierend, größer).
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .scale(1f + 0.12f * pulseAlpha)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.25f * pulseAlpha))
                        )
                        // Icon-Kern.
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(activityIcon, fontSize = 16.sp)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
                Text(
                    if (state.isAuto) stringResource(R.string.dashboard_running_auto)
                    else stringResource(R.string.common_active),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = accentColor,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.alpha(0.6f + 0.4f * pulseAlpha)
                )
            }

            Text(
                state.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // M12.1: Show origin for auto-started sessions
            if (state.isAuto && state.sourceLabel != null) {
                Text(
                    stringResource(R.string.dashboard_started_by, state.sourceLabel),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Hero-Timer — nowMs drives a real-time recompose
            // M18.61e-FIX: 40sp statt 64sp (User: "Timer Feld ist viel zu groß").
            Text(
                text = formatLiveDuration(state.activeMs(nowMs)),
                fontSize = 40.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = (-1).sp
            )

            // M18.93 AUF-LADE-BALKEN: Fortschritt durch die laufende Stunde
            // (Minute 0-59 → 0-100%). Dezent (3dp), Akzent-Farbe mit
            // Glow-Lauflicht — "etwas lädt sich auf". Nimmt keine extra
            // Höhe weg (10dp inkl. Padding).
            val minuteFraction = remember(nowMs) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
                ((cal.get(java.util.Calendar.MINUTE) * 60 + cal.get(java.util.Calendar.SECOND)) / 3600f)
                    .coerceIn(0f, 1f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.xl)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(minuteFraction)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    accentColor.copy(alpha = 0.55f),
                                    accentColor
                                )
                            )
                        )
                )
                // Glow-Knubbel am Fortschritts-Ende (pulsierend).
                if (minuteFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (minuteFraction * 320).dp - 5.dp)
                            .size(10.dp)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
            }

            state.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(AevumSpacing.sm))

            if (state.isAuto) {
                // M12.1: Auto sessions get Pause + Verwerfen + Stop
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.dashboard_pause))
                    }
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_discard))
                    }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.dashboard_stop))
                    }
                }
            } else {
                HeroActionRow(
                    primary = HeroAction(
                        Icons.Default.Pause,
                        stringResource(R.string.dashboard_pause),
                        onPause,
                        isPrimary = false
                    ),
                    secondary = HeroAction(
                        Icons.Default.Stop,
                        stringResource(R.string.dashboard_stop_long),
                        onStop,
                        isDestructive = true
                    )
                )
            }
            // M18.23: Wechsel-Button — oeffnet ActivityPicker zum Wechseln
            OutlinedButton(
                onClick = { showSwitchSheet = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AevumRadius.full)
            ) {
                Text("\u21C4", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dashboard_switch_activity), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    // M18.23: Switch-Picker als BottomSheet
    if (showSwitchSheet) {
        SwitchActivityPickerSheet(
            currentTitle = state.title,
            activityTypes = activityTypes,
            onSwitch = { newTypeId, newCategoryId ->
                onSwitch(newTypeId, newCategoryId)
                showSwitchSheet = false
            },
            onDismiss = { showSwitchSheet = false }
        )
    }
}

// ============================================================
// PAUSED — Hero Card mit Gesamt/Aktiv
// ============================================================

@Composable
private fun PausedCard(
    state: LiveActivityState.Paused,
    nowMs: Long,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit = {},
    onSwitch: (String, String?) -> Unit = { _, _ -> }
) {
    AevumCard(
        variant = CardVariant.Gradient,
        contentPadding = PaddingValues(AevumSpacing.xl)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Text("⏸", fontSize = 13.sp)
                Text(
                    if (state.isAuto) stringResource(R.string.dashboard_paused_auto)
                    else stringResource(R.string.dashboard_paused),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.4.sp
                )
            }

            Text(
                state.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            // M12.1: Show origin for auto-started sessions
            if (state.isAuto && state.sourceLabel != null) {
                Text(
                    stringResource(R.string.dashboard_started_by, state.sourceLabel),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            // Frozen active timer — nowMs keeps advancing, but activeMs stays constant during pause
            Text(
                text = formatLiveDuration(state.activeMs(nowMs)),
                fontSize = 64.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                letterSpacing = (-1).sp
            )

            // Pause-Differenzierung
            Row(
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DurationStat(label = stringResource(R.string.dashboard_total), value = formatHumanDuration(state.totalMs(nowMs)))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
                DurationStat(label = stringResource(R.string.common_active), value = formatHumanDuration(state.activeMs(nowMs)))
            }

            state.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(AevumSpacing.sm))

            if (state.isAuto) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    OutlinedButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_continue))
                    }
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_discard))
                    }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.dashboard_stop))
                    }
                }
            } else {
                HeroActionRow(
                    primary = HeroAction(
                        Icons.Default.PlayArrow,
                        stringResource(R.string.common_continue),
                        onResume,
                        isPrimary = true
                    ),
                    secondary = HeroAction(
                        Icons.Default.Stop,
                        stringResource(R.string.dashboard_stop_long),
                        onStop,
                        isDestructive = true
                    )
                )
            }
        }
    }
}

private data class HeroAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false
)

@Composable
private fun HeroActionRow(primary: HeroAction, secondary: HeroAction) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeroActionButton(primary)
        HeroActionButton(secondary)
    }
}

@Composable
private fun HeroActionButton(action: HeroAction) {
    val tint = when {
        action.isDestructive -> MaterialTheme.colorScheme.error
        action.isPrimary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val content: @Composable () -> Unit = {
        Icon(
            action.icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(AevumSpacing.xs + 2.dp))
        Text(
            action.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
    if (action.isPrimary) {
        // M10: Same visual weight as OutlinedButton for consistency,
        // filled only with primary tint — kein greller Unterschied.
        Button(
            onClick = action.onClick,
            contentPadding = PaddingValues(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
            modifier = Modifier.heightIn(min = 48.dp)
        ) { content() }
    } else {
        OutlinedButton(
            onClick = action.onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
            contentPadding = PaddingValues(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
            modifier = Modifier.heightIn(min = 48.dp)
        ) { content() }
    }
}

@Composable
private fun DurationStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.6.sp
        )
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================
// FORMAT HELPERS
// ============================================================

fun formatLiveDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(AppLocale.current, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(AppLocale.current, "%02d:%02d", minutes, seconds)
    }
}

fun formatHumanDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "$seconds s"
    }
}

// M18.23: Switch-Picker — einfaches BottomSheet zum Wechseln der Aktivitaet.
// Zeigt alle ActivityTypes mit Icon in farbigem Kreis. Bei Auswahl wird
// onSwitch(typeId, categoryId) aufgerufen — der Aufrufer beendet die
// aktuelle Session und startet die neue.
// M18.60: public gemacht — der Dashboard-Banner nutzt dasselbe Sheet
// fuer den Wechsel-Button neben Pause/Stop.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchActivityPickerSheet(
    currentTitle: String,
    activityTypes: List<ActivityType>,
    onSwitch: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
        ) {
            Text(
                stringResource(R.string.dashboard_switch_to),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = AevumSpacing.sm)
            )
            Text(
                stringResource(R.string.dashboard_current_ending, currentTitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AevumSpacing.sm)
            )
            activityTypes.sortedBy { it.name }.forEach { type ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSwitch(type.id, type.defaultCategoryId)
                        },
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
                        // Icon in farbigem Kreis
                        val color = if (type.color != 0L) Color(type.color)
                            else categoryColor(type.defaultCategoryId ?: "unknown")
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                type.icon.takeIf { it.isNotBlank() } ?: "\u2022",
                                fontSize = 18.sp
                            )
                        }
                        Text(type.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("\u2192", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(AevumSpacing.lg))
        }
    }
}
