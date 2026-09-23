package com.d_drostes_apps.aevum.ui.screens.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.137 (Kanban t_70a06809): Ausklappbare "Top Aktivitäten"-Karte.
 *
 * Der Nutzer wünschte sich, die Top-Aktivitäten aufklappen zu können, um
 * wirklich ALLE Aktivitäten der Periode zu sehen — nicht nur die ersten 5 —
 * und das Aufklappen sollte "hochmodern, bestenfalls kein Text, nur ein Icon"
 * sein.
 *
 * Umsetzung:
 * - Zugeklappt: die ersten [TOP_ACTIVITIES_COLLAPSED_COUNT] Aktivitäten.
 * - Aufgeklappt: alle Aktivitäten der Periode, weiterhin absteigend sortiert
 *   (die Sortierung liefert [InsightsAnalytics.build] — die UI kürzt nur).
 * - Der Umschalter ist ein reiner Icon-Button (Chevron), der beim Aufklappen
 *   um 180° dreht. Er trägt KEINEN sichtbaren Text.
 * - Die zusätzlichen Zeilen wachsen per [expandVertically] auf bzw. schrumpfen
 *   per [shrinkVertically] — inklusive Ein-/Ausblendung, damit der Übergang
 *   weich ist statt zu springen.
 *
 * Accessibility (Compose-Gegenstück zu aria-expanded/aria-controls):
 * - [contentDescription] benennt die AKTION ("Alle Aktivitäten anzeigen").
 * - [stateDescription] + [toggleableState] melden den ZUSTAND ("ausgeklappt"
 *   bzw. "zugeklappt") — das ist das, was TalkBack als Toggle-Zustand ansagt.
 * - [Role.Button] + `onClickLabel` machen den Button als solchen erkennbar.
 * Es gibt bewusst KEINEN sichtbaren Text; ein Screenreader-Label ist keine
 * sichtbare Beschriftung, sondern die Voraussetzung dafür, dass ein reiner
 * Icon-Button überhaupt bedienbar ist.
 */

/** Zugeklappt sichtbare Zeilen. */
internal const val TOP_ACTIVITIES_COLLAPSED_COUNT = 5

/** Test-Tag des Toggle-Buttons (für Compose-UI-Tests). */
internal const val TOP_ACTIVITIES_TOGGLE_TEST_TAG = "topActivitiesToggle"

/** Test-Tag einer einzelnen Zeile — trägt die Anzahl sichtbarer Zeilen. */
internal const val TOP_ACTIVITIES_ROW_TEST_TAG_PREFIX = "topActivitiesRow_"

/**
 * Anzahl der tatsächlich gerenderten Zeilen.
 *
 * Die Kategorie-Ansicht bleibt bewusst UNGEKÜRZT (M18.66-FIX17: sonst fehlt
 * z.B. "Transport", wenn fünf größere Kategorien davor liegen) — dort gibt es
 * nichts auszuklappen und deshalb auch keinen Toggle.
 */
internal fun topActivitiesVisibleCount(
    total: Int,
    mode: BreakdownMode,
    expanded: Boolean
): Int = when {
    total <= 0 -> 0
    mode == BreakdownMode.Category -> total
    expanded -> total
    else -> minOf(total, TOP_ACTIVITIES_COLLAPSED_COUNT)
}

/** Gibt es überhaupt Zeilen, die zugeklappt verborgen wären? Nur dann Toggle. */
internal fun topActivitiesToggleAvailable(total: Int, mode: BreakdownMode): Boolean =
    mode == BreakdownMode.Activity && total > TOP_ACTIVITIES_COLLAPSED_COUNT

@Composable
fun TopActivitiesCard(
    mode: BreakdownMode,
    items: List<TopActivitySlice>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    if (items.isEmpty()) return
    val head = items.take(topActivitiesVisibleCount(items.size, mode, expanded = false))
    val tail = items.drop(head.size)
    val showToggle = topActivitiesToggleAvailable(items.size, mode)
    // Referenz-Balkenlänge über ALLE Zeilen — sonst würden die Balken beim
    // Ausklappen neu skalieren und der Übergang wirkte unruhig.
    val maxMs = items.maxOf { it.durationMs }.coerceAtLeast(1L)

    GlassCard(accentColor = items.first().color) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (mode) {
                        BreakdownMode.Activity -> stringResource(R.string.insights_top_activities)
                        // M18.66-FIX17: keine Top-Begrenzung mehr —
                        // ALLE Kategorien werden angezeigt.
                        BreakdownMode.Category -> stringResource(R.string.common_categories)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (showToggle) {
                    ExpandToggleIconButton(expanded = expanded, onToggle = onToggleExpanded)
                }
            }
            Spacer(Modifier.height(AevumSpacing.md))
            head.forEachIndexed { index, slice ->
                TopSliceRow(
                    slice = slice,
                    maxMs = maxMs,
                    index = index,
                    testTag = topActivitiesRowTestTag(index)
                )
                Spacer(Modifier.height(AevumSpacing.sm))
            }
            AnimatedVisibility(
                visible = expanded && tail.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(durationMillis = 120))
            ) {
                Column {
                    tail.forEachIndexed { index, slice ->
                        val absoluteIndex = head.size + index
                        TopSliceRow(
                            slice = slice,
                            maxMs = maxMs,
                            // Kaskade läuft über die GESAMTE Liste weiter,
                            // damit die neuen Zeilen versetzt einlaufen.
                            index = absoluteIndex,
                            testTag = topActivitiesRowTestTag(absoluteIndex)
                        )
                        if (index < tail.lastIndex) {
                            Spacer(Modifier.height(AevumSpacing.sm))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reiner Icon-Umschalter: Chevron in einem Glas-Kreis, der beim Aufklappen
 * um 180° dreht. Kein sichtbarer Text — nur Semantik für Screenreader.
 */
@Composable
private fun ExpandToggleIconButton(expanded: Boolean, onToggle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "topActivitiesToggleRotation"
    )
    val actionLabel = stringResource(
        if (expanded) R.string.insights_top_activities_collapse
        else R.string.insights_top_activities_expand
    )
    val stateLabel = stringResource(
        if (expanded) R.string.insights_top_activities_state_expanded
        else R.string.insights_top_activities_state_collapsed
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .border(width = 1.dp, color = accent.copy(alpha = 0.35f), shape = CircleShape)
            .clickable(
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onToggle
            )
            .semantics {
                contentDescription = actionLabel
                stateDescription = stateLabel
                toggleableState = if (expanded) ToggleableState.On else ToggleableState.Off
            }
            .testTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation)
        )
    }
}

/** Nur für Tests/Debug: Test-Tag einer Listenzeile mit ihrem Index. */
internal fun topActivitiesRowTestTag(index: Int): String =
    "$TOP_ACTIVITIES_ROW_TEST_TAG_PREFIX$index"
