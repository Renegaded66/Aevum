package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1: Spielerischer Fortschrittsbalken.
 *
 * DER ANWENDUNGSFALL, DEN DIESER BALKEN LÖST: „Jede Aufgabe kriegt einen
 * schönen spielerischen Balken mit einem Fortschritt — dass man also nicht nur
 * am jeweiligen Tag sehen kann was man geschafft hat, sondern dass das Projekt
 * tatsächlich fortschreitet."
 *
 * Deshalb zeigt der Balken IMMER den Gesamtfortschritt der Aufgabe (alle
 * Todos, auch die von morgen) und nicht nur den Tagesausschnitt. Er ist die
 * Antwort auf „es scheint kein Ende zu haben": ein Ende, das messbar näher
 * kommt.
 *
 * ANIMATIONEN (alles im Bild gezeichnet, keine zusätzlichen Layouts):
 *  - Füllung schießt mit einer federnden Kurve auf den neuen Wert (nie linear,
 *    sonst fühlt sich Fortschritt wie eine Fortschrittsanzeige an)
 *  - wandernder Glanz, solange noch etwas offen ist → signalisiert „es geht
 *    weiter"
 *  - ab 100 %: goldener Verlauf, langsam atmendes Leuchten, aufpoppender Haken
 *  - Zahlen werden gezählt (animateIntAsState im Aufrufer)
 */
@Composable
fun PlayfulProgressBar(
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    trackColor: Color? = null,
    showPercent: Boolean = false,
    percentText: String? = null,
    animate: Boolean = true
) {
    val target = progress.coerceIn(0f, 1f)
    val filled by animateFloatAsState(
        targetValue = target,
        animationSpec = if (animate) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        } else {
            tween(durationMillis = 0)
        },
        label = "pm-progress-fill"
    )
    val complete = target >= 0.999f
    val gold = if (complete) PmPalette.complete else accent
    val track = trackColor ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    // Wandernder Glanz: nur solange etwas offen ist. Bei 100 % würde er die
    // Ruhe zerstören, die der erreichte Zustand ausstrahlen soll.
    val transition = rememberInfiniteTransition(label = "pm-shine")
    val shine by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pm-shine-x"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pm-pulse"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val corner = CornerRadius(size.height / 2f, size.height / 2f)
            // Spur
            drawRoundRect(
                color = track,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = corner
            )
            if (filled <= 0.001f) return@Canvas
            val fillWidth = (size.width * filled).coerceAtLeast(size.height * 0.6f)
            val fillSize = Size(fillWidth, size.height)

            // Glanz hinter dem Balken, wenn vollendet (atmendes Gold).
            if (complete) {
                drawRoundRect(
                    color = gold.copy(alpha = 0.22f * pulse),
                    topLeft = Offset(-2f, -2f),
                    size = Size(size.width + 4f, size.height + 4f),
                    cornerRadius = CornerRadius((size.height + 4f) / 2f)
                )
            }

            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = PmPalette.gradient(gold),
                    startX = 0f,
                    endX = fillWidth
                ),
                topLeft = Offset.Zero,
                size = fillSize,
                cornerRadius = corner
            )

            // Lichtkante oben — gibt dem Balken Körper.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(size.height * 0.18f, size.height * 0.16f),
                size = Size((fillWidth - size.height * 0.36f).coerceAtLeast(0f), size.height * 0.18f),
                cornerRadius = CornerRadius(size.height * 0.10f)
            )

            // Wandernder Glanz
            if (!complete && filled < 0.999f) {
                val shineWidth = size.height * 2.2f
                val shineCenter = (shine * (size.width + shineWidth * 2f)) - shineWidth
                clipToBar(fillWidth, size.height) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.42f),
                                Color.Transparent
                            ),
                            startX = shineCenter - shineWidth / 2f,
                            endX = shineCenter + shineWidth / 2f
                        ),
                        topLeft = Offset(0f, 0f),
                        size = Size(fillWidth, size.height),
                        cornerRadius = corner
                    )
                }
            }

            // Abgeschlossen: Haken poppt am rechten Ende auf.
            if (complete) {
                drawTick(
                    center = Offset(size.width - size.height / 2f, size.height / 2f),
                    radius = size.height * 0.30f,
                    color = Color(0xFF2A1D00),
                    progress = 1f
                )
            }
        }

        if (showPercent && percentText != null) {
            Text(
                text = percentText,
                fontSize = (height.value * 0.72f).coerceIn(9f, 16f).sp,
                fontWeight = FontWeight.SemiBold,
                color = if (complete) Color(0xFF2A1D00) else Color.White
            )
        }
    }
}

/** Zeichnet nur innerhalb des Balkenradius (für den Glanz). */
private inline fun DrawScope.clipToBar(width: Float, height: Float, block: DrawScope.() -> Unit) {
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 0f,
                top = 0f,
                right = width,
                bottom = height,
                cornerRadius = CornerRadius(height / 2f, height / 2f)
            )
        )
    }
    clipPath(path) { this@clipToBar.block() }
}

/** Haken-Strich (geteilt mit dem Häkchen der Todos, daher hier zentral). */
internal fun DrawScope.drawTick(center: Offset, radius: Float, color: Color, progress: Float) {
    val p1 = Offset(center.x - radius * 0.95f, center.y + radius * 0.05f)
    val p2 = Offset(center.x - radius * 0.15f, center.y + radius * 0.75f)
    val p3 = Offset(center.x + radius * 1.05f, center.y - radius * 0.85f)
    val t = progress.coerceIn(0f, 1f)
    if (t <= 0f) return
    if (t < 0.45f) {
        val f = t / 0.45f
        drawLine(color, p1, lerp(p1, p2, f), strokeWidth = radius * 0.34f, cap = StrokeCap.Round)
    } else {
        drawLine(color, p1, p2, strokeWidth = radius * 0.34f, cap = StrokeCap.Round)
        val f = (t - 0.45f) / 0.55f
        drawLine(color, p2, lerp(p2, p3, f), strokeWidth = radius * 0.34f, cap = StrokeCap.Round)
    }
}

private fun lerp(a: Offset, b: Offset, f: Float): Offset =
    Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

/** Umrandeter Haken für runde Flächen (Karte, Chip). */
internal fun DrawScope.drawTickOutline(center: Offset, radius: Float, color: Color, progress: Float) {
    drawCircle(color = color.copy(alpha = 0.18f), radius = radius * 1.6f, center = center)
    drawCircle(
        color = color.copy(alpha = 0.55f),
        radius = radius * 1.6f,
        center = center,
        style = Stroke(width = radius * 0.14f)
    )
    drawTick(center = center, radius = radius, color = color, progress = progress)
}
