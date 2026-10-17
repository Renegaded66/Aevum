package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.theme.AevumChartColors
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * M20: Farbwelten der Ziel-Architektur. Ein Ziel speichert nur den
 * SCHLÜSSEL (z. B. "gold") — die Farbe kommt aus dieser stabilen Liste
 * (Aevum-Chart-Palette), damit gespeicherte Ziele nie von Theme-Änderungen
 * abhängen und die Editor-Auswahl „dieselbe Farbe wie später" zeigt.
 */
object GoalAccents {
    val options: List<Pair<String, Color>> = listOf(
        "eis" to Color(0xFF38BDF8),
        "feuer" to Color(0xFFFF3B2E),
        "gold" to Color(0xFFFBBF24),
        "indigo" to Color(0xFF6366F1),
        "gruen" to Color(0xFF22C55E),
        "orange" to Color(0xFFF97316),
        "pink" to Color(0xFFEC4899),
        "violett" to Color(0xFFA855F7)
    )

    fun color(key: String?): Color? = options.firstOrNull { it.first == key }?.second

    /** Ziel-Akzent → Aktivitätsfarbe → Fallback (Theme). */
    fun resolve(key: String?, activityColor: Long?, fallback: Color): Color =
        color(key) ?: activityColor?.takeIf { it != 0L }?.let { Color(it) } ?: fallback
}

/** Kurzer Wochentagsname in der Gerätesprache (Mo, Di, … / Mo, Tu, …). */
fun weekdayShort(day: DayOfWeek): String =
    day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).trimEnd('.')

/**
 * Wochentags-Maske als Text: „Jeden Tag", „Mo–Fr", „Wochenende" oder
 * die einzelnen Kürzel (z. B. „Mo Mi Fr").
 */
@Composable
fun scheduleText(mask: Int): String {
    val m = mask and GoalProgressAnalytics.ALL_DAYS
    return when (m) {
        GoalProgressAnalytics.ALL_DAYS -> stringResource(R.string.goal_schedule_all)
        GoalEditorViewModel.WEEKDAYS -> stringResource(R.string.goal_schedule_weekdays)
        GoalEditorViewModel.WEEKEND -> stringResource(R.string.goal_schedule_weekend)
        else -> (1..7)
            .filter { m and (1 shl (it - 1)) != 0 }
            .joinToString(" ") { weekdayShort(DayOfWeek.of(it)) }
    }
}

// ── Pills / Badges ───────────────────────────────────────────────────────

/** Kleine Status-Kapsel („Heute fällig", „Frei", „Automatisch", …). */
@Composable
fun GoalPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AevumRadius.full),
        color = if (filled) color.copy(alpha = 0.18f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/** Serie: 🔥+Zahl — sichtbar ab Streak ≥ 1, „eingeäschert" bei 0. */
@Composable
fun GoalStreakBadge(streak: Int, modifier: Modifier = Modifier, showLabel: Boolean = false) {
    val active = streak > 0
    val color = if (active) Color(0xFFFF7A2F) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(if (active) "🔥" else "·", fontSize = 14.sp)
        Spacer(Modifier.width(3.dp))
        Text(
            text = if (showLabel) stringResource(R.string.goal_streak_value, streak) else "$streak",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

// ── Wochen-Pills (Editor + Detail) ───────────────────────────────────────

/**
 * Mo–So als tippbare Kapseln. [selectedMask] = Bitmaske (Mo=bit0 … So=bit6);
 * [onToggle] bekommt den ISO-Wochentag (1=Mo … 7=So); bei null ist die
 * Anzeige read-only (Detail-Ansicht).
 */
@Composable
fun GoalWeekdayPills(
    selectedMask: Int,
    modifier: Modifier = Modifier,
    onToggle: ((Int) -> Unit)? = null,
    compact: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    ) {
        (1..7).forEach { isoDay ->
            val selected = (selectedMask and (1 shl (isoDay - 1))) != 0
            val bg = if (selected) colors.primary.copy(alpha = 0.20f) else Color.Transparent
            val border = if (selected) colors.primary.copy(alpha = 0.75f) else colors.outlineVariant.copy(alpha = 0.5f)
            val fg = if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.75f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (compact) 26.dp else 38.dp)
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(AevumRadius.full))
                    .then(
                        if (onToggle != null) {
                            Modifier.clickable { onToggle(isoDay) }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = weekdayShort(DayOfWeek.of(isoDay)),
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg
                )
            }
        }
    }
}

