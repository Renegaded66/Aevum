package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.PmPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * M19.1: Datumswahl in Chips — „Heute / Morgen / Kein Datum / Datum …".
 *
 * ANWENDUNGSFALL: „die einzelnen todos will ich einen Tag zuweisen können.
 * Möglichst nutzerfreundlich, damit man nicht alle 25 todos einzeln einen Tag
 * zuweisen muss."
 *
 * Der schnellste Weg ist immer der sichtbare: „Heute" und „Morgen" sind EIN
 * Tipp, das freie Datum zwei. Und weil „morgen" bei einem Vortrag selten reicht,
 * gibt es das Datum ohne Umweg über einen Dialog — aber auch nicht als
 * Pflichtfeld („muss nicht zwingend mit Datum versehen sein").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PmDateChips(
    currentIso: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = true,
    today: LocalDate = LocalDate.now()
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val todayIso = today.toString()
    val tomorrowIso = today.plusDays(1).toString()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
    ) {
        if (allowNone) {
            PmDateChip(
                label = stringResource(R.string.pm_date_none),
                selected = currentIso == null,
                onClick = { onPick(null) }
            )
        }
        PmDateChip(
            label = stringResource(R.string.pm_today),
            selected = currentIso == todayIso,
            onClick = { onPick(todayIso) }
        )
        PmDateChip(
            label = stringResource(R.string.pm_tomorrow),
            selected = currentIso == tomorrowIso,
            onClick = { onPick(tomorrowIso) }
        )
        PmDateChip(
            label = pmShortDate(currentIso ?: "")?.let { "📅 $it" } ?: stringResource(R.string.pm_pick_date),
            selected = currentIso != null && currentIso != todayIso && currentIso != tomorrowIso,
            onClick = { pickerOpen = true }
        )
    }

    if (pickerOpen) {
        val initial = try {
            currentIso?.let { LocalDate.parse(it) } ?: today
        } catch (e: Exception) {
            today
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    pickerOpen = false
                    if (millis != null) {
                        val iso = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        onPick(iso)
                    }
                }) {
                    Text(stringResource(R.string.pm_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(stringResource(R.string.pm_cancel))
                }
            }
        ) {
            DatePicker(state = state, title = null, headline = null)
        }
    }
}

@Composable
private fun PmDateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PmPalette.soft(MaterialTheme.colorScheme.primary, 0.30f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.padding(end = 0.dp)
    )
}
