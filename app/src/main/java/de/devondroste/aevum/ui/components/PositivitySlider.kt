package de.devondroste.aevum.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * M18: PositivitySlider — Custom-Slider, KEIN Standard-Material-Slider.
 *
 * - Track: horizontaler Verlauf rot → gelb → grün (0 = schlecht, 100 = gut)
 * - Thumb: weißer Kreis mit Schatten + Score-Anzeige
 * - Anchorpoints: Emoji-Skala unter dem Track (😖 0 · 😐 50 · 😊 100)
 * - Tap auf Track springt direkt, Drag bewegt kontinuierlich
 *
 * Score-Berechnung: 0..100 Int. Der Track verläuft von links (0) nach
 * rechts (100) — intuitiver als ein vertikaler Schieber.
 */
@Composable
fun PositivitySlider(
    score: Int,
    onScoreChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 14.dp,
    thumbSize: Dp = 26.dp,
    // M18.2: Wird beim Loslassen/Ende der Geste gefeuert — für DB-Writes.
    // onScoreChange feuert dagegen bei jedem Drag-Event (nur UI-State).
    onValueChangeFinished: (() -> Unit)? = null
) {
    val scoreFloat = remember(score) { score.toFloat() }
    // M18: Sanfte Animation wenn der Score extern geändert wird (z.B. Reset)
    val animatedScore by animateFloatAsState(
        targetValue = scoreFloat,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "positivityScore"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Score-Anzeige oben rechts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Positivität",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                score.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = positivityColor(score)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { contentDescription = "Positivität $score von 100" }
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        onScoreChange(tapToScore(tapOffset.x, size.width.toFloat()))
                        onValueChangeFinished?.invoke()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { onValueChangeFinished?.invoke() },
                        onDragCancel = { onValueChangeFinished?.invoke() }
                    ) { change, _ ->
                        onScoreChange(tapToScore(change.position.x, size.width.toFloat()))
                    }
                }
        ) {
            // Track mit Farbverlauf
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .align(Alignment.Center)
            ) {
                val w = size.width
                val h = size.height
                val corner = CornerRadius(h / 2, h / 2)

                // Verlaufs-Track: rot → gelb → grün
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFE53935), // Rot (schlecht)
                            Color(0xFFFDD835), // Gelb (neutral)
                            Color(0xFF43A047)  // Grün (gut)
                        )
                    ),
                    topLeft = Offset.Zero,
                    size = Size(w, h),
                    cornerRadius = corner
                )

                // Glanz-Layer oben (subtiler weißer Verlauf)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.25f), Color.Transparent)
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h * 0.5f),
                    cornerRadius = corner
                )

                // Thumb-Position: 0..100 → x
                val fraction = (animatedScore / 100f).coerceIn(0f, 1f)
                val thumbX = (w * fraction).coerceIn(thumbSize.toPx() / 2, w - thumbSize.toPx() / 2)
                val thumbY = h / 2f

                // Thumb-Schatten (weicher dunkler Kreis)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.25f),
                    radius = thumbSize.toPx() / 2 + 2f,
                    center = Offset(thumbX, thumbY + 2f)
                )
                // Thumb selbst (weiß mit Score-Farbring)
                drawCircle(
                    color = Color.White,
                    radius = thumbSize.toPx() / 2,
                    center = Offset(thumbX, thumbY)
                )
                drawCircle(
                    color = positivityColor(score),
                    radius = thumbSize.toPx() / 2 - 4.dp.toPx(),
                    center = Offset(thumbX, thumbY),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // Emoji-Anchorpoints unter dem Track
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("😖", fontSize = 14.sp, modifier = Modifier.offset(x = 0.dp))
            Text("😐", fontSize = 14.sp)
            Text("😊", fontSize = 14.sp, modifier = Modifier.offset(x = 0.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Schlecht", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Neutral", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Gut", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** M18: Tap-Position (x) → Score (0..100). */
private fun tapToScore(x: Float, width: Float): Int {
    if (width <= 0f) return 50
    return ((x / width) * 100f).roundToInt().coerceIn(0, 100)
}

/** M18: Score → Farbe (rot → gelb → grün). */
fun positivityColor(score: Int): Color {
    val s = score.coerceIn(0, 100).toFloat()
    return when {
        s <= 50f -> lerpColor(Color(0xFFE53935), Color(0xFFFDD835), s / 50f)
        else -> lerpColor(Color(0xFFFDD835), Color(0xFF43A047), (s - 50f) / 50f)
    }
}

private fun lerpColor(start: Color, end: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * tt,
        green = start.green + (end.green - start.green) * tt,
        blue = start.blue + (end.blue - start.blue) * tt,
        alpha = start.alpha + (end.alpha - start.alpha) * tt
    )
}
