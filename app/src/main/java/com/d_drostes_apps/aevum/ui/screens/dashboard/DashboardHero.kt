package com.d_drostes_apps.aevum.ui.screens.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.domain.mascot.MascotBehavior
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.ui.components.shimmering
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * M19.4 — DASHBOARD-HERO 2.0 („Tag-Cockpit", nach Devons Skizze).
 * M19.5 — gepinnt + Maskottchen + kompakter (Devon-Feedback).
 *
 * Aufbau (Top → Bottom):
 *  1. ‹ Heute › — zentrierte Tages-Navigation (Kalender-Popup bleibt).
 *  2. Drei versetzte Glas-Kacheln: Erfasst | Schlaf | Bildschirm —
 *     M19.5 KOMPAKTER (kleineres Icon, engere Abstände; gilt bewusst
 *     auch ohne Maskottchen-Modus), die mittlere sitzt tiefer.
 *  3. Zentrales 180°-Gauge: roter→grüner Bogen (fester Skala-Farbraum
 *     wie in der Skizze), leuchtender Marker + Nadel am Wert, ✎-Badge
 *     bei Override, Tap öffnet den Güte-Dialog, rechts der Zonen-Chip.
 *     M19.5: GRÖSSER (248×148 statt 216×126) und mit Maskottchen-Modus —
 *     wenn aktiviert, wohnt das Wesen (MascotView) genau dort, wo sonst
 *     die Güte-Zahl steht: Es lächelt mit der Güte, atmet, blinzelt,
 *     schaut umher, schläft nachts (22–6 Uhr, außer es läuft eine
 *     Aufzeichnung) und reagiert süß auf Tippen; die kleine Zahl-Zeile
 *     darunter bleibt als Datenanker.
 *  4. Aufnahme-Bereich DIREKT unter dem Gauge (Devon: „unter der anzeige
 *     der güte … der kasten … der mit der animation die aktuelle
 *     aufzeichnung anzeigt"): Live → der Blubber-Banner (Timer +
 *     Pause/Wechsel/Stop); sonst die ruhige Bereitschafts-Zeile mit
 *     Tagesfortschritt. Weich animiert beim Start/Ende einer Aufnahme.
 *  5. Tagesleiste: 24h-Ribbon mit Segment-Blöcken, Stundenlabels
 *     0/6/12/18/24, pulsierender Jetzt-Marker. Tap → Timeline.
 *
 * ENTFERNT in M19.5: die Headline-Zeile (Devon: „das kann komplett weg")
 * und der frühere Status-Balken am Hero-Ende (Aufnahme sitzt jetzt unter
 * dem Gauge; der Collapse-Fade ist dem Pinning gewichen).
 *
 * Gestaltungs-Entscheidungen (bewusst, mit Alternative im Kopf):
 *  - Keine neue Grafik-Bibliothek: Gauge/Ribbon/Maskottchen sind
 *    Canvas-Bordmittel, passend zum Offline-Minimal-Dependency-Pfad.
 *  - Gauge mit FESTEM Rot→Grün-Farbraum (statt Farbe=Wert wie beim alten
 *    QualityRing): Die Skizze zeigt eine Skala, auf der der Marker steht —
 *    das liest sich wie ein Messgerät und bleibt auch bei 0/100 lesbar.
 *  - Farben strikt aus der App-Identität (M19.2: Eisblau/Feuerrot/Gold +
 *    Kategoriefarben).
 *  - Schimmer dosiert: Kacheln (desynchronisierte Perioden), Banner —
 *    kein Dauer-Glitzer.
 */

/**
 * M19.4: Überstand der Deko-Fläche UNTER dem Hero. Das Sheet (eigene,
 * abgerundete Karte im LazyColumn) startet direkt unter dem Hero — durch
 * dessen abgerundete Ecken und den Überstand scheint der Farbbereich
 * hindurch und das Sheet wirkt wie eine Karte ÜBER dem Hero
 * (Aufbau des Referenzbilds).
 */
internal val DashboardHeroPeek = 44.dp

/** Fallback-Höhe, bevor die erste Messung da ist (nur 1 Frame). */
internal val DashboardHeroFallbackHeight = 500.dp

// M19.4: Test-Tags für die Robolectric-UI-Suite.
internal const val DASHBOARD_HERO_GAUGE_TEST_TAG = "dashboard-hero-gauge"
internal const val DASHBOARD_HERO_TIMELINE_TEST_TAG = "dashboard-hero-timeline"
// M19.5: Der frühere Status-Balken ist jetzt der Aufnahme-Slot unter dem
// Gauge (Live-Banner ↔ Bereitschaftszeile) + das Maskottchen im Gauge.
internal const val DASHBOARD_HERO_RECORDING_SLOT_TEST_TAG = "dashboard-hero-recording-slot"
internal const val DASHBOARD_HERO_MASCOT_TEST_TAG = "dashboard-hero-mascot"
internal const val DASHBOARD_HERO_ZONE_TEST_TAG = "dashboard-hero-zone"
internal const val DASHBOARD_HERO_TILE_CAPTURED_TEST_TAG = "dashboard-hero-tile-captured"
internal const val DASHBOARD_HERO_TILE_SLEEP_TEST_TAG = "dashboard-hero-tile-sleep"
internal const val DASHBOARD_HERO_TILE_SCREEN_TEST_TAG = "dashboard-hero-tile-screen"

/**
 * M19.4: Reine Arithmetik des Heros — bewusst ohne Android-Abhängigkeit,
 * damit die JVM-Suite Parallax- und Gauge-Geometrie direkt prüfen kann
 * (gleiches Muster wie PmShimmer).
 */
internal object DashboardHeroMetrics {

    /** 0..100 → 0..1 (geklemmt). */
    fun gaugeSweepFraction(score: Int): Float =
        score.coerceIn(0, 100) / 100f

    /**
     * Winkel des Markers auf dem 180°-Bogen: 180° = linkes Ende (Score 0),
     * 360° = rechtes Ende (Score 100). Wird in Tests und im Canvas genutzt.
     */
    fun gaugeNeedleAngleDegrees(score: Int): Float =
        180f + 180f * gaugeSweepFraction(score)

    /**
     * M19.5-PINNING: Der Hero steht beim Scrollen STILL. Die LazyColumn
     * verschiebt ihn um -offset nach oben; diese Funktion liefert die
     * exakte Gegen-Verschiebung (+offset) für den graphicsLayer, sodass
     * netto 0 bleibt — nur das Sheet rutscht darüber („der komplette
     * obere bereich bleibt stehen, der untere rutscht darüber").
     * Sobald Item 0 komplett durchgescrollt ist (belowHero), wird die
     * volle Höhe kompensiert (der Hero ist dann ohnedies überdeckt).
     */
    fun heroStickyTranslation(offsetPx: Int, heroHeightPx: Int, belowHero: Boolean): Int {
        if (belowHero) return heroHeightPx.coerceAtLeast(0)
        return offsetPx.coerceAtLeast(0)
    }

    /** Tagesfortschritt (0..1) → Prozentanzeige (0..100). */
    fun dayProgressPercent(dayProgress: Float): Int =
        (dayProgress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
}

/**
 * M19.4: Deko-Hintergrund des Heros — eigene, fest gepinnte Ebene HINTER
 * der LazyColumn. Die Höhe kommt von außen (gemessene Hero-Item-Höhe +
 * [DashboardHeroPeek]), damit der Farbbereich unter dem Sheet-Rand
 * hervorschimmert (Parallax-Tiefe).
 */
@Composable
internal fun DashboardHeroBackground(
    modifier: Modifier = Modifier,
    height: Dp = DashboardHeroFallbackHeight
) {
    HeroBackground(modifier = modifier.fillMaxWidth().height(height))
}

/**
 * M19.4: Hero-Inhalt (Item 0 der LazyColumn — klickbar, scrollt mit
 * Parallax über die Deko-Fläche und unter das Sheet).
 */
@Composable
internal fun DashboardHeroContent(
    state: DashboardUiState,
    liveState: LiveActivityState,
    nowMs: Long,
    currentZone: CurrentZoneProvider.ZoneInfo?,
    mascot: MascotRepository.MascotConfig = MascotRepository.MascotConfig(),
    onQualityClick: () -> Unit,
    onOpenTimeline: () -> Unit,
    onPauseLive: () -> Unit = {},
    onResumeLive: () -> Unit = {},
    onStopLive: () -> Unit = {},
    onSwitchLive: (String, String?) -> Unit = { _, _ -> },
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onResetToToday: () -> Unit,
    onDateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLive = liveState is LiveActivityState.Running || liveState is LiveActivityState.Paused
    // M19.5: Nachtschlaf des Maskottchens (22–6 Uhr) — NUR für „Heute"
    // (beim Blättern in alte Tage gibt es kein Jetzt), und eine laufende
    // Aufzeichnung übersteuert den Schlaf jederzeit live (wechselnd mit
    // Start/Stop der Session).
    val isToday = state.displayedDate == java.time.LocalDate.now()
    val mascotSleeping = isToday && MascotBehavior.shouldSleep(
        hourOfDay = MascotBehavior.hourOfDay(state.currentMinute),
        isRecording = isLive
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = AevumSpacing.md)
            .padding(top = 6.dp, bottom = AevumSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        // 1) Kopf: zentrierte Tages-Navigation (‹ Heute ›).
        Box(modifier = Modifier.fillMaxWidth()) {
            HeroDayNavigation(
                displayedDate = state.displayedDate,
                onPrevious = onPreviousDay,
                onNext = onNextDay,
                onReset = onResetToToday,
                onDateClick = onDateClick,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 2) Die drei fundamentalen Zeit-Blöcke — versetzt (Skizze:
        // die mittlere Kachel sitzt tiefer), als Glas-Kacheln.
        // M19.5: KOMPAKTER (Devon: „von der höhe ein wenig reduzieren,
        // das icon kann [kleiner], abstände einen ganz klein wenig
        // geringer") — gilt auch ohne Maskottchen-Modus.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            HeroMetricTile(
                icon = "⏱️",
                value = formatHours(state.totalTrackedMs),
                label = stringResource(R.string.common_captured),
                color = MaterialTheme.colorScheme.primary,
                fraction = (state.totalTrackedMs / 86_400_000f).coerceIn(0f, 1f),
                animatedValueMs = state.totalTrackedMs,
                delayMs = 0,
                shimmerPeriodMs = 3600,
                modifier = Modifier
                    .weight(1f)
                    .testTag(DASHBOARD_HERO_TILE_CAPTURED_TEST_TAG)
            )
            HeroMetricTile(
                icon = "🌙",
                value = if (state.lastSleepDurationMs > 0) formatHours(state.lastSleepDurationMs) else "—",
                label = stringResource(R.string.common_sleep),
                color = Color(0xFF6366F1),
                fraction = (state.lastSleepDurationMs / 86_400_000f).coerceIn(0f, 1f),
                animatedValueMs = state.lastSleepDurationMs,
                delayMs = 90,
                shimmerPeriodMs = 4600,
                // Skizzen-Detail: die mittlere Kachel sitzt tiefer.
                modifier = Modifier
                    .weight(1f)
                    .offset(y = 8.dp)
                    .testTag(DASHBOARD_HERO_TILE_SLEEP_TEST_TAG)
            )
            HeroMetricTile(
                icon = "📱",
                value = state.digitalScreenTimeFormatted,
                label = stringResource(R.string.dashboard_screen),
                color = MaterialTheme.colorScheme.tertiary,
                fraction = (state.digitalScreenTimeMs / 86_400_000f).coerceIn(0f, 1f),
                animatedValueMs = state.digitalScreenTimeMs,
                delayMs = 180,
                shimmerPeriodMs = 5800,
                modifier = Modifier
                    .weight(1f)
                    .testTag(DASHBOARD_HERO_TILE_SCREEN_TEST_TAG)
            )
        }

        // 3) Zentrum: Güte-Gauge (M19.5 größer; mit Maskottchen-Modus
        // wohnt das Wesen genau an der Stelle der Güte-Zahl) + Zonen-Chip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .height(148.dp)
        ) {
            HeroGauge(
                score = state.qualityScore,
                overrideBadge = if (state.hasDayQualityOverride) "✎" else null,
                mascot = mascot,
                mascotSleeping = mascotSleeping,
                onClick = onQualityClick,
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag(DASHBOARD_HERO_GAUGE_TEST_TAG)
            )
            HeroZoneChip(
                zone = currentZone,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = 2.dp)
                    .testTag(DASHBOARD_HERO_ZONE_TEST_TAG)
            )
        }

        // 4) Aufnahme-Bereich — DIREKT unter dem Gauge (Devon: „unter der
        // anzeige der güte … der kasten … der mit der animation die
        // aktuelle aufzeichnung anzeigt"). Live → Blubber-Banner mit
        // Timer + Pause/Wechsel/Stop; sonst die ruhige Bereitschafts-Zeile.
        // Weich animiert beim Start/Ende einer Aufnahme. Die frühere
        // Headline-Zeile ist komplett entfernt (Devon: „das kann weg").
        AnimatedContent(
            targetState = isLive,
            transitionSpec = {
                (
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(260)
                    ) + fadeIn(animationSpec = tween(260))
                    ) togetherWith (
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(220)
                    ) + fadeOut(animationSpec = tween(200))
                    )
            },
            label = "heroRecordingSlot",
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DASHBOARD_HERO_RECORDING_SLOT_TEST_TAG)
        ) { recording ->
            if (recording) {
                LiveActivityBanner(
                    state = liveState,
                    nowMs = nowMs,
                    onPause = onPauseLive,
                    onResume = onResumeLive,
                    onStop = onStopLive,
                    onSwitch = onSwitchLive,
                    activityTypes = state.activityTypes
                )
            } else {
                ReadyStatusContent(
                    dayProgressPercent = DashboardHeroMetrics.dayProgressPercent(state.dayProgress)
                )
            }
        }

        // 5) Tagesleiste (24h-Ribbon mit Stundenlabels), Tap → Timeline.
        HeroDayFlowStrip(
            segments = state.flowSegments,
            currentMinute = state.currentMinute,
            emptyText = stringResource(R.string.dashboard_flow_empty),
            onClick = onOpenTimeline,
            modifier = Modifier.testTag(DASHBOARD_HERO_TIMELINE_TEST_TAG)
        )
    }
}

