package com.d_drostes_apps.aevum.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M19.4 — DAS DASHBOARD-SHEET („Karte über dem Hero").
 *
 * Das komplette Karten-Drumherum des Dashboards liegt jetzt in EINER
 * großen, oben abgerundeten Fläche (28dp — bewusst weicher als der
 * 24dp-Token: die Kante soll als Blatt lesbar sein, kein Kasten).
 * Sie scrollt mit der Liste und schiebt sich beim Wischen über den
 * gepinnten Hero — dessen Farbfläche schimmert durch die abgerundeten
 * Ecken (DashboardHeroPeek), dadurch liegt das Sheet optisch ÜBER dem
 * Hero (Aufbau des Referenzbilds).
 *
 * Aufbau der Fläche:
 *  - vertikaler Verlauf (leicht heller oben → Grundton unten, damit die
 *    Kante eine Lichtkante bekommt, kein harter Schnitt),
 *  - Rim-Light: feine horizontale Lichtlinie an der Oberkante,
 *  - weicher Glow direkt unter der Kante (Eisblau, sehr dezent).
 *
 * Bewusste Alternative war ein zweites gepinntes Layer mit eigener
 * Offset-Mathematik — verworfen: eine Fläche im Scroll-Content bleibt
 * beim Tageswechsel (AnimatedContent) und bei Taps trivial korrekt.
 */
@Composable
internal fun DashboardSheet(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = Color.Black,
                spotColor = Color.Black,
                clip = true
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF151E30),
                        Color(0xFF0E1523),
                        Color(0xFF0A0F19)
                    )
                )
            )
    ) {
        // Weicher Eisblau-Glow unter der Oberkante (Tiefe, keine Kante).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            primary.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Rim-Light: feine Lichtlinie an der Oberkante.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            primary.copy(alpha = 0.65f),
                            Color.Transparent
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md)
                .padding(top = 18.dp, bottom = 26.dp)
        ) {
            content()
        }
    }
}
