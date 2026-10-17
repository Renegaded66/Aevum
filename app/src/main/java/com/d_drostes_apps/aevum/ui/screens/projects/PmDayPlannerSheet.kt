package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.projects.PmColors
import com.d_drostes_apps.aevum.ui.components.PmCheckbox
import com.d_drostes_apps.aevum.ui.components.PmColorDot
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1: „Tag planen" — das Morgenritual.
 *
 * ANWENDUNGSFALL (wörtlich): „Und dann will ich mir morgens denken, diese
 * bestimmten 20 todos will ich heute schaffen, die alle für heute fällig haben
 * und dann machen."
 *
 * WARUM DIESES SHEET ÜBERHAUPT EXISTIERT: Einzelne Todos nacheinander auf
 * „Heute" zu tippen wäre bei 20 Schritten 20-mal Tippen — genau die Reibung,
 * die den Nutzer laut eigener Aussage blockiert. Hier ist es EINE Bestätigung:
 * Schon geplante und übernommene Schritte sind vorausgewählt (der Nutzer muss
 * also oft nur bestätigen), Gruppen lassen sich mit einem Schalter komplett
 * übernehmen, und das Datum ist wählbar — heute ist nur der Standard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PmDayPlannerSheet(state: PmProjectsUiState, actions: PmActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selection = state.plannerSelection
    val targetDate = state.plannerDate ?: state.today.toString()

    ModalBottomSheet(
        onDismissRequest = actions.onClosePlanner,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md)
                .padding(bottom = AevumSpacing.md)
        ) {
            Text(
                text = stringResource(R.string.pm_planner_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.pm_planner_sub),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AevumSpacing.sm))

            PmDateChips(
                currentIso = targetDate,
                onPick = actions.onPlannerDate,
                allowNone = false,
                today = state.today
            )

            Text(
                text = stringResource(R.string.pm_planner_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AevumSpacing.xs)
            )
            Spacer(Modifier.height(AevumSpacing.sm))

            if (state.plannerGroups.isEmpty()) {
                Text(
                    text = stringResource(R.string.pm_planner_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AevumSpacing.md)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp),
                    contentPadding = PaddingValues(vertical = AevumSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    state.plannerGroups.forEach { group ->
                        val groupIds = group.tasks.map { it.id }
                        val accent = PmPalette.color(
                            group.group.node?.color ?: PmColors.colorFor(group.group.id)
                        )
                        val allSelected = groupIds.isNotEmpty() && groupIds.all { selection.contains(it) }
                        item(key = "planner-head-" + group.group.id) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (allSelected) accent
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        )
                                ) {
                                    if (allSelected) {
                                        Text(
                                            "✓",
                                            fontSize = 12.sp,
                                            color = androidx.compose.ui.graphics.Color.White,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(AevumSpacing.xs))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.group.title.ifEmpty { stringResource(R.string.pm_no_parent) },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (group.group.breadcrumb.isNotEmpty()) {
                                        Text(
                                            text = group.group.breadcrumb,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                PmColorDot(color = accent)
                                Spacer(Modifier.width(AevumSpacing.xs))
                                TextButton(onClick = {
                                    actions.onPlannerGroupSelection(groupIds, !allSelected)
                                }) {
                                    Text(stringResource(R.string.pm_planner_select_group_all), fontSize = 11.sp)
                                }
                            }
                        }
                        group.tasks.forEach { todo ->
                            val checked = selection.contains(todo.id)
                            item(key = "planner-" + todo.id) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(AevumRadius.md))
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PmCheckbox(
                                        checked = checked,
                                        accent = accent,
                                        onToggle = { actions.onTogglePlannerSelection(todo.id) },
                                        size = 24.dp
                                    )
                                    Spacer(Modifier.width(AevumSpacing.xs))
                                    Text(
                                        text = todo.title,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    pmShortDate(todo.scheduledDate)?.let { chip ->
                                        val chipColor by animateColorAsState(
                                            targetValue = MaterialTheme.colorScheme.onSurfaceVariant,
                                            animationSpec = tween(200),
                                            label = "planner-chip"
                                        )
                                        PmChip(text = chip, color = chipColor)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(AevumSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pm_selected_summary, selection.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = actions.onApplyPlanner,
                    shape = RoundedCornerShape(percent = 50)
                ) {
                    Text(stringResource(R.string.pm_planner_apply))
                }
            }
        }
    }
}
