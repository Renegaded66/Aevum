package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * M18.93v4 BUBBLE-STREAM: Endloser Strom von "Energie-Blubberblasen", die
 * von links nach rechts fliegen (von der Aktivität in den Zeit-Tank).
 * Jede Blase hat deterministische Parameter (Spur, Phase, Größe, Tempo) —
 * der Strom wirkt organisch, ist aber komplett reproduzierbar (kein RNG
 * in der Composition). Kein Fortschritt, kein Ende: RepeatMode.Restart
 * + Phasen-Offsets = nie leeres Bild, nie Sprünge.
 *
 * Bewusst OHNE externe Library (Recherche 2026-09: Floating-Bubble-View =
 * System-Overlays, Quarks = Explosionen — beide falsches Werkzeug für
 * einen In-Layout-Strom; eigenes Canvas ist 40 Zeilen, 0 Abhängigkeiten,
 * und rendert ohne Recomposition, nur Canvas-Redraw).
 *
 * [animate]=false (Pause): Blasen "stehen" gedimmt — friert mit dem Timer.
 */
@Composable
fun BubbleStream(
    color: Color,
    modifier: Modifier = Modifier,
    bubbleCount: Int = 6,
    animate: Boolean = true,
    periodMs: Int = 2400
) {
    val transition = rememberInfiniteTransition(label = "bubbleStream")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = periodMs, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "bubbleT"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val r = h / 2f

        // Quelle (links) & Senke (rechts): weiche Glows — Energie tritt aus,
        // Energie tritt ein.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.28f), Color.Transparent)
            ),
            radius = r * 1.6f,
            center = Offset(0f, h / 2f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.34f), Color.Transparent)
            ),
            radius = r * 1.9f,
            center = Offset(w, h / 2f)
        )

        repeat(bubbleCount) { i ->
            // Deterministische Parameter pro Blase (golden-ratio-Streuung).
            val speed = 0.85f + ((i * 2654435761L) and 0xFFFFL) / 65535f * 0.5f
            val lane = 0.22f + ((i * 40503L) and 0xFFL) / 255f * 0.56f
            val phase = ((i * 97L) and 0xFFL) / 255f
            val radiusPx = 2.2.dp.toPx() + ((i * 31L) and 0x7L) * 0.9f

            val progress = if (animate) (t * speed + phase) % 1f else phase * 0.55f + 0.2f
            val x = w * progress
            val y = h * lane + (sin(progress * 2.0 * PI + i * 1.7)).toFloat() * h * 0.16f
            val alpha = if (animate) (sin(progress * PI)).toFloat() * 0.85f else 0.28f

            // Blase: weicher Kern + Glanzpunkt oben links.
            drawCircle(
                color = color.copy(alpha = alpha * 0.9f),
                radius = radiusPx,
                center = Offset(x, y)
            )
            drawCircle(
                color = Color.White.copy(alpha = alpha * 0.45f),
                radius = radiusPx * 0.34f,
                center = Offset(x - radiusPx * 0.32f, y - radiusPx * 0.32f)
            )
        }
    }
}