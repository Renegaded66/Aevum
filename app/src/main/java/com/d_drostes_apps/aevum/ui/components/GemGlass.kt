package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.rotate
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1: Ein Juwel im Glas. [id] = Todo-ID (stabil für die Animation),
 * [colorHex] = Farbe der Aufgabe (verrät, woran gearbeitet wurde).
 */
data class PmGem(val id: String, val colorHex: String, val label: String = "")

/**
 * M19.1: DAS GEFÄSS — Herzstück des Projektmanagements.
 *
 * ANWENDUNGSFALL (wörtlich): „sozusagen ein Gefäß, dass anfangs leer ist, und
 * sich immer weiter füllt mit einer Animation wenn man eine weitere Aufgabe
 * für heute abgehakt hat, und wenn man alle abgehakt hat ist das Glas mit
 * Juwelen voll und kommt eine kurze Feuerwerk Animation."
 *
 * UMSETZUNG:
 *  - Ein Glas wird als Pfad gezeichnet (Glas-Optik mit Rand, Lippe, Glanzkante,
 *    Messmarken, Schatten) — KEIN Emoji, keine Grafikdatei: alles vektoriell,
 *    damit es bei jeder Größe und in jedem Theme sitzt.
 *  - Jedes erledigte Todo wird ein facettierter Edelstein (Tafel + zwei
 *    Facetten + Glanzpunkt), gedreht und leicht versetzt gepackt wie ein
 *    echter Haufen. Die Farbe kommt von der Aufgabe.
 *  - **Geisterplätze**: die noch offenen Todos liegen als transparente
 *    Fassungen OBERHALB des Haufens im Glas. Dadurch sieht man nicht nur, was
 *    geschafft ist, sondern die LÜCKE — das ist der eigentliche Antrieb
 *    („da fehlen noch acht").
 *  - **Fallanimation**: Ein neues Juwel fällt von oben herab und landet mit
 *    einer federnden Kurve; der Haufen sackt kurz nach, am Aufschlagpunkt
 *    blitzt es auf.
 *  - Bei vollem Glas: goldener Rand, leuchtende Juwelen, aufsteigende Funken
 *    (das Feuerwerk selbst liegt in [FireworksOverlay]).
 *
 * Der Haufen wird von unten nach oben gefüllt, in der Reihenfolge des
 * Abhakens — das Glas erzählt damit den Tagesverlauf.
 */
@Composable
fun GemGlass(
    gems: List<PmGem>,
    ghostCount: Int,
    modifier: Modifier = Modifier,
    full: Boolean = false,
    maxGems: Int = 60,
    maxColumns: Int = 0
) {
    val shown = remember(gems, maxGems) { gems.take(maxGems) }
    val ghosts = remember(ghostCount, shown.size, maxGems) {
        ghostCount.coerceAtLeast(0).coerceAtMost((maxGems - shown.size).coerceAtLeast(0))
    }
    val signature = remember(shown) { shown.map { it.id } }

    // Fallanimation: läuft genau beim HINZUFÜGEN eines Juwels.
    val drop = remember { Animatable(1f) }
    var lastSignature by remember { mutableStateOf(signature) }
    LaunchedEffect(signature) {
        if (signature.size > lastSignature.size) {
            drop.snapTo(0f)
            drop.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessMediumLow)
            )
        } else {
            drop.snapTo(1f)
        }
        lastSignature = signature
    }

    // Aufsteigende Funken im vollen Glas.
    val transition = rememberInfiniteTransition(label = "pm-glass")
    val sparkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pm-glass-sparkle"
    )

    Canvas(modifier = modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        drawGlass(
            gems = shown,
            ghostCount = ghosts,
            dropProgress = drop.value,
            full = full,
            sparkle = sparkle,
            maxColumns = maxColumns
        )
    }
}

// ── Zeichnen ──────────────────────────────────────────────────────────────

