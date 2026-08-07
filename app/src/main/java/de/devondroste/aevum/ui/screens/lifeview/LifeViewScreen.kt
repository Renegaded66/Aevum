package de.devondroste.aevum.ui.screens.lifeview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * M18.35: LifeView — die Lebenszeit-Ansicht.
 *
 * "Dem Nutzer Angst machen" — aber mit Zahlen und Grafiken, nicht mit
 * Worten. Die zentrale Visualisierung ist der LIFE CALENDAR: jeder
 * Monat des Lebens ist eine Zelle. Verbrauchte Monate leuchten in
 * einem Farbverlauf, verbleibende sind dunkel. Darunter die
 * Hochrechnungen: Schlaf, Autofahren, Pauschalen — als Jahre, die
 * vom verbleibenden Leben "aufgefressen" werden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeViewScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: LifeViewViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showBirthdayPicker by remember { mutableStateOf(false) }
    var showAgeDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        item {
            LifeHero(
                state = state,
                onBack = onBack,
                onEditBirthday = { showBirthdayPicker = true },
                onEditAge = { showAgeDialog = true }
            )
        }

        if (!state.hasBirthday) {
            item { BirthdayPrompt(onClick = { showBirthdayPicker = true }) }
        } else {
            item { LifeCalendarGrid(state = state) }
            item { TimeBreakdownCard(state = state) }
            item { ActivityDetailsCard(state = state) }
            item { WakeUpCallCard(state = state) }
        }
        item { Spacer(Modifier.height(AevumSpacing.xxl)) }
    }

    if (showBirthdayPicker) {
        BirthdayPickerDialog(
            initial = state.birthday,
            onConfirm = { date ->
                viewModel.saveBirthday(date)
                showBirthdayPicker = false
            },
            onDismiss = { showBirthdayPicker = false }
        )
    }

    if (showAgeDialog) {
        AgeDialog(
            initial = state.expectedAge,
            onConfirm = { age ->
                viewModel.saveExpectedAge(age)
                showAgeDialog = false
            },
            onDismiss = { showAgeDialog = false }
        )
    }
}

@Composable
private fun LifeHero(
    state: LifeViewUiState,
    onBack: () -> Unit,
    onEditBirthday: () -> Unit,
    onEditAge: () -> Unit
) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LEBENSZEIT", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Was bleibt dir wirklich?", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onBack) { Text("Zurück") }
            }

            if (state.hasBirthday) {
                // Countdown — die nackte Zahl
                Text(
                    "${state.remainingYears} Jahre, ${state.remainingDays % 365} Tage",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    "bis du ${state.expectedAge} wirst (Alter: ${state.age})",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    OutlinedButton(onClick = onEditBirthday) { Text("Geburtstag", fontSize = 12.sp) }
                    OutlinedButton(onClick = onEditAge) { Text("Alter ${state.expectedAge}", fontSize = 12.sp) }
                }
            } else {
                Text(
                    "Gib deinen Geburtstag an, um zu sehen, wie viel Zeit dir wirklich bleibt.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BirthdayPrompt(onClick: () -> Unit) {
    AevumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Text("🎂", fontSize = 40.sp)
            Text("Geburtstag eingeben", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Nur das Jahr zählt für die Berechnung — dein Geburtstag bleibt lokal auf dem Gerät.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onClick) { Text("Geburtstag festlegen") }
        }
    }
}

/**
 * Der Life Calendar: 1 Zelle = 1 Monat. Verbrauchte Monate leuchten
 * in einem Verlauf von Grün (Kindheit) über Gelb zu Rot (jetzt).
 * Verbleibende Monate sind dunkel. Das ist die "umhauende" Grafik.
 */
