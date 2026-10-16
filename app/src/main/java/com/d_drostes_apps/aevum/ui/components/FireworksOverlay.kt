package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1: Feuerwerk — die kurze Feier, wenn das Glas voll ist.
 *
 * ANWENDUNGSFALL: „wenn man alle abgehakt hat ist das Glas mit Juwelen voll und
 * kommt eine kurze Feuerwerk Animation."
 *
 * WARUM ES EIN EIGENES OVERLAY IST: Es liegt ÜBER dem Inhalt (eigene Ebene),
 * läuft genau einmal und blockiert nichts — kein Zappeln der Listen, keine
 * Neuberechnung der Fortschritte. Der Auslöser ist ein Zustand, kein
 * Compose-Zufall ([com.d_drostes_apps.aevum.domain.projects.PmCelebrationGate]
 * verhindert Doppelfeuer bei jeder Neukomposition).
 *
 * ANIMATION: vier Abschüsse (versetzt gezündet) mit je 34 Partikeln, die mit
 * Schwerkraft fallen, eine kurze Spur ziehen und ausblenden. Dazu ein Banner,
 * das aufpoppt und dessen Glanz einmal durchläuft. Nach [durationMillis] meldet
 * sich das Overlay über [onFinished] — der Aufrufer setzt dann seinen Zustand
 * zurück (sonst bliebe der Canvas dauerhaft in der Hierarchie).
 */
@Composable
fun FireworksOverlay(
    play: Boolean,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
    durationMillis: Int = 3600
) {
    if (!play) return
    val progress = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(play) {
        visible = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = durationMillis, easing = LinearEasing))
        visible = false
        onFinished()
    }
    if (!visible) return

    val t = progress.value

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val bursts = BURSTS
            // Wichtig: kein Vollbild-Blitz bei t = 0 (sonst wirkt es wie ein Fehler).
            val dim = (0.32f * (1f - t)).coerceAtLeast(0f)
            drawRect(color = Color.Black.copy(alpha = dim))
            for ((burstIndex, burst) in bursts.withIndex()) {
                val local = ((t - burst.delay) / burst.life).coerceIn(0f, 1f)
                if (t <= burst.delay) continue
                val origin = Offset(w * burst.x, h * burst.y)
                // Zündblitz
                val flash = ((local - 0.0f) / 0.18f).coerceIn(0f, 1f)
                if (flash < 1f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f * (1f - flash)),
                        radius = 14f + 90f * flash,
                        center = origin
                    )
                }
                for (i in 0 until burst.particles) {
                    val seed = burstIndex * 97 + i
                    val a = pseudoRandom(seed * 7 + 1)
                    val b = pseudoRandom(seed * 13 + 5)
                    val angle = (i.toFloat() / burst.particles) * 6.28318f + a * 0.35f
                    val speed = (70f + 210f * b) * burst.spread
                    val dx = kotlin.math.cos(angle) * speed * local
                    val dy = kotlin.math.sin(angle) * speed * local + 210f * local * local
                    val ease = 1f - local
                    val head = Offset(origin.x + dx, origin.y + dy)
                    val tail = Offset(
                        origin.x + dx * (1f - 0.16f * ease),
                        origin.y + dy * (1f - 0.16f * ease)
                    )
                    val color = burst.colors[i % burst.colors.size]
                    val radius = (2.6f + 3.4f * b) * (1f - local * 0.65f)
                    drawLine(
                        color = color.copy(alpha = 0.55f * ease),
                        start = tail,
                        end = head,
                        strokeWidth = radius * 0.9f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = PmPalette.bright(color, 0.35f).copy(alpha = ease),
                        radius = radius,
                        center = head
                    )
                }
            }
        }

        // Banner: poppt kurz nach dem ersten Abschuss auf.
        val bannerT = ((t - 0.12f) / 0.25f).coerceIn(0f, 1f)
        if (bannerT > 0f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .scale(0.7f + 0.3f * bannerT)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                PmPalette.complete.copy(alpha = 0.92f * bannerT),
                                PmPalette.bright(PmPalette.complete, 0.35f).copy(alpha = 0.92f * bannerT)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.5f * bannerT),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2A1D00)
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF3A2A00)
                )
            }
        }
    }
}

/** Ein Abschuss des Feuerwerks. */
private data class Burst(
    val x: Float,
    val y: Float,
    val delay: Float,
    val life: Float,
    val particles: Int,
    val spread: Float,
    val colors: List<Color>
)

private val BURSTS: List<Burst> = listOf(
    Burst(0.24f, 0.30f, 0.00f, 0.62f, 34, 1.00f, listOf(Color(0xFFFF6B9D), Color(0xFFFBBF24), Color(0xFFFFF1B8))),
    Burst(0.76f, 0.24f, 0.12f, 0.60f, 34, 0.92f, listOf(Color(0xFF2DD4BF), Color(0xFF38BDF8), Color(0xFFE8FFFB))),
    Burst(0.50f, 0.44f, 0.26f, 0.58f, 40, 1.15f, listOf(Color(0xFF8B7CFF), Color(0xFFA78BFA), Color(0xFFFFE2A6))),
    Burst(0.18f, 0.58f, 0.42f, 0.54f, 28, 0.80f, listOf(Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFFFFFFF))),
    Burst(0.82f, 0.52f, 0.54f, 0.52f, 28, 0.85f, listOf(Color(0xFFFB923C), Color(0xFFFF6B9D), Color(0xFFFFF1B8)))
)
