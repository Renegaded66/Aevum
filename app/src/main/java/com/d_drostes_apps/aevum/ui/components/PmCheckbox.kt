package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1: Funkenstoß — der kleine Belohnungsmoment beim Abhaken.
 *
 * Bewusst KLEIN und kurz (~0,5 s): Die eigentliche Belohnung ist das Juwel, das
 * ins Glas fällt. Der Funke bestätigt nur unmittelbar, dass der Tipp angekommen
 * ist — er läuft genau einmal pro Übergang und nicht bei jeder Neukomposition
 * (siehe [PmCheckbox]).
 */
@Composable
fun SparkBurst(
    play: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    particles: Int = 10,
    distance: Dp = 30.dp,
    durationMillis: Int = 520
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(play) {
        if (play) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = durationMillis, easing = LinearEasing))
        } else {
            progress.snapTo(0f)
        }
    }
    val t = progress.value
    if (t <= 0f) return
    Canvas(modifier = modifier) {
        val max = distance.toPx()
        // Blitz in der Mitte
        drawCircle(
            color = color.copy(alpha = 0.35f * (1f - t)),
            radius = max * 0.5f * (0.4f + t)
        )
        for (i in 0 until particles) {
            val a = pseudoRandom(i * 7 + 3)
            val b = pseudoRandom(i * 13 + 11)
            val angle = (i.toFloat() / particles) * 6.28318f + a * 0.5f
            val speed = max * (0.55f + 0.65f * b)
            // leichte Erdanziehung: Funken fallen am Ende
            val dx = kotlin.math.cos(angle) * speed * t
            val dy = kotlin.math.sin(angle) * speed * t + 26f * t * t
            val alpha = (1f - t).coerceIn(0f, 1f) * (0.55f + 0.45f * b)
            val radius = max * 0.075f * (1f - t * 0.55f)
            drawLine(
                color = color.copy(alpha = alpha * 0.8f),
                start = Offset(center.x + dx * 0.35f, center.y + dy * 0.35f),
                end = Offset(center.x + dx, center.y + dy),
                strokeWidth = radius * 1.1f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = PmPalette.bright(color, 0.45f).copy(alpha = alpha),
                radius = radius,
                center = Offset(center.x + dx, center.y + dy)
            )
        }
    }
}

/** Deterministischer „Zufall" — gleiche Optik bei jeder Neukomposition. */
internal fun pseudoRandom(seed: Int): Float {
    val x = (seed * 1103515245 + 12345) and 0x7FFFFFFF
    return x / 2147483647f
}

/**
 * M19.1: Animiertes Häkchen mit Funkenstoß und haptischem Feedback.
 *
 * WARUM NICHT DIE MATERIAL-CHECKBOX: Der Nutzer fordert ausdrücklich eine
 * spielerische Oberfläche. Abhaken ist hier die HÄUFIGSTE Handlung des
 * Programms (bis zu 25-mal pro Tag) — sie muss sich belohnend anfühlen, nicht
 * behördlich. Deshalb: Kreis füllt sich mit der Farbe der Aufgabe, der Haken
 * schreibt sich, ein Funkenstoß fliegt, und das Handy gibt ein spürbares
 * Feedback. Kein Level, keine Punkte — nur ein schöner Moment.
 */
@Composable
fun PmCheckbox(
    checked: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    enabled: Boolean = true
) {
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium),
        label = "pm-check-fill"
    )
    val tick by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 260, delayMillis = if (checked) 120 else 0),
        label = "pm-check-tick"
    )

    // Funkenstoß genau einmal pro Übergang offen → erledigt (nicht bei jeder
    // Neukomposition — sonst würde die Liste bei jedem Scrollen funkeln).
    var lastChecked by remember { mutableStateOf(checked) }
    var burstKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(checked) {
        if (checked && !lastChecked) burstKey++
        lastChecked = checked
    }
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(size + 14.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            },
        contentAlignment = Alignment.Center
    ) {
        if (burstKey > 0) {
            key(burstKey) {
                SparkBurst(
                    play = true,
                    color = accent,
                    modifier = Modifier.size(size + 26.dp),
                    distance = size * 1.05f,
                    particles = 9
                )
            }
        }
        Canvas(modifier = Modifier.size(size)) {
            val r = size.toPx() / 2f
            val center = Offset(r, r)
            // Spur
            drawCircle(
                color = accent.copy(alpha = 0.32f),
                radius = r,
                center = center,
                style = Stroke(width = r * 0.17f)
            )
            if (fill > 0.01f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(PmPalette.bright(accent, 0.35f), accent, PmPalette.deep(accent, 0.15f)),
                        center = Offset(r * 0.72f, r * 0.72f),
                        radius = r * 1.5f
                    ),
                    radius = r * fill,
                    center = center
                )
                if (fill > 0.85f) {
                    drawTick(
                        center = center,
                        radius = r * 0.52f,
                        color = Color.White,
                        progress = tick
                    )
                }
            }
        }
    }
}

/**
 * Ring-Häkchen für erledigte Todos in dichten Listen (ohne Funken, ohne
 * Haptik) — zeigt Erledigung dauerhaft an, ohne die Aufmerksamkeit zu
 * stehlen.
 */
@Composable
fun PmDoneRing(
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val r = size.toPx() / 2f
        drawTickOutline(
            center = Offset(r, r),
            radius = r * 0.5f,
            color = accent,
            progress = 1f
        )
    }
}

/** Kleiner farbiger Punkt: Farbe der Aufgabe = Farbe des Juwels im Glas. */
@Composable
fun PmColorDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 9.dp
) {
    Canvas(modifier = modifier.size(size)) {
        drawCircle(color = PmPalette.deep(color, 0.2f).copy(alpha = 0.6f), radius = size.toPx() * 0.75f)
        drawCircle(color = color, radius = size.toPx() * 0.5f)
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = size.toPx() * 0.14f,
            center = Offset(size.toPx() * 0.38f, size.toPx() * 0.36f)
        )
    }
}