@Composable
private fun LifeCalendarGrid(state: LifeViewUiState) {
    AevumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Dein Leben in Monaten", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${state.livedMonths} von ${state.totalMonths} Monaten verbraucht",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "${(state.livedMonths.toFloat() / state.totalMonths * 100).roundToInt()}%",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Grid: 24 Spalten (2 Jahre pro Zeile) — kompakt und lesbar
            val cols = 24
            val rows = (state.totalMonths + cols - 1) / cols
            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (col in 0 until cols) {
                        val monthIndex = row * cols + col
                        if (monthIndex >= state.totalMonths) {
                            Spacer(Modifier.weight(1f).aspectRatio(1f))
                        } else {
                            val lived = monthIndex < state.livedMonths
                            val cellColor = if (lived) {
                                // Verlauf: jung = grün, alt = rot
                                val t = monthIndex.toFloat() / state.totalMonths
                                Color(
                                    red = 0.13f + 0.75f * t,
                                    green = 0.72f - 0.5f * t,
                                    blue = 0.13f + 0.1f * t
                                )
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cellColor)
                            )
                        }
                    }
                }
            }
            // Legende
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Geburt", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF13B84A)))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFF5C518)))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE53935)))
                Spacer(Modifier.width(6.dp))
                Text("Heute", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)))
                Spacer(Modifier.width(4.dp))
                Text("Verbleibend", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Der gestapelte Balken: Wie viel vom verbleibenden Leben wird
 * "aufgefressen" — Schlaf, Autofahren, Pauschalen, Erfasst, Frei.
 */
@Composable
private fun TimeBreakdownCard(state: LifeViewUiState) {
    AevumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Text("Wofür dein Leben draufgeht", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Hochgerechnet auf die verbleibenden ${state.remainingYears} Jahre (aus den letzten 14 Tagen).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Gestapelter Balken
            val total = state.breakdown.sumOf { it.years }.coerceAtLeast(0.1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                state.breakdown.forEach { slice ->
                    val fraction = (slice.years / total).toFloat().coerceIn(0f, 1f)
                    if (fraction > 0.005f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(Color(slice.color.toInt()))
                        )
                    }
                }
            }

            // Legende mit Jahren
            state.breakdown.forEach { slice ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Text(slice.icon, fontSize = 16.sp)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(slice.color.toInt()))
                    )
                    Text(slice.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        formatYears(slice.years),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(slice.color.toInt())
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityDetailsCard(state: LifeViewUiState) {
    if (state.activityDetails.isEmpty()) return
    AevumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Text("Deine Aktivitäten im Lebensvergleich", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            state.activityDetails.forEach { detail ->
                val color = Color(detail.color.toInt())
                val maxYears = state.activityDetails.first().years.coerceAtLeast(0.1)
                val fraction = (detail.years / maxYears).toFloat().coerceIn(0f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(800),
                    label = "bar"
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(detail.icon, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(detail.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(
                            "${formatYears(detail.years)} · ${detail.minutesPerDay} min/Tag",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(color.copy(alpha = 0.8f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WakeUpCallCard(state: LifeViewUiState) {
    val sleepPercent = (state.sleepYears / state.remainingYears.coerceAtLeast(1) * 100).roundToInt()
    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text("⏳", fontSize = 32.sp)
            Text(
                "Du wirst etwa ${formatYears(state.sleepYears)} deines restlichen Lebens schlafen.",
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Das sind $sleepPercent% der Zeit, die dir noch bleibt. Autofahren kostet dich zusätzlich ${formatYears(state.drivingYears)}.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPickerDialog(
    initial: LocalDate?,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val initialMillis = initial?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        onConfirm(date)
                    }
                }
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    ) {
        DatePicker(state = datePickerState)
    }
}

@Composable
private fun AgeDialog(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var age by remember { mutableStateOf(initial.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Erwartetes Alter") },
        text = {
            Column {
                Text("$age Jahre", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Slider(
                    value = age,
                    onValueChange = { age = it },
                    valueRange = 60f..100f,
                    steps = 39
                )
                Text(
                    "Standard: 80. Die Hochrechnungen nutzen diesen Wert.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(age.roundToInt()) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

private fun formatYears(years: Double): String {
    return if (years >= 1) {
        String.format(Locale.GERMANY, "%.1f Jahre", years)
    } else {
        val months = (years * 12).roundToInt()
        if (months >= 1) "$months Monate" else "< 1 Monat"
    }
}
