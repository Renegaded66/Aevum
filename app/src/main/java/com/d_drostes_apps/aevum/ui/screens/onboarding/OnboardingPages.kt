package com.d_drostes_apps.aevum.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.ui.screens.dashboard.MascotView
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.util.AppLanguage

/**
 * M34 — Die fünf Seiten des Willkommens-Dialogs.
 *
 * Gestaltungsregeln (bewusst durchgehalten):
 *  • Eine Seite = eine Entscheidung. Keine Seite erklärt zwei Dinge.
 *  • Jede Auswahl ist sofort sichtbar (Punkt, Haken, Schalter) — der Nutzer
 *    muss nie „speichern" drücken, um zu sehen, was gilt.
 *  • Nichts blinkt, nichts zählt herunter. Die einzige Bewegung ist der
 *    Seitenwechsel selbst und der aktuelle Punkt.
 *  • Text bleibt Text: keine Werbeversprechen, keine Ausrufezeichen.
 */

private val PAGE_PADDING = 24.dp

// ════════════════════════════════════════════════════════════════════════
// Seite 1 — Sprache
// ════════════════════════════════════════════════════════════════════════

@Composable
internal fun LanguagePage(
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = AevumSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
    ) {
        OnboardingPageHeader(
            eyebrow = oStr(R.string.ob_lang_eyebrow),
            title = oStr(R.string.ob_lang_title),
            subtitle = oStr(R.string.ob_lang_subtitle)
        )

        // Zwei Spalten, feste Zeilen statt Lazy-Grid: die zehn Sprachen passen
        // ohne Scrollen auf jeden gängigen Bildschirm, und der Pager behält
        // seine eigene Scroll-Achse.
        // 52 dp Höhe und 8 dp Abstand: elf Sprachen = sechs Zeilen. Mit 58/10
        // passte die Fußnote unten nicht mehr ganz in den Pager (abgeschnitten).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.selectable.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { language ->
                        LanguageTile(
                            language = language,
                            selected = language.code == selected,
                            onClick = { onSelect(language.code) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Text(
            text = oStr(R.string.ob_lang_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LanguageTile(
    language: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant.copy(alpha = 0.28f),
        animationSpec = tween(180),
        label = "lang-bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.85f) else colors.outlineVariant.copy(alpha = 0.45f),
        animationSpec = tween(180),
        label = "lang-border"
    )
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(AevumRadius.lg))
            .background(background)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(AevumRadius.lg))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = language.flag, fontSize = 19.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = oStr(language.labelRes),
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ════════════════════════════════════════════════════════════════════════
// Seite 2 — Lebensbereiche + Aktivitäten
// ════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FocusPage(
    state: OnboardingViewModel.OnboardingUiState,
    onToggleCategory: (String) -> Unit,
    onToggleActivity: (String) -> Unit,
    onAddCategory: (String) -> Unit,
    onAddActivity: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = AevumSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
    ) {
        OnboardingPageHeader(
            eyebrow = oStr(R.string.ob_focus_eyebrow),
            title = oStr(R.string.ob_focus_title),
            subtitle = oStr(R.string.ob_focus_subtitle)
        )

        // ── Lebensbereiche ────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OnboardingSectionLabel(
                text = oStr(R.string.ob_focus_categories),
                trailing = oStr(
                    R.string.ob_focus_selected,
                    state.selectedCategoryIds.size,
                    state.categories.size
                )
            )
            Text(
                text = oStr(R.string.ob_focus_categories_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 11.5.sp
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.categories.forEach { item ->
                    PickChip(
                        label = item.label,
                        icon = item.icon,
                        colorArgb = item.colorArgb,
                        selected = item.id in state.selectedCategoryIds,
                        onClick = { onToggleCategory(item.id) }
                    )
                }
                AddChip(
                    label = oStr(R.string.ob_focus_add_category),
                    hint = oStr(R.string.ob_focus_add_field_label),
                    confirm = oStr(R.string.ob_focus_add_confirm),
                    cancel = oStr(R.string.ob_focus_add_cancel),
                    onAdd = onAddCategory
                )
            }
        }

        // ── Aktivitäten ────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OnboardingSectionLabel(
                text = oStr(R.string.ob_focus_activities),
                trailing = oStr(
                    R.string.ob_focus_selected,
                    state.selectedActivityIds.size,
                    state.activities.size
                )
            )
            Text(
                text = oStr(R.string.ob_focus_activities_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 11.5.sp
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.activities.forEach { item ->
                    PickChip(
                        label = item.label,
                        icon = item.icon,
                        colorArgb = item.colorArgb,
                        selected = item.id in state.selectedActivityIds,
                        onClick = { onToggleActivity(item.id) }
                    )
                }
                AddChip(
                    label = oStr(R.string.ob_focus_add_activity),
                    hint = oStr(R.string.ob_focus_add_field_label),
                    confirm = oStr(R.string.ob_focus_add_confirm),
                    cancel = oStr(R.string.ob_focus_add_cancel),
                    onAdd = onAddActivity
                )
            }
        }

        if (state.selectedActivityIds.isEmpty()) {
            Text(
                text = oStr(R.string.ob_focus_empty_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Ein auswählbarer Chip.
 *
 * Die Farbe des Eintrags färbt nur die Fläche (14 %) und den Rand (65 %) —
 * nie die Schrift. Damit bleibt der Kontrast auch bei Gelb oder Mint lesbar,
 * ohne pro Farbe eine Textfarbe zu berechnen.
 */
@Composable
private fun PickChip(
    label: String,
    icon: String,
    colorArgb: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = Color(colorArgb)
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) accent.copy(alpha = 0.16f) else colors.surfaceVariant.copy(alpha = 0.22f),
        animationSpec = tween(160),
        label = "chip-bg"
    )
    val borderColor by animateColorAsState(
        if (selected) accent.copy(alpha = 0.7f) else colors.outlineVariant.copy(alpha = 0.4f),
        animationSpec = tween(160),
        label = "chip-border"
    )
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(background)
            .border(if (selected) 1.2.dp else 1.dp, borderColor, RoundedCornerShape(AevumRadius.full))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = if (selected) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(text = icon, fontSize = 13.sp)
        Text(
            text = label,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
        if (selected) {
            Text(
                text = "✕",
                color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * „+ Hinzufügen" — derselbe Chip, aber gestrichelt und ohne Füllung: er sieht
 * aus wie eine Lücke, nicht wie ein Eintrag. Antippen klappt ein Feld auf.
 */
@Composable
private fun AddChip(
    label: String,
    hint: String,
    confirm: String,
    cancel: String,
    onAdd: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AevumRadius.full)

    if (!open) {
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(shape)
                .dashedBorder(colors.outlineVariant.copy(alpha = 0.7f), shape)
                .clickable { open = true }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+  $label",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    } else {
        // Aufgeklappt: Feld + zwei Aktionen, in derselben Chip-Höhe beginnend.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                placeholder = { Text(hint, fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                shape = RoundedCornerShape(AevumRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outlineVariant
                ),
                modifier = Modifier.width(210.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(shape)
                        .background(colors.primary.copy(alpha = 0.18f))
                        .clickable {
                            if (text.isNotBlank()) {
                                onAdd(text)
                                text = ""
                                open = false
                            }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirm, color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(shape)
                        .clickable {
                            text = ""
                            open = false
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(cancel, color = colors.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Gestrichelter Rand ohne zusätzliche Library (Compose bringt keinen mit). */
private fun Modifier.dashedBorder(
    color: Color,
    shape: RoundedCornerShape,
    strokeWidth: Float = 1.2f
): Modifier = this.then(
    Modifier.drawWithContentBorder(color, shape, strokeWidth)
)

private fun Modifier.drawWithContentBorder(
    color: Color,
    shape: RoundedCornerShape,
    strokeWidth: Float
): Modifier = this.then(
    drawBehind {
        // Eckenradius in px: CornerSize braucht die Zeichenfläche + Dichte.
        val r = shape.topStart.toPx(size, this)
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(
                width = strokeWidth * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f * density, 7f * density))
            )
        )
    }
)

// ════════════════════════════════════════════════════════════════════════
// Seite 3 — Maskottchen
// ════════════════════════════════════════════════════════════════════════

@Composable
internal fun MascotPage(
    state: OnboardingViewModel.OnboardingUiState,
    onEnable: (Boolean) -> Unit,
    onColor: (String) -> Unit,
    onAntenna: (String) -> Unit,
    onCheeks: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = AevumSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
    ) {
        OnboardingPageHeader(
            eyebrow = oStr(R.string.ob_mascot_eyebrow),
            title = oStr(R.string.ob_mascot_title),
            subtitle = oStr(R.string.ob_mascot_subtitle)
        )

        // Zwei-Wege-Wahl — groß, ruhig, ohne Dialog.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BinaryChoice(
                text = oStr(R.string.ob_mascot_on),
                selected = state.mascotEnabled,
                onClick = { onEnable(true) },
                modifier = Modifier.weight(1f)
            )
            BinaryChoice(
                text = oStr(R.string.ob_mascot_off),
                selected = !state.mascotEnabled,
                onClick = { onEnable(false) },
                modifier = Modifier.weight(1f)
            )
        }

        // Bühne: das Wesen steht im Licht, unabhängig von der Wahl.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(AevumRadius.xl))
                .background(
                    Brush.radialGradient(
                        listOf(
                            colors.primary.copy(alpha = if (state.mascotEnabled) 0.13f else 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(0.5f, 0.55f),
                        radius = 620f
                    )
                )
                .background(colors.surfaceVariant.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            if (state.mascotEnabled) {
                MascotView(
                    config = MascotRepository.MascotConfig(
                        enabled = true,
                        colorId = state.mascotColor,
                        antennaStyle = state.mascotAntenna,
                        cheeks = state.mascotCheeks
                    ),
                    smileLevel = 0.9f,
                    sleeping = false,
                    mascotSize = 140.dp,
                    contentDescription = oStr(R.string.mascot_cd),
                    testTag = "onboarding-mascot"
                )
                Text(
                    text = oStr(R.string.ob_mascot_hint),
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .dashedBorder(colors.outlineVariant.copy(alpha = 0.8f), RoundedCornerShape(50))
                    )
                    Text(
                        text = oStr(R.string.ob_mascot_preview_off),
                        color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        AnimatedVisibility(visible = state.mascotEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                // Farbe
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = oStr(R.string.ob_mascot_color),
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MascotRepository.COLOR_PALETTE.forEach { (id, color) ->
                            val active = id == state.mascotColor
                            Box(
                                modifier = Modifier
                                    .size(if (active) 30.dp else 26.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (active) 3.dp else 1.dp,
                                        color = if (active) colors.onSurface else colors.outlineVariant.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    )
                                    .clickable { onColor(id) }
                            )
                        }
                    }
                }

                // Antenne
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = oStr(R.string.ob_mascot_antenna),
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MascotRepository.ANTENNA_STYLES.forEach { style ->
                            val active = style == state.mascotAntenna
                            val label = when (style) {
                                MascotRepository.ANTENNA_STAR -> oStr(R.string.settings_mascot_antenna_star)
                                MascotRepository.ANTENNA_BOLT -> oStr(R.string.settings_mascot_antenna_bolt)
                                else -> oStr(R.string.settings_mascot_antenna_ball)
                            }
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(AevumRadius.full))
                                    .background(
                                        if (active) colors.primary.copy(alpha = 0.18f)
                                        else colors.surfaceVariant.copy(alpha = 0.3f)
                                    )
                                    .border(
                                        1.dp,
                                        if (active) colors.primary.copy(alpha = 0.6f)
                                        else colors.outlineVariant.copy(alpha = 0.4f),
                                        RoundedCornerShape(AevumRadius.full)
                                    )
                                    .clickable { onAntenna(style) }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (active) colors.primary else colors.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Bäckchen
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = oStr(R.string.ob_mascot_cheeks),
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.mascotCheeks,
                        onCheckedChange = onCheeks,
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
                    )
                }
            }
        }
    }
}

@Composable
private fun BinaryChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) colors.primary.copy(alpha = 0.15f) else colors.surfaceVariant.copy(alpha = 0.22f),
        animationSpec = tween(180),
        label = "binary-bg"
    )
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(background)
            .border(
                if (selected) 1.4.dp else 1.dp,
                if (selected) colors.primary.copy(alpha = 0.8f) else colors.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(AevumRadius.md)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

// ════════════════════════════════════════════════════════════════════════
// Seite 4 — Berechtigungen
// ════════════════════════════════════════════════════════════════════════

/** Eine Zeile der Berechtigungs-Seite: ID, Symbol, Titel, Zweck. */
private data class PermissionRow(
    val id: String,
    val icon: String,
    val titleRes: Int,
    val descriptionRes: Int
)

private val PERMISSION_ROWS = listOf(
    PermissionRow(
        "location",
        "📍",
        R.string.ob_perm_location_title,
        R.string.ob_perm_location_desc
    ),
    PermissionRow(
        "activity",
        "🏃",
        R.string.ob_perm_activity_title,
        R.string.ob_perm_activity_desc
    ),
    PermissionRow(
        "notifications",
        "🔔",
        R.string.ob_perm_notifications_title,
        R.string.ob_perm_notifications_desc
    ),
    PermissionRow(
        "usage",
        "📱",
        R.string.ob_perm_usage_title,
        R.string.ob_perm_usage_desc
    ),
    PermissionRow(
        "calendar",
        "📅",
        R.string.ob_perm_calendar_title,
        R.string.ob_perm_calendar_desc
    ),
    PermissionRow(
        "calls",
        "📞",
        R.string.ob_perm_calls_title,
        R.string.ob_perm_calls_desc
    )
)

@Composable
internal fun PermissionsPage(
    state: OnboardingViewModel.OnboardingUiState,
    onToggle: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = AevumSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
    ) {
        OnboardingPageHeader(
            eyebrow = oStr(R.string.ob_perm_eyebrow),
            title = oStr(R.string.ob_perm_title),
            subtitle = oStr(R.string.ob_perm_subtitle)
        )

        OnboardingSectionLabel(
            text = oStr(R.string.ob_perm_choice),
            trailing = oStr(
                R.string.ob_perm_selected,
                state.permissions.size,
                PERMISSION_ROWS.size
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AevumRadius.xl))
                .background(colors.surfaceVariant.copy(alpha = 0.22f))
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(AevumRadius.xl))
        ) {
            PERMISSION_ROWS.forEachIndexed { index, row ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 54.dp)
                            .height(1.dp)
                            .background(colors.outlineVariant.copy(alpha = 0.3f))
                    )
                }
                PermissionRowView(
                    row = row,
                    checked = row.id in state.permissions,
                    onToggle = { onToggle(row.id) }
                )
            }
        }

        Text(
            text = oStr(R.string.ob_perm_footnote),
            color = colors.onSurfaceVariant.copy(alpha = 0.8f),
            fontSize = 11.5.sp,
            lineHeight = 16.sp
        )
        Text(
            text = oStr(R.string.ob_perm_none),
            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun PermissionRowView(
    row: PermissionRow,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(AevumRadius.sm))
                .background(
                    if (checked) colors.primary.copy(alpha = 0.16f)
                    else colors.surfaceVariant.copy(alpha = 0.35f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(row.icon, fontSize = 15.sp)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = oStr(row.titleRes),
                color = colors.onSurface,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = oStr(row.descriptionRes),
                color = colors.onSurfaceVariant,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
        )
    }
}

