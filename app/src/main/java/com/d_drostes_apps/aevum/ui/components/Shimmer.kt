package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Schrägstellung des Lichtbands — leicht geneigt wirkt der Streifen organischer. */
private const val SHEEN_TILT_DEGREES = 16f

/** Bandbreite als Bruchteil der Elementbreite. */
private const val SHEEN_BAND_FRACTION = 0.34f

/**
 * M19.2: Schimmer-Effekt für ausgewählte Buttons — der Nutzer wollte „gern
 * auch mit Schimmern". Der wandernde Streifen ist hier der eisige Glanz.
 *
 * WIE: Ein Endlos-Wert (0..1, linear) läuft einmal pro Periode durch
 * [PmShimmer.sweepProgress]: Auf der ersten Strecke wandert das Lichtband
 * über das Element, danach Pause bis zum Neustart. [PmShimmer] ist reine
 * Arithmetik und damit in der JVM-Suite testbar; der Modifier unten ist nur
 * die dünne visuelle Schale (InfiniteTransition + drawWithContent).
 */
object PmShimmer {

    /** Fester Mindestüberhang über 1f: die Ruhephase muss eindeutig > 1f
     *  sein, damit das Band sicher als „weg" erkannt wird. */
    private const val REST_OFFSET = 0.001f

    /**
     * 0..1 Sweep-Position aus einem Animationswert.
     *
     * Vor [sweepFraction] steigt die Position linear von 0 auf kurz vor 1;
     * ab [sweepFraction] ist Ruhephase — das Ergebnis liegt > 1f und der
     * zeichnende Teil lässt das Band weg. Bewusst pure Arithmetik, damit
     * sie ohne Android-Setup geprüft werden kann.
     */
    fun sweepProgress(t: Float, sweepFraction: Float = 0.72f): Float {
        if (t <= 0f) return 0f
        val rest = t - sweepFraction
        if (rest >= 0f) return 1f + REST_OFFSET + rest
        return t / sweepFraction
    }

    /**
     * Start-X des Lichtbands in px für einen Sweep (0..1).
     *
     * 0 → Band voll links versteckt (-bandPx), 1 → Band voll rechts hinaus
     * (widthPx). Linear, also monoton — das Band überquert das Element
     * genau einmal, ohne zu zucken.
     */
    fun bandStartX(widthPx: Float, bandPx: Float, sweep: Float): Float =
        -bandPx + (widthPx + bandPx) * sweep
}

/**
 * M19.2: Legt den wandernden Licht-Schimmer über den gezeichneten Inhalt.
 *
 * Der AUFRUFER clippt: Der Modifier zeichnet das Band absichtlich über die
 * Elementgrenzen hinaus (schräg, höher als das Element). Auf eine Form
 * begrenzen funktioniert deshalb nur in dieser Reihenfolge:
 *
 * ```
 * Modifier.clip(shape).shimmering(...)
 * ```
 *
 * Umgekehrt (clip nach shimmering) glänzt das Band über die Kante hinaus.
 *
 * WIE: Linear endlos animierter Wert → [PmShimmer.sweepProgress] → in der
 * Ruhephase (> 1f) wird nichts gezeichnet, sonst ein diagonales Lichtband
 * (transparent → sheen → transparent), das von links nach rechts zieht.
 */
fun Modifier.shimmering(
    enabled: Boolean = true,
    periodMillis: Int = 3400,
    sheenColor: Color = Color.White,
    intensity: Float = 0.28f,
    sweepFraction: Float = 0.72f
): Modifier {
    // Ohne Effekt gar keine Animation aufbauen — composed() wird gar
    // nicht erst betreten.
    if (!enabled) return this
    return composed {
        val transition = rememberInfiniteTransition(label = "pm-shimmer")
        val t by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = periodMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pm-shimmer-progress"
        )
        this.drawWithContent {
            drawContent()
            val sweep = PmShimmer.sweepProgress(t, sweepFraction)
            if (sweep > 1f) return@drawWithContent // Ruhephase: Streifen aus
            val bandPx = size.width * SHEEN_BAND_FRACTION
            // Das Band muss, um den Winkel gedreht, die Ecken des Elements
            // noch überspannen — deshalb diagonal verlängert.
            val tiltRad = (SHEEN_TILT_DEGREES * PI / 180f).toFloat()
            val bandLength = size.height * cos(tiltRad) + size.width * sin(tiltRad)
            rotate(
                degrees = SHEEN_TILT_DEGREES,
                pivot = Offset(size.width / 2f, size.height / 2f)
            ) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            sheenColor.copy(alpha = intensity),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(
                        PmShimmer.bandStartX(size.width, bandPx, sweep),
                        (size.height - bandLength) / 2f
                    ),
                    size = Size(bandPx, bandLength)
                )
            }
        }
    }
}