/**
 * M19.4: Animierter Hero-Hintergrund — wandernder Gradient (Aevum-
 * Palette) + drei driftende Orbs (Eisblau/Feuerrot/Gold statt der alten
 * Lila/Mint-Töne, damit die Fläche zur M19.2-Identität passt).
 */
@Composable
private fun HeroBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "heroBg")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10_000), RepeatMode.Reverse),
        label = "heroGradientT"
    )
    val orb1X by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(22_000), RepeatMode.Reverse),
        label = "orb1X"
    )
    val orb1Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000), RepeatMode.Reverse),
        label = "orb1Y"
    )
    val orb2X by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(19_000), RepeatMode.Reverse),
        label = "orb2X"
    )
    val orb2Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000), RepeatMode.Reverse),
        label = "orb2Y"
    )
    val orb3X by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(25_000), RepeatMode.Reverse),
        label = "orb3X"
    )
    val orb3Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(21_000), RepeatMode.Reverse),
        label = "orb3Y"
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary

    // M19.4: Vier Stopps statt hartem Ausblenden — die Farbe bleibt bis in
    // den Überstand UNTER dem Hero erhalten (DashboardHeroPeek) und wird
    // durch die abgerundeten Sheet-Ecken sichtbar; erst die letzte
    // Strecke blendet weich aus. Farbwanderung: Eisblau → Gold → Feuerrot.
    // M19.5: Tail-Stopp von 0.92 auf 0.965 gezogen — der Hero ist höher
    // geworden (Banner im Hero), das sichtbare Überstands-Band liegt
    // relativ zum Gesamtverlauf weiter unten und lag sonst schon im
    // Ausblend-Bereich (Deko schimmerte kaum noch durch).
    val topColor = lerpColor(primary.copy(alpha = 0.30f), tertiary.copy(alpha = 0.26f), t)
    val midColor = lerpColor(secondary.copy(alpha = 0.18f), primary.copy(alpha = 0.15f), t)
    val tailColor = lerpColor(primary.copy(alpha = 0.22f), tertiary.copy(alpha = 0.18f), t)

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                0f to topColor,
                0.55f to midColor,
                // Farbe bleibt bis in den Überstand unter dem Hero
                // erhalten (sichtbar neben den Sheet-Ecken), erst das
                // letzte Stück (M19.5: 3,5 %) blendet weich aus.
                0.965f to tailColor,
                1f to Color.Transparent
            )
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Orb 1 — groß, oben rechts (Eisblau).
            drawCircle(
                color = primary.copy(alpha = 0.09f),
                radius = size.minDimension * 0.42f,
                center = Offset(
                    w * (0.78f + 0.14f * sin(orb1X * 6.28f)),
                    h * (0.10f + 0.20f * sin(orb1Y * 6.28f))
                )
            )
            // Orb 2 — mittel, unten links (Feuerrot).
            drawCircle(
                color = secondary.copy(alpha = 0.07f),
                radius = size.minDimension * 0.34f,
                center = Offset(
                    w * (0.10f + 0.16f * sin(orb2X * 5.2f)),
                    h * (0.72f + 0.18f * sin(orb2Y * 4.6f))
                )
            )
            // Orb 3 — klein, Mitte (Gold).
            drawCircle(
                color = tertiary.copy(alpha = 0.06f),
                radius = size.minDimension * 0.24f,
                center = Offset(
                    w * (0.50f + 0.22f * sin(orb3X * 3.8f)),
                    h * (0.42f + 0.24f * sin(orb3Y * 5.0f))
                )
            )
        }
    }
}

