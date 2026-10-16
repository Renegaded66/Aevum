package com.d_drostes_apps.aevum.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.d_drostes_apps.aevum.domain.projects.PmColors

/**
 * M19.1: Farbräume des Projektmanagements.
 *
 * Die Fachlogik liefert Farben als Hex-Strings ([PmColors], rein und testbar).
 * Dieses Objekt macht daraus Compose-Farben — inklusive der Abstufungen, die
 * das spielerische Design braucht (heller Gradient, weicher Hintergrund,
 * sattes Leuchten). Bewusst OHNE `android.graphics.Color.parseColor`, damit
 * Previews und Tests in der reinen JVM laufen.
 */
object PmPalette {

    val fallback: Color = Color(0xFF8B7CFF)

    /** "#rrggbb" → Color; ungültige Werte fallen auf die Primärfarbe zurück. */
    fun color(hex: String?): Color {
        if (!PmColors.isValidHex(hex)) return fallback
        val body = hex!!.trim().removePrefix("#")
        val expanded = if (body.length == 3) body.map { "$it$it" }.joinToString("") else body
        val value = expanded.toLongOrNull(16) ?: return fallback
        return Color(0xFF000000L or value)
    }

    /** Helle Variante für den Gradient-Verlauf des Balkens. */
    fun bright(color: Color, amount: Float = 0.34f): Color = lerp(color, Color.White, amount)

    /** Dunkle Variante für Tiefe/Umrandungen. */
    fun deep(color: Color, amount: Float = 0.45f): Color = lerp(color, Color(0xFF0A0B14), amount)

    /** Weicher Hintergrund (Karten, Chips) — nie deckend, damit es glast. */
    fun soft(color: Color, alpha: Float = 0.18f): Color = color.copy(alpha = alpha)

    /** Verlauf für Balken und leuchtende Flächen. */
    fun gradient(color: Color): List<Color> = listOf(deep(color, 0.10f), bright(color, 0.42f))

    /** Vollendete Dinge werden golden — ohne Level-System, nur als Auszeichnung. */
    val complete: Color = Color(0xFFFBBF24)

    /** Übernommene Todos ("seit gestern") — Aufmerksamkeit ohne Panik. */
    val rollover: Color = Color(0xFFFB923C)

    /** Hintergrund-Auren für den PM-Himmel (animiert). */
    val sky: List<Color> = listOf(
        Color(0xFF1B1436), Color(0xFF0E1B33), Color(0xFF251537), Color(0xFF0B1A2A)
    )
}
