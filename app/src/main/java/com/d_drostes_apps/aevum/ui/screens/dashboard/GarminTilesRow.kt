package com.d_drostes_apps.aevum.ui.screens.dashboard

import com.d_drostes_apps.aevum.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.data.model.GarminActivity
import com.d_drostes_apps.aevum.data.model.GarminDailySummary
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.util.Locale

/**
 * M18.58: Garmin-Kacheln — "schöne moderne kleine Kacheln, aber nur wenn
 * auch was synchronisiert ist".
 *
 * User-Wunsch:
 *  - Schlaf-Kachel: Mond, dunkelblau, Text drunter mit Schlafdauer
 *    (unabhängig von der Quelle — kommt aus der Timeline/activity_session)
 *  - Schritte-Kachel: andersfarbig, Schritt-Icon, Zahl drunter (Garmin)
 *  - Pro Garmin-Aktivität eine Kachel (z.B. Distanz oder Dauer)
 *  - Kalorien-Kachel (verbrannte Kalorien)
 *  - "Nicht zu groß aber auch nicht zu klein"
 *
 * Design: FlowRow mit quadratischen Kacheln (~104dp), Farbverlauf je
 * Kachel, Icon oben, Wert (Monospace, fett) + Label drunter. Die Kacheln
 * erscheinen nur, wenn Garmin-Daten existieren (nur Schlaf-Kachel immer,
 * wenn Schlaf erfasst wurde).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GarminTilesRow(
    sleepDurationMs: Long,
    summary: GarminDailySummary?,
    activities: List<GarminActivity>,
    modifier: Modifier = Modifier
) {
    // Kacheln bauen — nur wenn Daten existieren (User: "nur wenn auch was
    // synchronisiert ist"). Schlaf-Kachel erscheint immer, wenn Schlaf
    // erfasst wurde (unabhängig von der Quelle).
    // Farben nach Design-Research (M18.58): eine Akzentfarbe pro Metrik —
    // Schlaf DUNKELBLAU (expliziter User-Wunsch "Mond, die Kachel in
    // dunkelblau"), Schritte Cyan, Kalorien Amber, Distanz Grün.
    val tiles = mutableListOf<GarminTile>()
    if (sleepDurationMs > 0) {
        tiles += GarminTile(
            icon = "🌙",
            value = formatDuration(sleepDurationMs),
            colors = listOf(Color(0xFF1E3A8A), Color(0xFF3B82F6))
        )
    }
    summary?.let { s ->
        if (s.steps > 0) {
            tiles += GarminTile(
                icon = "👟",
                value = String.format(Locale.GERMAN, "%,d", s.steps),
                colors = listOf(Color(0xFF155E75), Color(0xFF22D3EE))
            )
        }
        if (s.calories > 0) {
            tiles += GarminTile(
                icon = "🔥",
                value = "${s.calories}",
                colors = listOf(Color(0xFF78350F), Color(0xFFFBBF24))
            )
        }
        if (s.distanceMeters > 0) {
            tiles += GarminTile(
                icon = "📏",
                value = formatDistance(s.distanceMeters),
                colors = listOf(Color(0xFF065F46), Color(0xFF34D399))
            )
        }
    }
    // Pro Garmin-Aktivität eine Kachel (Dauer als Wert — User: "für jede
    // weitere Activity, an dem Tag eine weitere solche Kachel mit bspw
    // Distanz oder dauer").
    activities.forEach { activity ->
        val hasDistance = activity.distanceMeters > 0
        tiles += GarminTile(
            icon = when (activity.activityType.lowercase()) {
                "running" -> "🏃"
                "cycling" -> "🚴"
                "walking" -> "🚶"
                "swimming" -> "🏊"
                "hiking" -> "🥾"
                else -> "⌚"
            },
            value = if (hasDistance) formatDistance(activity.distanceMeters)
            else formatDuration(activity.endAt - activity.startAt),
            colors = listOf(Color(0xFF0E7490), Color(0xFF06B6D4))
        )
    }

    if (tiles.isEmpty()) return

    AevumCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                stringResource(R.string.dashboard_garmin_today),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                tiles.forEach { tile ->
                    GarminTileBox(tile)
                }
            }
        }
    }
}

private data class GarminTile(
    val icon: String,
    val value: String,
    val colors: List<Color>
)

@Composable
private fun GarminTileBox(tile: GarminTile) {
    Box(
        modifier = Modifier
            // M18.59: 4 nebeneinander — 104dp war zu breit (nur 3 passten).
            // 76dp × 4 + 3×8dp Spacing = 328dp → passt auf 360dp-Screens.
            .width(76.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(AevumRadius.lg))
            .background(
                Brush.linearGradient(
                    colors = tile.colors,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(76f, 72f)
                )
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(tile.icon, fontSize = 18.sp)
            Text(
                tile.value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format(Locale.GERMAN, "%.1f km", meters / 1000.0)
    } else {
        "${meters.toInt()} m"
    }
}