/** Lineare Farbinterpolation (für den wandernden Gradient). */
private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

/**
 * M19.4: 180°-Güte-Gauge (Skizzen-Zentrum).
 * M19.5: Größer (248×148) und mit Maskottchen-Modus — das Wesen wohnt
 * dort, wo sonst die große Güte-Zahl steht (Devon: „dort erscheinen, wo
 * aktuell die zahl der güte ist"), mit kleiner Zahl-Zeile darunter.
 *
 * Roter→gelber→grüner Bogen als FESTE Skala (sweepGradient auf den
 * absoluten Winkelpositionen 180°/270°/360°), animierter Füll-Sweep,
 * leuchtender End-Marker mit Nadel (Skizzen-Pfeil) und Puls-Glow.
 */
@Composable
private fun HeroGauge(
    score: Int,
    overrideBadge: String?,
    mascot: MascotRepository.MascotConfig,
    mascotSleeping: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sweepFraction by animateFloatAsState(
        targetValue = DashboardHeroMetrics.gaugeSweepFraction(score),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 140f),
        label = "heroGaugeSweep"
    )
    val pulse = rememberInfiniteTransition(label = "heroGaugePulse")
    val glow by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "heroGaugeGlow"
    )
    // M19.4: Theme-adaptive Ableitungen — Dunkel ist der Hauptmodus, aber
    // im hellen Theme brauchen Grün/Gelb mehr Tiefe (Kontrast auf Weiß).
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scoreColor = if (darkTheme) {
        positivityColor(score)
    } else {
        lerpColor(positivityColor(score), Color.Black, 0.28f)
    }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (darkTheme) 0.16f else 0.30f
    )
    val cd = stringResource(R.string.dashboard_hero_quality_cd, score)

    Box(
        modifier = modifier
            .width(248.dp)
            .height(148.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) { contentDescription = cd }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 13.dp.toPx()
            val radius = (size.width - stroke) / 2f
            val cx = size.width / 2f
            val cy = radius + stroke / 2f + 2.dp.toPx()
            val arcTopLeft = Offset(cx - radius, cy - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Skalen-Track.
            drawArc(
                color = trackColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Füll-Bogen: feste Rot→Gelb→Grün-Skala an absoluten Winkeln.
            val sweep = 180f * sweepFraction
            if (sweep > 1f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0.5f to positivityColor(0),
                        0.75f to positivityColor(50),
                        1.0f to positivityColor(100),
                        center = Offset(cx, cy)
                    ),
                    startAngle = 180f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // Marker + Nadel (Skizzen-Pfeil am Bogen).
            val angleDeg = 180f + 180f * sweepFraction
            val angleRad = angleDeg * PI.toFloat() / 180f
            val markerX = cx + radius * cos(angleRad)
            val markerY = cy + radius * sin(angleRad)
            // Weicher Puls-Glow um den Marker.
            drawCircle(
                color = scoreColor.copy(alpha = 0.16f + 0.12f * glow),
                radius = stroke * 1.9f,
                center = Offset(markerX, markerY)
            )
            // Innerer Zeiger (zeigt zur Mitte — liest sich als Nadel).
            val needleInner = radius - stroke * 3.0f
            drawLine(
                color = scoreColor.copy(alpha = 0.85f),
                start = Offset(markerX, markerY),
                end = Offset(
                    cx + needleInner * cos(angleRad),
                    cy + needleInner * sin(angleRad)
                ),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Leuchtender End-Punkt.
            drawCircle(
                color = scoreColor,
                radius = stroke * 0.62f,
                center = Offset(markerX, markerY)
            )
        }

        if (mascot.enabled) {
            // M19.5: Maskottchen-Modus — das Wesen wohnt dort, wo sonst
            // die große Güte-Zahl steht; darunter eine kleine Zahl-Zeile
            // als Datenanker (Güte bleibt ablesbar + Tap-Fläche für den
            // Güte-Dialog).
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MascotView(
                    config = mascot,
                    smileLevel = MascotBehavior.smileLevel(score),
                    sleeping = mascotSleeping,
                    // M19.6: „Im dashboard kann das auch ein stück größer
                    // sein" — 108 statt 92 dp (passt samt Antenne in den
                    // vergrößerten Gauge-Halbkreis).
                    mascotSize = 108.dp,
                    contentDescription = stringResource(R.string.mascot_cd_score, score),
                    testTag = DASHBOARD_HERO_MASCOT_TEST_TAG
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        score.toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = scoreColor
                    )
                    if (overrideBadge != null) {
                        Text(
                            overrideBadge,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(R.string.dashboard_quality_label),
                        fontSize = 9.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Zahl + Label im offenen Halbkreis (klassischer Modus).
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        score.toString(),
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = scoreColor
                    )
                    if (overrideBadge != null) {
                        Text(
                            overrideBadge,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 3.dp)
                        )
                    }
                }
                Text(
                    stringResource(R.string.dashboard_quality_label),
                    fontSize = 9.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * M19.4: Zonen-Chip rechts neben dem Gauge (Skizzen-„Zuhause" mit
 * gelbem Punkt). Zeigt den Geofence-Namen oder „Abwesend"; Glas-Pille
 * mit weichem Wechsel bei Zonen-Updates.
 */
@Composable
private fun HeroZoneChip(
    zone: CurrentZoneProvider.ZoneInfo?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val hasZone = zone != null
    val zoneName = zone?.geofence?.name ?: stringResource(R.string.component_zone_absent)
    val accent = if (hasZone) colors.primary else colors.outline

    val shape = RoundedCornerShape(AevumRadius.full)
    Box(
        modifier = modifier
            .clip(shape)
            .background(accent.copy(alpha = if (hasZone) 0.14f else 0.10f))
            .border(1.dp, accent.copy(alpha = if (hasZone) 0.42f else 0.30f), shape)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (hasZone) Icons.Filled.Place else Icons.Filled.LocationOff,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = accent
            )
            AnimatedContent(
                targetState = zoneName,
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
                label = "heroZoneName"
            ) { name ->
                Text(
                    name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasZone) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * M19.4: Glas-Kachel (Erfasst/Schlaf/Bildschirm) — Farbverlauf,
 * Gradient-Rand, farbiger Glow-Schatten, Kaskaden-Einblendung,
 * Zähler-Animation, Mini-Balken und dezenter, desynchronisierter Schimmer.
 */
@Composable
private fun HeroMetricTile(
    icon: String,
    value: String,
    label: String,
    color: Color,
    fraction: Float,
    delayMs: Int,
    shimmerPeriodMs: Int,
    modifier: Modifier = Modifier,
    animatedValueMs: Long = 0L
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        visible = true
    }
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 220f),
        label = "heroTileAppear"
    )
    val animatedMs by animateFloatAsState(
        targetValue = animatedValueMs.toFloat(),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "heroTileValue"
    )
    val barFill by animateFloatAsState(
        targetValue = if (visible) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(700, delayMillis = delayMs, easing = FastOutSlowInEasing),
        label = "heroTileBar"
    )
    val displayValue = if (animatedValueMs > 0L) {
        val h = animatedMs.toLong() / 3_600_000
        val m = (animatedMs.toLong() % 3_600_000) / 60_000
        when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    } else value

    val shape = RoundedCornerShape(AevumRadius.xl)

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = 0.94f + 0.06f * appear
                scaleY = 0.94f + 0.06f * appear
                alpha = appear
            }
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = color.copy(alpha = 0.55f),
                spotColor = color.copy(alpha = 0.55f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        color.copy(alpha = 0.20f),
                        color.copy(alpha = 0.07f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        color.copy(alpha = 0.50f),
                        color.copy(alpha = 0.14f)
                    )
                ),
                shape = shape
            )
            .shimmering(
                periodMillis = shimmerPeriodMs,
                sheenColor = Color.White,
                intensity = 0.13f
            )
            .padding(horizontal = AevumSpacing.sm + 2.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // M19.5: Icon NEBEN dem Wert (Devon: „das icon kann neben der
        // zeit") — Wert + Label stehen rechts vom Icon, die Kachel wird
        // dadurch deutlich flacher (fast ein Drittel weniger Höhe).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(icon, fontSize = 13.sp)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    displayValue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    label,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(1.dp))
        // Mini-Balken: Anteil an 24h.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            if (barFill > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(barFill)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

@Composable
private fun ReadyStatusContent(
    dayProgressPercent: Int,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(AevumRadius.full)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f))
        )
        Text(
            stringResource(R.string.dashboard_recording_ready),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.dashboard_day_progress, dayProgressPercent),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * M19.4: Tagesleiste — 24h-Ribbon mit Segment-Blöcken, sanft gedimmter
 * Zukunft rechts vom Jetzt-Marker (pulsierender Glow-Punkt) und
 * Stundenlabels 0/6/12/18/24 darunter. Tap öffnet die Timeline.
 */
@Composable
private fun HeroDayFlowStrip(
    segments: List<DashboardFlowSegment>,
    currentMinute: Int,
    emptyText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stripHeight = 44.dp
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "heroFlowAppear"
    )
    val pulse = rememberInfiniteTransition(label = "heroFlowPulse")
    val nowPulse by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "heroFlowNowPulse"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val nowColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.background
    val cd = stringResource(R.string.dashboard_hero_timeline_cd)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripHeight)
                .clip(RoundedCornerShape(AevumRadius.full))
                .background(trackColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .semantics(mergeDescendants = true) { contentDescription = cd }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val h = size.height
                segments.forEach { seg ->
                    val start = (seg.startMinute / 1440f) * size.width
                    val end = (seg.endMinute / 1440f) * size.width
                    val width = ((end - start) * appear).coerceAtLeast(2f)
                    val segColor = com.d_drostes_apps.aevum.ui.components.categoryColor(seg.categoryName)
                    val alpha = when {
                        seg.isCandidate -> 0.28f
                        seg.isCurrent -> 0.95f
                        else -> 0.62f
                    }
                    drawRoundRect(
                        color = segColor.copy(alpha = alpha),
                        topLeft = Offset(start, 5f),
                        size = Size(width, h - 10f),
                        cornerRadius = CornerRadius(9f, 9f)
                    )
                }
                // Zukunft sanft abdunkeln ("wie viel des Tages bleibt").
                if (currentMinute in 0..1439 && segments.isNotEmpty()) {
                    val nowX = size.width * currentMinute / 1440f
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                dimColor.copy(alpha = 0f),
                                dimColor.copy(alpha = 0.42f)
                            ),
                            startX = nowX,
                            endX = (nowX + size.width * 0.10f).coerceAtMost(size.width)
                        ),
                        topLeft = Offset(nowX, 0f),
                        size = Size((size.width - nowX).coerceAtLeast(0f), h)
                    )
                }
                // Jetzt-Marker: Linie + pulsierender Glow-Punkt.
                if (currentMinute in 0..1440) {
                    val nowX = size.width * currentMinute / 1440f
                    drawCircle(
                        color = nowColor.copy(alpha = 0.20f * nowPulse),
                        radius = 9f,
                        center = Offset(nowX, h / 2f)
                    )
                    drawLine(
                        color = nowColor,
                        start = Offset(nowX, 2f),
                        end = Offset(nowX, h - 2f),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = nowColor,
                        radius = 3.5f,
                        center = Offset(nowX, 7f)
                    )
                }
            }
            if (segments.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        emptyText,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = AevumSpacing.md)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("0", "6", "12", "18", "24").forEach { hour ->
                Text(
                    hour,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** M18.60/M19.4: Tages-Navigation — Glas-Pille im Hero-Kopf. */
@Composable
private fun HeroDayNavigation(
    displayedDate: java.time.LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReset: () -> Unit,
    onDateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = java.time.LocalDate.now()
    val isToday = displayedDate == today
    val label = when {
        isToday -> stringResource(R.string.common_today)
        displayedDate == today.minusDays(1) -> stringResource(R.string.common_yesterday)
        else -> displayedDate.format(java.time.format.DateTimeFormatter.ofPattern("EEE, dd.MM."))
    }
    val shape = RoundedCornerShape(AevumRadius.full)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape
            )
            .padding(horizontal = AevumSpacing.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            "‹",
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onPrevious)
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(AevumRadius.sm))
                .clickable(onClick = onDateClick)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        if (!isToday) {
            Text(
                stringResource(R.string.common_today),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.sm))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Text(
            "›",
            fontSize = 17.sp,
            color = if (isToday) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = !isToday, onClick = onNext)
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
}

/** Helper: Stunden/Minuten-Format („5h 10m"). */
private fun formatHours(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