private fun DrawScope.drawGlass(
    gems: List<PmGem>,
    ghostCount: Int,
    dropProgress: Float,
    full: Boolean,
    sparkle: Float,
    maxColumns: Int
) {
    val w = size.width
    val h = size.height
    val lip = h * 0.058f
    val left = w * 0.15f
    val right = w * 0.85f
    val top = h * 0.085f
    val bottom = h * 0.965f
    val innerPad = w * 0.045f
    val availW = (right - left) - 2f * innerPad
    val availH = (bottom - top) - lip - 2f * innerPad
    val total = gems.size + ghostCount
    val gem = gemSizeFor(availW, availH, total, maxColumns)
    val cols = colCount(availW, gem, maxColumns)
    val pileSquash = (1f - dropProgress) * gem * 0.10f

    val vessel = Path().apply {
        val r = w * 0.075f
        moveTo(left, top)
        lineTo(left, bottom - r)
        quadraticBezierTo(left, bottom, left + r, bottom)
        lineTo(right - r, bottom)
        quadraticBezierTo(right, bottom, right, bottom - r)
        lineTo(right, top)
        close()
    }

    // 1) Schatten auf dem Boden — erdet das Glas.
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent),
            center = Offset(w / 2f, bottom + h * 0.015f),
            radius = (right - left) * 0.62f
        ),
        topLeft = Offset(left - w * 0.06f, bottom - h * 0.012f),
        size = Size((right - left) + w * 0.12f, h * 0.07f)
    )

    // 2) Glas innerlich (hinter den Juwelen)
    drawPath(
        path = vessel,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.045f),
                Color.White.copy(alpha = 0.075f),
                Color.White.copy(alpha = 0.035f)
            ),
            startY = top,
            endY = bottom
        )
    )

    // 3) Juwelen (im Glas beschnitten)
    if (total > 0) {
        clipPath(vessel) {
            // Geisterplätze zuerst (liegen oben — sie sind die Lücke)
            for (index in gems.size until total) {
                val slot = pileSlot(index, cols, total, gem, left, top, bottom, innerPad, availW, availH)
                drawGhostGem(slot, gem * 0.5f, index)
            }
            // dann die echten Juwelen von unten nach oben
            for ((index, gemItem) in gems.withIndex()) {
                val slot = pileSlot(index, cols, total, gem, left, top, bottom, innerPad, availW, availH)
                val isNewest = index == gems.size - 1
                val falling = isNewest && dropProgress < 1f
                val center = if (falling) {
                    Offset(slot.x, slot.y - (1f - dropProgress) * (bottom - top) * 1.15f)
                } else {
                    Offset(slot.x, slot.y + pileSquash)
                }
                if (isNewest && dropProgress > 0.55f && !full) {
                    // Aufblitzen am Aufschlagpunkt
                    val flash = ((dropProgress - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                PmPalette.bright(PmPalette.color(gemItem.colorHex), 0.5f).copy(alpha = 0.55f * (1f - flash)),
                                Color.Transparent
                            ),
                            center = slot,
                            radius = gem * 1.9f
                        ),
                        radius = gem * 1.9f,
                        center = slot
                    )
                }
                drawPmGem(
                    center = center,
                    radius = gem * 0.5f,
                    color = PmPalette.color(gemItem.colorHex),
                    rotation = (pseudoRandom(index * 53 + 11) - 0.5f) * 26f,
                    glow = if (full) 1f else 0f
                )
            }
        }
        // Glasglanz über den Juwelen: lässt sie "im Glas" liegen
        drawPath(
            path = vessel,
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                startY = top,
                endY = top + (bottom - top) * 0.55f
            )
        )
    }

    // 4) Umriss
    drawPath(
        path = vessel,
        brush = Brush.verticalGradient(
            colors = if (full) {
                listOf(
                    PmPalette.complete.copy(alpha = 0.85f),
                    PmPalette.bright(PmPalette.complete, 0.3f).copy(alpha = 0.55f),
                    PmPalette.complete.copy(alpha = 0.30f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.38f),
                    Color.White.copy(alpha = 0.18f),
                    Color.White.copy(alpha = 0.10f)
                )
            },
            startY = top,
            endY = bottom
        ),
        style = Stroke(width = 2.2f, cap = StrokeCap.Round)
    )

    // 5) Lippe/Rand
    val lipTop = top - lip * 0.55f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f),
        topLeft = Offset(left - w * 0.012f, lipTop),
        size = Size((right - left) + w * 0.024f, lip),
        cornerRadius = CornerRadius(lip / 2f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.12f)),
            startY = lipTop,
            endY = lipTop + lip
        ),
        topLeft = Offset(left - w * 0.012f, lipTop),
        size = Size((right - left) + w * 0.024f, lip),
        cornerRadius = CornerRadius(lip / 2f),
        style = Stroke(width = 2f)
    )
    // Öffnung
    drawOval(
        color = Color.Black.copy(alpha = 0.28f),
        topLeft = Offset(left + innerPad * 0.4f, lipTop + lip * 0.30f),
        size = Size((right - left) - innerPad * 0.8f, lip * 0.5f)
    )

    // 6) Glanzkante links + Messmarken rechts (Glas-Anmutung)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)),
            startY = top + lip,
            endY = bottom - lip
        ),
        topLeft = Offset(left + w * 0.028f, top + lip * 1.1f),
        size = Size(w * 0.014f, (bottom - top) - lip * 3f),
        cornerRadius = CornerRadius(w * 0.007f)
    )
    for (i in 1..3) {
        val y = bottom - (bottom - top) * (i / 4f)
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(right - innerPad * 0.65f, y),
            end = Offset(right - innerPad * 0.10f, y),
            strokeWidth = 1.6f,
            cap = StrokeCap.Round
        )
    }

    // 7) Volles Glas: aufsteigende Funken
    if (full) {
        for (i in 0 until 7) {
            val phase = (sparkle + i / 7f) % 1f
            val x = left + (right - left) * (0.2f + 0.6f * pseudoRandom(i * 29 + 3))
            val y = bottom - (bottom - top) * phase
            val alpha = (1f - phase) * 0.85f
            val r = gem * (0.05f + 0.05f * pseudoRandom(i * 41 + 7))
            drawCircle(color = PmPalette.bright(PmPalette.complete, 0.25f).copy(alpha = alpha), radius = r, center = Offset(x, y))
        }
    }
}

