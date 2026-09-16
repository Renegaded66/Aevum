package com.d_drostes_apps.aevum.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode

/**
 * M18.129: Die „geplanter Block"-Textur für die Timeline.
 *
 * Ein Kalender-Termin, der noch NICHT aufgezeichnet ist, wird als
 * diagonal gestrichelte Fläche dargestellt — klar unterscheidbar von
 * einer echten Aufzeichnung (flach gefüllt) und trotzdem in der Farbe
 * und mit dem Icon der zugeordneten Aktivität.
 *
 * WARUM EINE EIGENE ZEICHEN-FUNKTION:
 * Die Timeline zeichnet Blöcke in einem `Canvas`. MaterialTheme-Werte
 * sind im DrawScope NICHT verfügbar (M18.61-Lektion: „MaterialTheme.
 * colorScheme im Canvas-DrawScope verboten → Farben VOR dem Lambda in
 * lokale Vals extrahieren"). Diese Funktion bekommt die Farbe daher
 * als Parameter und konstruiert nur den Brush.
 */
object PlannedBlockTexture {

    /** Abstand der Streifen in Pixeln (diagonal, also optisch ~1.4×). */
    private const val STRIPE_PERIOD_PX = 14f

    /** Breite der sichtbaren Streifen (Rest bleibt transparent). */
    private const val STRIPE_WIDTH_PX = 5f

    /**
     * Baut den Diagonal-Streifen-Brush.
     *
     * Technik: ein 45°-LinearGradient mit `TileMode.Repeated` über eine
     * Periode von [STRIPE_PERIOD_PX]. Der Gradient läuft nur über die
     * Streifenbreite, danach wird das Muster gekachelt — dadurch entstehen
     * beliebig viele Streifen ohne Geometrie-Berechnung (dieselbe Technik
     * wie das M18.51-Notification-Muster).
     *
     * @param color Aktivitätsfarbe (Icon-Farbe der geplanten Aktivität).
     * @param fillAlpha Deckkraft der Streifen — deutlich niedriger als
     *        echte Blöcke (0.62), damit „geplant" auf einen Blick
     *        erkennbar ist.
     */
    fun brush(
        color: Color,
        fillAlpha: Float = 0.34f
    ): Brush {
        val stripe = color.copy(alpha = fillAlpha)
        val gap = color.copy(alpha = 0.06f)
        return Brush.linearGradient(
            colors = listOf(stripe, stripe, gap, gap),
            // 45°-Verlauf: von links-oben nach rechts-unten.
            start = Offset(0f, 0f),
            end = Offset(STRIPE_PERIOD_PX, STRIPE_PERIOD_PX),
            tileMode = TileMode.Repeated
        )
    }

    /**
     * Alpha für die Kontur des geplanten Blocks. Ebenfalls reduziert —
     * eine gestrichelte Kante signalisiert „vorläufig".
     */
    fun borderAlpha(): Float = 0.55f

    /**
     * Deckkraft der echten (aufgezeichneten) Blöcke — als Referenzwert
     * hier dokumentiert, damit der Kontrast bewusst gewählt ist und nicht
     * auseinanderläuft, wenn jemand die Timeline-Optik anpasst.
     */
    fun recordedFillAlpha(): Float = 0.62f
}