// ════════════════════════════════════════════════════════════════════════
// Seite 5 — Abschluss
// ════════════════════════════════════════════════════════════════════════

/** Ein Vertrauens-Punkt der letzten Seite. */
private data class TrustRow(val icon: String, val titleRes: Int, val descriptionRes: Int)

private val TRUST_ROWS = listOf(
    TrustRow("✦", R.string.ob_trust_free_title, R.string.ob_trust_free_desc),
    TrustRow("🔒", R.string.ob_trust_local_title, R.string.ob_trust_local_desc),
    TrustRow("↺", R.string.ob_trust_control_title, R.string.ob_trust_control_desc)
)

@Composable
internal fun TrustPage() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_PADDING, vertical = AevumSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
    ) {
        OnboardingPageHeader(
            eyebrow = oStr(R.string.ob_trust_eyebrow),
            title = oStr(R.string.ob_trust_title),
            subtitle = oStr(R.string.ob_trust_subtitle)
        )

        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            TRUST_ROWS.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AevumRadius.lg))
                        .background(colors.surfaceVariant.copy(alpha = 0.2f))
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(AevumRadius.lg))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(row.icon, color = colors.primary, fontSize = 15.sp)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = oStr(row.titleRes),
                            color = colors.onSurface,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = oStr(row.descriptionRes),
                            color = colors.onSurfaceVariant,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        Text(
            text = oStr(R.string.ob_trust_footnote),
            color = colors.onSurfaceVariant.copy(alpha = 0.65f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
