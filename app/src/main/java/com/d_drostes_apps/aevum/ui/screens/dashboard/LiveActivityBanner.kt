package com.d_drostes_apps.aevum.ui.screens.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.ui.components.BubbleStream
import com.d_drostes_apps.aevum.ui.components.FlipTimeText
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.4: LiveActivityBanner — der "richtig deutlich sichtbare Banner" in
 * der App. Gleitet von oben rein (AnimatedVisibility), zeigt:
 *  - Aktivitätstitel + Live-Timer (aktualisiert jede Sekunde)
 *  - Pause/Fortsetzen + Stoppen Buttons (groß, klar)
 *  - Farbverlauf je nach Session-Typ (Auto = primary, manuell = tertiary)
 *
 * M19.5: Umgezogen aus DashboardScreen.kt und IM DASHBOARD-HERO verankert
 * (Devon: „unter der anzeige der güte … der kasten … der mit der animation
 * die aktuelle aufzeichnung anzeigt") — deshalb `internal` statt `private`
 * (DashboardHero ruft ihn auf, Tests rendern ihn direkt).
 *
 * M19.5: Emoji-Zentrierung gefixt (Devon: „die emojis für pause, wechsel
 * und stop … nicht 100% mittig"): Die Glyphen liefen mit Default-
 * ContentPadding und voller Font-Leading sichtbar aus der Mitte. Jetzt:
 * contentPadding 0, fillMaxSize-Zentrierbox, lineHeight = fontSize und
 * includeFontPadding = false — die Zeichen sitzen exakt mittig.
 */
@Composable
internal fun LiveActivityBanner(
    state: LiveActivityState,
    nowMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    // M18.60: Wechsel-Button wie in der Benachrichtigung — oeffnet das
    // Switch-Sheet, beendet die aktuelle Session und startet die neue.
    onSwitch: (String, String?) -> Unit = { _, _ -> },
    activityTypes: List<com.d_drostes_apps.aevum.data.model.ActivityType> = emptyList()
) {
    // M18.93v3 FANCY-BANNER: Das kompakte Viereck ("Aufnahme läuft" + Punkt
    // + 3 Buttons) ist durch ein echtes Auflade-Erlebnis ersetzt:
    // ChargingRing (Füll-Ring um das Activity-Icon, Glow-Komet wandert in
    // einem Orbit, Kern pulsiert) + Titel/Timer daneben + Buttons unten.
    // Fraction = aktive Aufzeichnungszeit / 60 Min (volle Stunde = voller
    // Ring, danach bleibt er voll — kein irritierender Reset).
    val title = when (state) {
        is LiveActivityState.Running -> state.title
        is LiveActivityState.Paused -> state.title
        else -> return
    }
    val isPaused = state is LiveActivityState.Paused
    // M18.60: State fuer das Wechsel-Sheet
    var showSwitchSheet by remember { mutableStateOf(false) }
    // M18.93v3: Puls für ChargingRing + Status-Text (atmet sanft).
    val pulse = rememberInfiniteTransition(label = "bannerPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "bannerPulseAlpha"
    )
    val accent = if (isPaused) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val activityIcon = when (state) {
        is LiveActivityState.Running ->
            activityTypes.firstOrNull { it.name == state.title }?.icon ?: ""
        is LiveActivityState.Paused ->
            activityTypes.firstOrNull { it.name == state.title }?.icon ?: ""
        else -> ""
    }
    val activeMs = when (state) {
        is LiveActivityState.Running -> state.activeMs(nowMs)
        is LiveActivityState.Paused -> state.activeMs(nowMs)
        else -> 0L
    }
    val timerText = "%02d:%02d:%02d".format(
        activeMs / 3_600_000,
        (activeMs % 3_600_000) / 60_000,
        (activeMs % 60_000) / 1000
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.lg),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.42f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f)
                        )
                    )
                )
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                // M18.93v4 ZWEI-ICON-HERO mit BUBBLE-STREAM: Links das
                // Activity-Icon (in pulsierendem Halo), rechts der Zeit-Tank
                // (⏳ in leisem Ring); dazwischen fliegt endlos ein Strom
                // von Energie-Blubberblasen von links nach rechts — "die
                // Aktivität lädt den Tank auf". KEIN Fortschritt, kein Balken:
                // reine Dauerauflade-Animation. Bei Pause: Blasen stehen.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                ) {
                    // Icon 1: Aktivität (Glow-Kern).
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .scale(0.96f + 0.05f * pulseAlpha)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        accent.copy(alpha = 0.32f),
                                        accent.copy(alpha = 0.10f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(activityIcon.ifBlank { "\u23F1" }, fontSize = 24.sp)
                    }

                    // Blubber-Strom (füllt den Zwischenraum) — fliegt immer.
                    BubbleStream(
                        color = accent,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        animate = !isPaused
                    )

                    // Icon 2: Zeit-Tank (statisch, leiser Ring — das Ziel).
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .border(2.dp, accent.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("\u23F1", fontSize = 22.sp, color = accent)
                    }
                }

                // Titel + Status + Timer (kompakt darunter).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            if (isPaused) stringResource(R.string.dashboard_paused)
                            else stringResource(R.string.dashboard_recording),
                            fontSize = 10.sp,
                            letterSpacing = 1.0.sp,
                            color = accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.alpha(0.55f + 0.45f * pulseAlpha)
                        )
                        Text(
                            title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // M18.93v5 FLIP-CLOCK: Ziffern klappen analog um.
                    FlipTimeText(timeText = timerText, accent = accent)
                }
                // Zeile 2: Aktionen — volle Breite, nichts wird gequetscht.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    // Pause/Fortsetzen (M19.5: Glyphe exakt mittig).
                    Button(
                        onClick = if (isPaused) onResume else onPause,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        CenteredGlyph(if (isPaused) "\u25B6" else "\u23F8")
                    }
                    // M18.60: Wechsel-Button — wie in der Benachrichtigung.
                    // Oeffnet das Switch-Sheet (alle Aktivitaeten).
                    OutlinedButton(
                        onClick = { showSwitchSheet = true },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        CenteredGlyph("\u21C4", color = MaterialTheme.colorScheme.onSurface)
                    }
                    // Stoppen
                    OutlinedButton(
                        onClick = onStop,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        CenteredGlyph("\u25A0", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // M18.60: Wechsel-Sheet — gleiche Auswahl wie in der Benachrichtigung.
    if (showSwitchSheet) {
        SwitchActivityPickerSheet(
            currentTitle = title,
            activityTypes = activityTypes,
            onSwitch = { newTypeId, newCategoryId ->
                onSwitch(newTypeId, newCategoryId)
                showSwitchSheet = false
            },
            onDismiss = { showSwitchSheet = false }
        )
    }
}

/**
 * M19.5: Glyphe exakt mittig in Button-Höhe (Devon-Befund zu ⏸/⇄/■).
 * fillMaxSize-Box als echtes Zentrum; lineHeight = fontSize und
 * includeFontPadding = false nehmen die asymmetrische Font-Leading weg.
 */
@Composable
private fun CenteredGlyph(
    glyph: String,
    color: Color = Color.Unspecified
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = glyph,
            fontSize = 15.sp,
            lineHeight = 15.sp,
            color = color,
            textAlign = TextAlign.Center,
            style = LocalTextStyle.current.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}
