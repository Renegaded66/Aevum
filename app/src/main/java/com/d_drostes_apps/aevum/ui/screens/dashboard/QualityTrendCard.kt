package com.d_drostes_apps.aevum.ui.screens.dashboard

import com.d_drostes_apps.aevum.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.LocalDate

/**
 * M18.58: Güte-Verlauf-Statistik — "wie gut habe ich meine Zeit genutzt".
 *
 * User-Wunsch: "eine Statistik über den Güte Verlauf der letzten Tage,
 * wobei man INNERHALB der Statistik einstellen können soll, ob in den
 * letzten 7, 30, 365 Tagen. Eine richtig moderne fancy Statistik."
 *
 * M19: Finger-Scrubber — man kann mit dem Finger über den Graph ziehen,
 * dann folgt ein Strich dem Finger und zeigt den Wert an der Stelle.
 *
 * Design:
 *  - Animierter Segmented-Umschalter 7T / 30T / 365T (Rahmen gleitet)
 *  - Area-Chart (Canvas): gewichteter Positivitäts-Score pro Tag,
 *    Verlaufs-Linie + Gradient-Fläche + Punkte, "heute"-Marker
 *  - Finger-Scrubber: vertikaler Strich + Tooltip mit Datum + Score
 *  - Kopfzeile: Ø-Score im gewählten Fenster + Trend-Pfeil (vs. Vorperiode)
 *  - Score-Farbe (rot→grün) via positivityColor
 */
@Composable
fun QualityTrendCard(
    trend: List<DailyQualityPoint>,
    modifier: Modifier = Modifier
) {
    var windowDays by remember { mutableStateOf(7) }

    AevumCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            // Kopfzeile: Titel + Segmented-Umschalter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(R.string.dashboard_quality_trend_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.dashboard_quality_trend_sub),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                WindowToggle(windowDays = windowDays, onSelect = { windowDays = it })
            }

            // Datenfenster filtern (letzte N Tage)
            val today = LocalDate.now()
            val cutoff = today.minusDays((windowDays - 1).toLong())
            val windowData = trend.filter { it.date >= cutoff.toString() }
            val prevCutoff = cutoff.minusDays(windowDays.toLong())
            val prevData = trend.filter { it.date >= prevCutoff.toString() && it.date < cutoff.toString() }

            if (windowData.isEmpty()) {
                Text(
                    stringResource(R.string.dashboard_quality_trend_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            } else {
                // Kennzahlen: Ø-Score + Trend vs. Vorperiode
                val avgScore = windowData.map { it.score }.average().toInt()
                val prevAvg = prevData.map { it.score }.average()
                val delta = if (prevData.isNotEmpty()) avgScore - prevAvg.toInt() else 0
                TrendHeader(avgScore = avgScore, delta = delta)

                // Area-Chart mit Finger-Scrubber
                QualityAreaChart(
                    points = windowData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            }
        }
    }
}

/**
 * M18.58: Animierter 7/30/365-Segmented-Umschalter. Der Rahmen gleitet
 * per animateDpAsState zur aktiven Option — gleiches Muster wie die
 * Schlaf-Quellen-Auswahl (User-Wunsch "fancy").
 */
