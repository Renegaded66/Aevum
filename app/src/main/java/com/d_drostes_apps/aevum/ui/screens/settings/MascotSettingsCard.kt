package com.d_drostes_apps.aevum.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.screens.dashboard.MASCOT_SETTINGS_PREVIEW_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.MascotView
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

// M19.5: Test-Tags der Maskottchen-Einstellungen (Robolectric-Suite).
internal const val MASCOT_SETTINGS_SWITCH_TEST_TAG = "mascot-settings-switch"
internal const val MASCOT_SETTINGS_CHEEKS_TEST_TAG = "mascot-settings-cheeks"
internal fun mascotColorTestTag(colorId: String) = "mascot-color-$colorId"
internal fun mascotAntennaTestTag(style: String) = "mascot-antenna-$style"

/**
 * M19.5: Maskottchen-Sektion der Einstellungen (Devon: „ein und
 * ausschalten … default aus. Wenn ein, dann kann man sich selber ein
 * maskottchen erstellen, so ein richtig süßes putziges wesen. Die farbe
 * des maskottchen und vielleicht ein paar optische eigenschaften soll
 * man in den einstellungen festlegen können.").
 *
 * Aufbau: Titel + Beschreibung + Schalter (Default aus ⇒ alles bleibt
 * wie bisher); bei aktiviertem Modus: interaktive Vorschau (Tippen →
 * niedliche Reaktion), Farb-Palette (8 Töne), Antennen-Spitze
 * (Kugel/Stern/Blitz — M19.6, PAG-Vorbild) und Bäckchen-Schalter.
 * Alle Änderungen wirken sofort im Dashboard (ein DataStore-Flow speist
 * beide Screens).
 */
@Composable
internal fun MascotSettingsCard(
    config: MascotRepository.MascotConfig,
    onToggle: (Boolean) -> Unit,
    onSelectColor: (String) -> Unit,
    onSelectAntennaStyle: (String) -> Unit,
    onToggleCheeks: (Boolean) -> Unit
) {
    AevumCard(variant = CardVariant.Elevated) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_mascot_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.settings_mascot_desc),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag(MASCOT_SETTINGS_SWITCH_TEST_TAG)
                )
            }

            AnimatedVisibility(visible = config.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    // Interaktive Vorschau — Tippen löst die süßen
                    // Reaktionen aus (Sternchen-Hüpfer, Wackel+Funkeln,
                    // Bäckchen-Pop).
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        MascotView(
                            config = config,
                            smileLevel = 0.85f,
                            sleeping = false,
                            mascotSize = 120.dp,
                            testTag = MASCOT_SETTINGS_PREVIEW_TEST_TAG
                        )
                    }
                    Text(
                        stringResource(R.string.settings_mascot_preview_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Farbe — feste Palette in Aevum-Tönen.
                    Text(
                        stringResource(R.string.settings_mascot_color),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MascotRepository.COLOR_PALETTE.forEach { (id, color) ->
                            val selected = id == config.colorId
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(color, CircleShape)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        },
                                        shape = CircleShape
                                    )
                                    .clickable { onSelectColor(id) }
                                    .testTag(mascotColorTestTag(id))
                            )
                        }
                    }

                    // Ohren — rund / spitz / lang.
                    Text(
                        stringResource(R.string.settings_mascot_antenna),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MascotRepository.ANTENNA_STYLES.forEach { style ->
                            val selected = style == config.antennaStyle
                            val label = when (style) {
                                MascotRepository.ANTENNA_STAR ->
                                    stringResource(R.string.settings_mascot_antenna_star)
                                MascotRepository.ANTENNA_BOLT ->
                                    stringResource(R.string.settings_mascot_antenna_bolt)
                                else ->
                                    stringResource(R.string.settings_mascot_antenna_ball)
                            }
                            val pillShape = RoundedCornerShape(AevumRadius.full)
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                        pillShape
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        },
                                        pillShape
                                    )
                                    .clickable { onSelectAntennaStyle(style) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag(mascotAntennaTestTag(style))
                            ) {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Bäckchen an/aus.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_mascot_cheeks),
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp
                        )
                        Switch(
                            checked = config.cheeks,
                            onCheckedChange = onToggleCheeks,
                            modifier = Modifier.testTag(MASCOT_SETTINGS_CHEEKS_TEST_TAG)
                        )
                    }
                }
            }
        }
    }
}
