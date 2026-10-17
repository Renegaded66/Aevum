package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.PlayfulProgressBar
import com.d_drostes_apps.aevum.ui.components.shimmering
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1/M19.2: Editor — als „Sheet von unten" geöffnet (siehe AppNavHost).
 *
 * M19.2: Hier entstehen nur noch (Unter-)PROJEKTE; Aufgaben tippt man inline
 * in die To-do-Liste (das große Sammel-Textfeld aus M19.1 ist entfallen).
 * Der Editor bearbeitet außerdem bestehende Projekte und Aufgaben (Titel,
 * Notiz, Farbe, Frist, Tag, Erledigt).
 */
@Composable
fun PmEditorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PmEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // Nach dem Speichern zurück — genau einmal, ausgelöst vom Zustand.
    LaunchedEffect(state.form.saved) {
        if (state.form.saved) onDone()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(start = AevumSpacing.sm)) {
                        Text(
                            text = pmEditorTitle(state.mode, state.parentId != null),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (state.parentBreadcrumb.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.pm_parent_label) + ": " + state.parentBreadcrumb,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDone) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.pm_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AevumSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.mode == PmEditorMode.NODE_EDIT || state.mode == PmEditorMode.TASK_EDIT) {
                        OutlinedButton(
                            onClick = { viewModel.delete(onDone) },
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(
                                text = stringResource(R.string.pm_delete),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.width(AevumSpacing.sm))
                    }
                    Button(
                        onClick = viewModel::save,
                        shape = RoundedCornerShape(percent = 50),
                        enabled = !state.form.saving,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))
                            .shimmering(intensity = 0.20f)
                    ) {
                        Text(
                            text = stringResource(
                                if (state.mode == PmEditorMode.NODE_EDIT || state.mode == PmEditorMode.TASK_EDIT) {
                                    R.string.pm_save
                                } else {
                                    R.string.pm_create
                                }
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // Ziel-Elternknoten
            if (state.parentId != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AevumRadius.lg),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                ) {
                    Column(modifier = Modifier.padding(AevumSpacing.sm)) {
                        Text(
                            text = stringResource(R.string.pm_parent_label) + " · " + state.parentTitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        PlayfulProgressBar(
                            progress = state.parentProgress / 100f,
                            accent = MaterialTheme.colorScheme.primary,
                            height = 6.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }
                }
            }

            PmFieldLabel(stringResource(R.string.pm_field_title))
            OutlinedTextField(
                value = state.form.title,
                onValueChange = viewModel::setTitle,
                singleLine = true,
                isError = state.showError,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AevumRadius.lg)
            )
            if (state.showError) {
                Text(
                    text = stringResource(R.string.pm_title_required),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            PmFieldLabel(stringResource(R.string.pm_field_note))
            OutlinedTextField(
                value = state.form.note,
                onValueChange = viewModel::setNote,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 200.dp),
                shape = RoundedCornerShape(AevumRadius.lg)
            )

            PmFieldLabel(stringResource(R.string.pm_field_color))
            PmColorPicker(
                selected = state.form.colorHex,
                onPick = viewModel::setColor
            )

            if (state.mode.isTaskEdit) {
                PmFieldLabel(stringResource(R.string.pm_field_day))
                PmDateChips(
                    currentIso = state.form.scheduledDate,
                    onPick = viewModel::setScheduledDate
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AevumRadius.md))
                        .clickable { viewModel.toggleDone() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.form.done) PmPalette.complete
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.form.done) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color(0xFF2A1D00),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(AevumSpacing.sm))
                    Text(
                        text = stringResource(R.string.pm_done_label),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                PmFieldLabel(stringResource(R.string.pm_field_due))
                PmDateChips(
                    currentIso = state.form.dueDate,
                    onPick = viewModel::setDueDate,
                    allowNone = true
                )
            }

            Spacer(Modifier.height(AevumSpacing.xxl))
        }
    }
}

@Composable
private fun PmFieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Farbwahl: automatisch (aus dem Projekt) oder eine der acht Farben. */
@Composable
private fun PmColorPicker(selected: String?, onPick: (String?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .border(
                    width = if (selected == null) 2.dp else 1.dp,
                    color = if (selected == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .clickable { onPick(null) },
            contentAlignment = Alignment.Center
        ) {
            Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        com.d_drostes_apps.aevum.domain.projects.PmColors.PALETTE.forEachIndexed { index, hex ->
            val active = selected?.equals(hex, ignoreCase = true) == true
            Box(
                modifier = Modifier
                    .size(if (active) 30.dp else 26.dp)
                    .clip(CircleShape)
                    .background(PmPalette.color(hex))
                    .border(
                        width = if (active) 2.5.dp else 0.dp,
                        color = if (active) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { onPick(hex) }
            )
        }
    }
}

@Composable
private fun pmEditorTitle(mode: PmEditorMode, hasParent: Boolean): String = when (mode) {
    PmEditorMode.NODE_NEW ->
        if (hasParent) stringResource(R.string.pm_editor_new_subproject) else stringResource(R.string.pm_editor_new_project)
    PmEditorMode.NODE_EDIT -> stringResource(R.string.pm_editor_edit_node)
    PmEditorMode.TASK_EDIT -> stringResource(R.string.pm_editor_edit_task)
}