/** Facettierter Edelstein: Tafel + zwei Pavillon-Flächen + Glanzpunkt.
 *  Wird auch vom Dashboard-Einstieg ([PmEntryButton]) verwendet. */
internal fun DrawScope.drawPmGem(
    center: Offset,
    radius: Float,
    color: Color,
    rotation: Float,
    glow: Float
) {
    if (glow > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PmPalette.bright(color, 0.6f).copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = radius * 2.6f
            ),
            radius = radius * 2.6f,
            center = center
        )
    }
    rotate(degrees = rotation, pivot = center) {
        fun p(dx: Float, dy: Float) = Offset(center.x + dx * radius, center.y + dy * radius)

        // Silhouette (Grundfläche, damit keine Lücke zwischen den Facetten bleibt)
        val silhouette = Path().apply {
            moveTo(p(-0.30f, -0.62f).x, p(-0.30f, -0.62f).y)
            lineTo(p(0.30f, -0.62f).x, p(0.30f, -0.62f).y)
            lineTo(p(0.66f, -0.10f).x, p(0.66f, -0.10f).y)
            lineTo(p(0.0f, 0.78f).x, p(0.0f, 0.78f).y)
            lineTo(p(-0.66f, -0.10f).x, p(-0.66f, -0.10f).y)
            close()
        }
        drawPath(silhouette, PmPalette.deep(color, 0.35f))

        // Tafel (oben) — hell
        val table = Path().apply {
            moveTo(p(-0.30f, -0.62f).x, p(-0.30f, -0.62f).y)
            lineTo(p(0.30f, -0.62f).x, p(0.30f, -0.62f).y)
            lineTo(p(0.66f, -0.10f).x, p(0.66f, -0.10f).y)
            lineTo(p(-0.66f, -0.10f).x, p(-0.66f, -0.10f).y)
            close()
        }
        drawPath(table, PmPalette.bright(color, 0.42f))

        // Pavillon links — dunkel, rechts — satt
        val pavilionLeft = Path().apply {
            moveTo(p(-0.66f, -0.10f).x, p(-0.66f, -0.10f).y)
            lineTo(p(0f, -0.10f).x, p(0f, -0.10f).y)
            lineTo(p(0f, 0.70f).x, p(0f, 0.70f).y)
            close()
        }
        drawPath(pavilionLeft, PmPalette.deep(color, 0.18f))

        val pavilionRight = Path().apply {
            moveTo(p(0f, -0.10f).x, p(0f, -0.10f).y)
            lineTo(p(0.66f, -0.10f).x, p(0.66f, -0.10f).y)
            lineTo(p(0f, 0.70f).x, p(0f, 0.70f).y)
            close()
        }
        drawPath(pavilionRight, color)

        // Glanzpunkt
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = radius * 0.13f,
            center = p(-0.26f, -0.34f)
        )
        // Kante der Tafel
        drawLine(
            color = Color.White.copy(alpha = 0.30f),
            start = p(-0.30f, -0.62f),
            end = p(0.30f, -0.62f),
            strokeWidth = radius * 0.10f,
            cap = StrokeCap.Round
        )
    }
}

