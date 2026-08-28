package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.66-FIX3: Zone-Banner für das Dashboard.
 *
 * Zeigt die aktuelle Geofence-Zone an, in der sich der User befindet
 * (z.B. "Arbeit", "Zuhause"), oder "Abwesend" wenn er in keiner Zone ist.
 *
 * Der Banner ist dezent (klein, nicht aufdringlich), aber klar sichtbar.
 * Gradient-Hintergrund abgeleitet von der Activity-Farbe der verknüpften
 * Automatisierung (wenn vorhanden).
 */
@Composable
fun ZoneBanner(
    zone: CurrentZoneProvider.ZoneInfo?,
    modifier: Modifier = Modifier,
    // M18.66-FIX7: Debug-Info — temporär fürs Debugging. Zeigt an,
    // was checkNow() gemacht hat (ENTER/EXIT, autoStart, Fehler).
    // M18.66-FIX12: Debug-Info + Distanz-Text entfernt — nur Zonen-Name.
    debugInfo: String = ""
) {
    val colors = MaterialTheme.colorScheme

    val gradientColors = if (zone != null) {
        listOf(
            colors.primaryContainer.copy(alpha = 0.85f),
            colors.surfaceVariant.copy(alpha = 0.72f)
        )
    } else {
        listOf(
            colors.surfaceVariant.copy(alpha = 0.50f),
            colors.surfaceVariant.copy(alpha = 0.30f)
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.lg),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AevumRadius.lg))
                .background(Brush.linearGradient(gradientColors))
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm + AevumSpacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(AevumRadius.sm))
                        .background(
                            if (zone != null) colors.primary.copy(alpha = 0.15f)
                            else colors.outline.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (zone != null) {
                        Icon(
                            imageVector = Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.LocationOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.outline
                        )
                    }
                }

                // Text
                AnimatedContent(
                    targetState = zone?.geofence?.name ?: stringResource(R.string.component_zone_absent),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "zone-name"
                ) { name ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            ),
                            color = if (zone != null) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // M18.66-FIX12: Distanz-Text + Debug-Info entfernt.
                        // Nur der Zonen-Name wird angezeigt.
                    }
                }
            }
        }
    }
}