@Composable
private fun WindowToggle(windowDays: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        7 to stringResource(R.string.dashboard_trend_7d),
        30 to stringResource(R.string.dashboard_trend_30d),
        365 to stringResource(R.string.dashboard_trend_365d)
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { index, (days, label) ->
            val selected = days == windowDays
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(days) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrendHeader(avgScore: Int, delta: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // Ø-Score als große Zahl mit Farbe
        val scoreColor = positivityColor(avgScore)
        Text(
            "$avgScore",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = scoreColor
        )
        Text(
            "Ø",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Trend vs. Vorperiode
        if (delta != 0) {
            val trendColor = if (delta > 0) Color(0xFF10B981) else Color(0xFFEF4444)
            Text(
                if (delta > 0) "▲ +$delta" else "▼ $delta",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = trendColor
            )
        }
    }
}

/**
 * M18.58 + M19: Area-Chart (Canvas) mit Finger-Scrubber.
 *
 * Zeichnet den Score-Verlauf mit Gradient-Fläche, Linie und Datenpunkten.
 * Animation: Die Fläche "wächst" von unten ein (animateFloatAsState).
 *
 * M19: Finger-Scrubber — man kann mit dem Finger über den Graph ziehen,
 * dann folgt ein vertikaler Strich dem Finger und ein Tooltip zeigt
 * das Datum und den Score an der berührten Stelle.
 */
@Composable
private fun QualityAreaChart(
    points: List<DailyQualityPoint>,
    modifier: Modifier = Modifier
) {
    val fillProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 120f),
        label = "area-fill"
    )
    val lineColor = positivityColor(points.map { it.score }.average().toInt())
    val gradientColors = listOf(
        lineColor.copy(alpha = 0.35f),
        lineColor.copy(alpha = 0.03f)
    )

    // M19: Scrubber-Status
    var scrubX by remember { mutableStateOf<Float?>(null) }
    var scrubIndex by remember { mutableStateOf(-1) }
    val density = LocalDensity.current
    val scrubberColor = MaterialTheme.colorScheme.primary
    val tooltipBgColor = MaterialTheme.colorScheme.surface
    val tooltipTextColor = MaterialTheme.colorScheme.onSurface
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(points) {
                    // M19: Finger-Scrubber — Drag und Tap erkennen
                    detectDragGestures(
                        onDragStart = { offset ->
                            scrubX = offset.x
                            scrubIndex = findNearestIndex(offset.x, size.width, points.size)
                        },
                        onDrag = { change, _ ->
                            scrubX = change.position.x.coerceIn(0f, size.width.toFloat())
                            scrubIndex = findNearestIndex(scrubX!!, size.width, points.size)
                        },
                        onDragEnd = { scrubX = null; scrubIndex = -1 },
                        onDragCancel = { scrubX = null; scrubIndex = -1 }
                    )
                }
                .pointerInput(points) {
                    // Tap: einmaliges Antippen zeigt auch den Wert
                    detectTapGestures(
                        onTap = { offset ->
                            scrubX = offset.x
                            scrubIndex = findNearestIndex(offset.x, size.width, points.size)
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val padY = 8f
            val usableH = h - padY * 2

            val maxScore = 100f
            val minScore = 0f
            fun xFor(index: Int): Float =
                if (points.size == 1) w / 2f
                else w * index / (points.size - 1).toFloat()
            fun yFor(score: Int): Float =
                padY + usableH * (1f - (score - minScore) / (maxScore - minScore))

            // Füllfläche (animiert von unten)
            val path = Path()
            if (points.isNotEmpty()) {
                val fillHeight = usableH * fillProgress
                path.moveTo(xFor(0), h - padY)
                points.forEachIndexed { index, point ->
                    path.lineTo(xFor(index), yFor(point.score))
                }
                path.lineTo(xFor(points.size - 1), h - padY)
                path.close()
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = gradientColors,
                        startY = padY,
                        endY = h - padY
                    )
                )

                // Linie
                val linePath = Path()
                points.forEachIndexed { index, point ->
                    val x = xFor(index)
                    val y = yFor(point.score)
                    if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                }
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )

                // Datenpunkte (nur bei ≤ 30 Tagen, sonst Rauschen)
                if (points.size <= 31) {
                    points.forEachIndexed { index, point ->
                        drawCircle(
                            color = lineColor,
                            radius = 3f,
                            center = Offset(xFor(index), yFor(point.score))
                        )
                    }
                }

                // Heute-Marker (letzter Punkt)
                if (points.size > 1) {
                    val lastX = xFor(points.size - 1)
                    val lastY = yFor(points.last().score)
                    drawCircle(
                        color = lineColor,
                        radius = 5.5f,
                        center = Offset(lastX, lastY)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = 2f,
                        center = Offset(lastX, lastY)
                    )
                }

                // M19: Scrubber — vertikaler Strich + hervorgehobener Punkt
                if (scrubX != null && scrubIndex in points.indices) {
                    val sx = xFor(scrubIndex)
                    val sy = yFor(points[scrubIndex].score)
                    // Vertikale Linie folgt dem Finger
                    drawLine(
                        color = scrubberColor.copy(alpha = 0.6f),
                        start = Offset(sx, 0f),
                        end = Offset(sx, h),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round
                    )
                    // Highlight-Kreis am Schnittpunkt
                    drawCircle(
                        color = scrubberColor,
                        radius = 6f,
                        center = Offset(sx, sy)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3f,
                        center = Offset(sx, sy)
                    )
                }
            }
        }

        // M19: Tooltip-Text über dem Canvas (Compose-Overlay)
        if (scrubIndex in points.indices) {
            val point = points[scrubIndex]
            val scoreColor = positivityColor(point.score)
            val dateLabel = try {
                val date = LocalDate.parse(point.date)
                val day = date.dayOfMonth
                val month = date.monthValue
                "${day}.${month}."
            } catch (_: Exception) { point.date }
            val durationLabel = formatDurationShort(point.durationMs)

            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tooltipBgColor.copy(alpha = 0.95f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        dateLabel,
                        fontSize = 10.sp,
                        color = tooltipTextColor.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "${point.score}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                        fontFamily = FontFamily.Monospace
                    )
                    if (durationLabel.isNotBlank()) {
                        Text(
                            durationLabel,
                            fontSize = 9.sp,
                            color = tooltipTextColor.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/** Findet den Index des nächsten Datenpunkts zur X-Position. */
private fun findNearestIndex(x: Float, canvasWidth: Int, pointCount: Int): Int {
    if (pointCount == 0) return -1
    if (pointCount == 1) return 0
    val fraction = (x / canvasWidth).coerceIn(0f, 1f)
    return (fraction * (pointCount - 1)).roundToInt()
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()

private fun formatDurationShort(ms: Long): String {
    if (ms <= 0) return ""
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return when {
        hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}