/** Leere Fassung = noch offenes Todo. Zeigt die Lücke, nicht die Leistung. */
private fun DrawScope.drawGhostGem(center: Offset, radius: Float, index: Int) {
    rotate(degrees = (pseudoRandom(index * 53 + 11) - 0.5f) * 26f, pivot = center) {
        fun p(dx: Float, dy: Float) = Offset(center.x + dx * radius, center.y + dy * radius)
        val silhouette = Path().apply {
            moveTo(p(-0.30f, -0.62f).x, p(-0.30f, -0.62f).y)
            lineTo(p(0.30f, -0.62f).x, p(0.30f, -0.62f).y)
            lineTo(p(0.66f, -0.10f).x, p(0.66f, -0.10f).y)
            lineTo(p(0.0f, 0.78f).x, p(0.0f, 0.78f).y)
            lineTo(p(-0.66f, -0.10f).x, p(-0.66f, -0.10f).y)
            close()
        }
        drawPath(silhouette, Color.White.copy(alpha = 0.045f))
        drawPath(silhouette, Color.White.copy(alpha = 0.16f), style = Stroke(width = 1.5f))
    }
}

/**
 * Position eines Juwels im Haufen: von unten nach oben, Zeilen zentriert, mit
 * leichtem Versatz — sieht aus wie ein Haufen, ist aber deterministisch
 * (kein Springen bei Neukomposition).
 */
private fun pileSlot(
    index: Int,
    cols: Int,
    total: Int,
    gem: Float,
    left: Float,
    top: Float,
    bottom: Float,
    innerPad: Float,
    availW: Float,
    availH: Float
): Offset {
    val row = index / cols
    val col = index % cols
    val rowsTotal = if (cols > 0) (total + cols - 1) / cols else 1
    val inRow = if (row == rowsTotal - 1 && cols > 0) total - row * cols else cols
    val rowWidth = inRow * gem * 1.04f
    val startX = left + innerPad + ((availW - rowWidth) / 2f).coerceAtLeast(0f) + gem * 0.52f
    val jitterX = (pseudoRandom(index * 31 + 7) - 0.5f) * gem * 0.16f
    val jitterY = (pseudoRandom(index * 17 + 5) - 0.5f) * gem * 0.10f
    val x = startX + col * gem * 1.04f + jitterX
    val y = bottom - innerPad - gem * 0.66f - row * gem * 0.82f + jitterY
    return Offset(x, y)
}

/** Kleinste Juwelengröße, die alle Steine in das Glas passt. */
private fun gemSizeFor(availW: Float, availH: Float, total: Int, maxColumns: Int): Float {
    // M19.2: Die schmale Seitenspalte („Glas neben den Todos") bekommt ein
    // Spaltenlimit — sonst würden die Juwelen dort zu winzigen Krümeln.
    val startCols = if (maxColumns > 0) maxColumns.toFloat() else 6f
    var gem = (availW / startCols).coerceAtMost(availH / 4.5f)
    var guard = 0
    while (guard < 60 && gem > 4f) {
        val cols = colCount(availW, gem, maxColumns)
        val rows = ((availH / (gem * 0.82f)).toInt()).coerceAtLeast(1)
        if (cols * rows + rows / 2 >= total) break
        gem *= 0.9f
        guard++
    }
    return gem
}

/** Spaltenzahl für eine Juwelengröße — optional auf [maxColumns] begrenzt. */
private fun colCount(availW: Float, gem: Float, maxColumns: Int): Int {
    val natural = ((availW / (gem * 1.06f)).toInt()).coerceAtLeast(1)
    return if (maxColumns > 0) natural.coerceAtMost(maxColumns) else natural
}