// ── Verlaufs-Balken ──────────────────────────────────────────────────────

/**
 * Balken-Diagramm über [periods] (älteste zuerst) mit Ziellinie.
 * Farbe je Balken: erreicht = Akzent, verpasst = rot, frei = neutral.
 * [labels] parallel zu [periods] (z. B. Wochentagskürzel).
 */
@Composable
fun GoalHistoryBars(
    periods: List<GoalProgressAnalytics.PeriodStatus>,
    target: Float,
    accent: Color,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp
) {
    val colors = MaterialTheme.colorScheme
    if (periods.isEmpty()) return
    val maxValue = maxOf(target, periods.maxOfOrNull { it.value } ?: 0f).coerceAtLeast(0.0001f)
    val targetFraction = (target / maxValue).coerceIn(0.05f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(height)) {
            // Ziellinie
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(bottom = (height * targetFraction))
                    .height(1.dp)
                    .background(colors.onSurfaceVariant.copy(alpha = 0.35f))
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                periods.forEach { p ->
                    val fraction = (p.value / maxValue).coerceIn(0f, 1f)
                    val barColor = when {
                        p.met == true -> accent
                        p.met == false -> colors.error.copy(alpha = 0.65f)
                        else -> colors.outlineVariant.copy(alpha = 0.45f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((height * fraction).coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(barColor, barColor.copy(alpha = 0.55f))
                                )
                            )
                            .then(
                                if (p.isCurrent) {
                                    Modifier.border(1.dp, colors.onSurface.copy(alpha = 0.45f), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Heatmap (13 Wochen × 7 Tage) ─────────────────────────────────────────

/**
 * GitHub-artige Heatmap für TAGES-Perioden: 13 Wochen-Spalten × 7 Zeilen.
 * Intensität = Anteil am Zielwert, Farbe = erreicht (Akzent) / verpasst.
 */
@Composable
fun GoalHeatmap(
    periods: List<GoalProgressAnalytics.PeriodStatus>,
    target: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    cell: Dp = 12.dp
) {
    val colors = MaterialTheme.colorScheme
    if (periods.isEmpty()) return
    val byDate = periods.associateBy { it.startDate }
    val last = periods.last().startDate
    val firstMonday = periods.first().startDate
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val weeks = ArrayList<LocalDate>()
    var monday = firstMonday
    while (!monday.isAfter(last)) {
        weeks += monday
        monday = monday.plusWeeks(1)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            weeks.forEach { weekStart ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    (0..6).forEach { offset ->
                        val day = weekStart.plusDays(offset.toLong())
                        val p = byDate[day]
                        val cellColor = when {
                            p == null -> Color.Transparent
                            p.met == null -> colors.outlineVariant.copy(alpha = 0.22f)
                            else -> {
                                val intensity = (p.value / target.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                                val base = if (p.met == true) accent else colors.error
                                base.copy(alpha = 0.14f + 0.72f * intensity)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(cell)
                                .clip(RoundedCornerShape(3.dp))
                                .background(cellColor)
                                .then(
                                    if (p?.isCurrent == true) {
                                        Modifier.border(1.dp, colors.onSurface.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.goal_heatmap_less),
                fontSize = 10.sp,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            listOf(0.10f, 0.30f, 0.55f, 0.80f, 1.0f).forEach { a ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 1.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent.copy(alpha = 0.10f + 0.75f * a))
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.goal_heatmap_more),
                fontSize = 10.sp,
                color = colors.onSurfaceVariant
            )
        }
    }
}

// ── Check-Button (manuelles Abhaken) ─────────────────────────────────────

/**
 * Der „Dopamin-Knopf": Feder-Bounce beim Tippen, +1, Erfolgsfarbe.
 * [countToday] steuert die Rückgängig-Zeile (nur heute).
 */
@Composable
fun GoalCheckButton(
    countToday: Int,
    accent: Color,
    onCheck: () -> Unit,
    onUndo: (() -> Unit)?,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var tapCount by remember { mutableIntStateOf(0) }

    // Nach dem ersten Tipp zeigt der Knopf ein Häkchen statt „+".
    val justChecked = countToday > 0
    Column(modifier = modifier) {
        Button(
            onClick = {
                tapCount++
                scope.launch {
                    scale.animateTo(0.90f, tween(80))
                    scale.animateTo(1.05f, tween(120))
                    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                }
                onCheck()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .scale(scale.value),
            shape = RoundedCornerShape(AevumRadius.full),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = label ?: stringResource(R.string.goal_check_now),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (justChecked) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "· ${stringResource(R.string.goal_checked_times, countToday)}",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
        if (onUndo != null && countToday > 0) {
            TextButton(onClick = onUndo, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.goal_undo_checkin), fontSize = 13.sp)
            }
        }
    }
}

// ── Feier-Overlay (Konfetti + Karte) ─────────────────────────────────────

private class ConfettiParticle(
    val angle: Float,
    val speed: Float,
    val width: Float,
    val height: Float,
    val colorIdx: Int,
    val spin: Float,
    val delay: Float
)

/**
 * Vollbild-Feier: Konfetti-Burst + Karte mit Ziel-Titel und Serie.
 * Erscheint genau einmal je Ziel+Periode (Dedupe im ViewModel/Store),
 * schließt per Tipp oder automatisch nach ~3,4 s.
 */
@Composable
fun GoalCelebrationOverlay(
    event: GoalCelebrationEvent?,
    onDismiss: () -> Unit
) {
    if (event == null) return
    val accent = GoalAccents.color(event.accent) ?: MaterialTheme.colorScheme.tertiary

    val progress = remember(event.goalId) { Animatable(0f) }
    LaunchedEffect(event.goalId) {
        progress.animateTo(1f, tween(3300, easing = LinearEasing))
        onDismiss()
    }

    val particles = remember(event.goalId) {
        val random = Random(event.goalId.hashCode())
        List(120) {
            ConfettiParticle(
                angle = random.nextFloat() * 2f * PI.toFloat(),
                speed = 0.30f + random.nextFloat() * 0.85f,
                width = 6f + random.nextFloat() * 8f,
                height = 10f + random.nextFloat() * 12f,
                colorIdx = random.nextInt(0, 6),
                spin = (random.nextFloat() - 0.5f) * 3f,
                delay = random.nextFloat() * 0.30f
            )
        }
    }
    val palette = listOf(
        accent,
        Color(0xFFFBBF24),
        Color(0xFF22C55E),
        Color(0xFF38BDF8),
        Color(0xFFEC4899),
        Color(0xFFA855F7)
    )

    val backdrop by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(400),
        label = "celebration-backdrop"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f * backdrop))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progress.value
            particles.forEach { p ->
                val local = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
                if (local <= 0f) return@forEach
                val distance = p.speed * local * size.minDimension * 1.35f
                val gravity = 0.70f * local * local * size.minDimension
                val pos = Offset(
                    x = center.x + cos(p.angle) * distance,
                    y = center.y + sin(p.angle) * distance + gravity
                )
                val alpha = (1f - (local * local)).coerceIn(0f, 1f)
                if (alpha <= 0.02f) return@forEach
                val color = palette[p.colorIdx % palette.size].copy(alpha = alpha)
                rotate(degrees = p.spin * local * 360f, pivot = pos) {
                    drawRect(
                        color = color,
                        topLeft = Offset(pos.x - p.width / 2f, pos.y - p.height / 2f),
                        size = Size(p.width, p.height)
                    )
                }
            }
        }

        // Zentrale Karte
        val pop = remember(event.goalId) { Animatable(0.6f) }
        LaunchedEffect(event.goalId) {
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        Surface(
            modifier = Modifier
                .padding(AevumSpacing.xl)
                .scale(pop.value),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = AevumSpacing.xl, vertical = AevumSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.10f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = accent
                    )
                }
                Text(
                    text = stringResource(R.string.goal_celebration_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = event.goalTitle,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (event.streak > 0) {
                    Surface(
                        shape = RoundedCornerShape(AevumRadius.full),
                        color = Color(0xFFFF7A2F).copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = stringResource(R.string.goal_celebration_streak, event.streak),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF7A2F)
                        )
                    }
                }
                Text(
                    text = event.periodLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/** Farbwahl für den Editor — [selected] null = „Automatisch". */
@Composable
fun GoalAccentPicker(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // „Automatisch" = keine Auswahl
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(
                    2.dp,
                    if (selected == null) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                    CircleShape
                )
                .clickable { onSelect(null) },
            contentAlignment = Alignment.Center
        ) {
            Text("∅", fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        GoalAccents.options.forEach { (key, color) ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.35f))
                    .border(
                        2.dp,
                        if (selected == key) colors.onSurface else Color.Transparent,
                        CircleShape
                    )
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center
            ) {
                if (selected == key) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}
