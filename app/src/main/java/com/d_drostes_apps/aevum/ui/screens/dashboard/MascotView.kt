package com.d_drostes_apps.aevum.ui.screens.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.domain.mascot.MascotBehavior
import com.d_drostes_apps.aevum.domain.mascot.MascotReaction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** M19.5: Test-Tags für die Robolectric-Suite. */
internal const val MASCOT_TEST_TAG = "mascot-view"
internal const val MASCOT_SETTINGS_PREVIEW_TEST_TAG = "mascot-settings-preview"

// PAG-Palette (M19.6): exakt die Töne des Vorbild-Maskottchens.
private val PAG_INK = Color(0xFF1B2333)
private val PAG_EYE_WHITE = Color(0xFFF4F8FF)
private val PAG_PUPIL = Color(0xFF232B3A)
private val PAG_ANTENNA_LINE = Color(0xFF9FB0C6)
private val PAG_GOLD = Color(0xFFFFD166)
private val PAG_CHEEK = Color(0xFFFF8FA3)
private val PAG_ZZZ = Color(0xFFDEE7F2)

/**
 * M19.5/M19.6 — DAS MASKOTTCHEN, im Stil des PAG-Maskottchens
 * (Devon: „Halte dich vielleicht an das maskottchen aus PAG. Das fand ich
 * gut. Das einfach leicht personalisierbar. und ohne herzen bitte,
 * vielleicht mit sternchen oder so.").
 *
 * Anatomie (an [personal-ai-gateway/android/mascot] angelehnt, ohne Assets):
 *  - EIN runder Körper mit weichem Vertikal-Verlauf (kein Umriss),
 *    Bauchlicht-Oval unten — Farben aus [MascotRepository.COLOR_PALETTE],
 *    Vorgabe ist das PAG-Blau.
 *  - Antenne, die sich in Blickrichtung neigt und leicht nachfedert; die
 *    Spitze ist personalisierbar: Kugel (PAG-Original), Stern oder Blitz.
 *  - Große weiße Augen mit dunklen Pupillen, die der Blick-Wanderung
 *    folgen; Blinzeln schiebt das Lid zu (geschlossene Augen = ruhige
 *    Linie, wie im PAG-Original). Ab sehr guter Güte funkeln Extra-Glints.
 *  - Kleiner Smile-Mund, dessen Breite/Tiefe mit [smileLevel] wächst
 *    („je besser die güte, desto mehr soll das maskottchen lächeln");
 *    im Schlaf ein kleiner Mund-Punkt.
 *  - Rosa Bäckchen (abschaltbar), weicher Bodenschatten, langsames,
 *    subtiles Atmen.
 *
 * Verhalten: schaut umher (langsame Blick-Wanderung), blinzelt (~alle
 * 4,3 s); [sleeping] (22–6 Uhr ohne Aufzeichnung): geschlossene Augen +
 * aufsteigende „z"; Tippen löste drei zyklische Reaktionen aus —
 * Sternchen-Hüpfer (STAR_HOP), Wackel-Funkeln (WIGGLE_SPARKLES),
 * Bäckchen-Pop (BLUSH_POP) — „ohne herzen".
 */
@Composable
internal fun MascotView(
    config: MascotRepository.MascotConfig,
    smileLevel: Float,
    sleeping: Boolean,
    modifier: Modifier = Modifier,
    mascotSize: Dp = 96.dp,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    testTag: String = MASCOT_TEST_TAG
) {
    var tapCount by remember { mutableIntStateOf(0) }
    val reaction = MascotBehavior.reactionFor(tapCount)
    val reactionProgress = remember { Animatable(0f) }
    LaunchedEffect(tapCount) {
        if (tapCount > 0) {
            reactionProgress.snapTo(0f)
            reactionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(1700, easing = LinearEasing)
            )
        }
    }
    val smile by animateFloatAsState(
        targetValue = smileLevel.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 120f),
        label = "mascotSmile"
    )

    // Dauer-Animationen: Atmen, Blinzeln, Umherschauen, Zzz.
    // Atem-Tempo wie im PAG-Original: sehr langsam und subtil
    // (sin-Periode ~10,7 s wach / ~16,3 s schlafend).
    val bodyT = rememberInfiniteTransition(label = "mascotBody")
    val breath by bodyT.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (sleeping) 8200 else 5300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mascotBreath"
    )
    val lookX by bodyT.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mascotLookX"
    )
    val lookY by bodyT.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mascotLookY"
    )
    val blinkPhase by bodyT.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4300, easing = LinearEasing), RepeatMode.Restart),
        label = "mascotBlink"
    )
    val zzzPhase by bodyT.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2700, easing = LinearEasing), RepeatMode.Restart),
        label = "mascotZzz"
    )
    // Blinzeln: sehr kurzes Zukneifen im späten Zyklus.
    val blink = when {
        sleeping -> 1f
        blinkPhase < 0.88f -> 0f
        blinkPhase < 0.93f -> 1f
        else -> 0f
    }

    val bodyColor = MascotRepository.colorFor(config.colorId)
    val bodyBottomColor = lerpMascotColor(bodyColor, PAG_PUPIL, 0.34f)
    val scaleFactor = mascotSize.value / 96f

    val p = reactionProgress.value
    val activeReaction = if (p < 1f) reaction else null
    val sleepingLabel = stringResource(R.string.mascot_state_sleeping)
    val awakeLabel = stringResource(R.string.mascot_state_awake)
    val resolvedCd = contentDescription ?: stringResource(R.string.mascot_cd)

    Box(
        modifier = modifier
            .size(mascotSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                tapCount += 1
                onClick?.invoke()
            }
            .semantics {
                this.contentDescription = resolvedCd
                stateDescription = if (sleeping) sleepingLabel else awakeLabel
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val baseCy = h * 0.545f
            val r0 = w * 0.31f
            val breathScale = 1f + 0.014f * breath
            val popScale = if (activeReaction == MascotReaction.BLUSH_POP) {
                1f + 0.06f * sin(PI * p).toFloat()
            } else 1f
            val r = r0 * breathScale * popScale
            val hop = if (activeReaction == MascotReaction.STAR_HOP) {
                -sin(PI * p).toFloat() * r0 * 0.92f
            } else 0f
            val wiggle = if (activeReaction == MascotReaction.WIGGLE_SPARKLES) {
                sin(p * PI.toFloat() * 3f) * (1f - p) * 8f
            } else 0f
            val cy = baseCy + hop

            // Bodenschatten — bleibt am Boden stehen (PAG-Stil).
            drawOval(
                color = Color(0x40000000),
                topLeft = Offset(cx - r0 * 1.02f, baseCy + r0 * 0.86f),
                size = Size(r0 * 2.04f, r0 * 0.40f)
            )

            rotate(degrees = wiggle, pivot = Offset(cx, cy + r)) {
                val lookVX = if (sleeping) 0f else (lookX - 0.5f) * 2f
                val lookVY = if (sleeping) 0f else (lookY - 0.5f) * 2f

                // Antenne (hinter dem Körper; neigt sich in Blickrichtung,
                // die Spitze eilt leicht nach — wie im PAG-Original).
                val antennaBase = Offset(cx + lookVX * r * 0.10f, cy - r * 0.98f)
                val antennaTip = Offset(
                    cx + lookVX * r * 0.55f,
                    cy - r * 1.42f + lookVY * r * 0.10f + breath * r * 0.012f
                )
                val antenna = Path().apply {
                    moveTo(antennaBase.x, antennaBase.y)
                    quadraticBezierTo(
                        cx + lookVX * r * 0.22f, cy - r * 1.26f,
                        antennaTip.x, antennaTip.y
                    )
                }
                drawPath(
                    path = antenna,
                    color = PAG_ANTENNA_LINE,
                    style = Stroke(
                        width = (w * 0.008f).coerceAtLeast(1.1.dp.toPx()),
                        cap = StrokeCap.Round
                    )
                )
                drawAntennaTip(config.antennaStyle, antennaTip, r)

                // Körper: weicher Vertikal-Verlauf, bewusst OHNE Umriss
                // (PAG-Stil wirkt flach und weich).
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(bodyColor, bodyBottomColor),
                        startY = cy - r,
                        endY = cy + r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )
                // Bauchlicht.
                drawOval(
                    color = Color(0x26BFE3FF),
                    topLeft = Offset(cx - r * 0.55f, cy + r * 0.10f),
                    size = Size(r * 1.10f, r * 0.72f)
                )

                // Gesicht (PAG-Proportionen: große Augen, kleine Kurve).
                val eyeDX = r * 0.42f
                val eyeY = cy - r * 0.10f
                val eyeR = r * 0.30f
                val blinkEff = if (sleeping) 1f else blink
                val eyeOpen = 1f - blinkEff * 0.97f
                val happySquint = activeReaction == MascotReaction.BLUSH_POP && p in 0.12f..0.78f
                val pupilX = lookVX * eyeR * 0.36f
                val pupilY = lookVY * eyeR * 0.34f

                listOf(-1f, 1f).forEach { side ->
                    val ex = cx + side * eyeDX
                    if (blinkEff > 0.9f || happySquint) {
                        // Geschlossene / Freuden-Augen: ruhige Linie.
                        drawLine(
                            color = PAG_INK,
                            start = Offset(ex - eyeR * 0.62f, eyeY),
                            end = Offset(ex + eyeR * 0.62f, eyeY),
                            strokeWidth = (w * 0.010f).coerceAtLeast(1.2.dp.toPx()),
                            cap = StrokeCap.Round
                        )
                    } else {
                        drawOval(
                            color = PAG_EYE_WHITE,
                            topLeft = Offset(ex - eyeR, eyeY - eyeR * eyeOpen),
                            size = Size(eyeR * 2f, eyeR * 2f * eyeOpen)
                        )
                        if (blinkEff < 0.75f) {
                            val pupilR = eyeR * 0.46f * (1f - blinkEff * 0.5f)
                            val px = ex + pupilX
                            val py = eyeY + pupilY
                            drawCircle(PAG_PUPIL, pupilR, Offset(px, py))
                            drawCircle(
                                Color(0xE6FFFFFF), pupilR * 0.30f,
                                Offset(px - pupilR * 0.30f, py - pupilR * 0.32f)
                            )
                            // Extra-Funkeln bei sehr guter Güte (und beim
                            // Bäckchen-Pop) — Devons „je besser, desto
                            // fröhlicher".
                            if (smile >= 0.85f || activeReaction == MascotReaction.BLUSH_POP) {
                                drawCircle(
                                    Color.White.copy(alpha = 0.40f + 0.35f * breath),
                                    pupilR * 0.22f,
                                    Offset(px + pupilR * 0.55f, py + pupilR * 0.50f)
                                )
                            }
                        }
                    }
                }

                // Wangen (optional): PAG-Rosa; beim Bäckchen-Pop glühen sie.
                if (config.cheeks && !sleeping) {
                    val blushBoost = if (activeReaction == MascotReaction.BLUSH_POP) {
                        0.45f * sin(PI * p).toFloat()
                    } else 0f
                    val cheekAlpha = (0.30f + blushBoost).coerceAtMost(0.85f)
                    listOf(-1f, 1f).forEach { side ->
                        drawCircle(
                            color = PAG_CHEEK.copy(alpha = cheekAlpha),
                            radius = r * 0.125f,
                            center = Offset(cx + side * r * 0.64f, eyeY + r * 0.44f)
                        )
                    }
                }

                // Mund: kleiner Smile, wächst mit der Güte; im Schlaf ein
                // ruhiger Mund-Punkt (PAG-Original).
                val my = cy + r * 0.46f
                if (sleeping) {
                    drawCircle(PAG_INK, r * 0.065f, Offset(cx, my))
                } else {
                    val hw = r * (0.13f + 0.16f * smile)
                    val depth = r * (0.045f + 0.075f * smile)
                    val mouth = Path().apply {
                        moveTo(cx - hw, my - r * 0.02f)
                        quadraticBezierTo(cx, my + depth * 2f, cx + hw, my - r * 0.02f)
                    }
                    drawPath(
                        path = mouth,
                        color = PAG_INK,
                        style = Stroke(
                            width = (w * 0.010f).coerceAtLeast(1.2.dp.toPx()),
                            cap = StrokeCap.Round
                        )
                    )
                }
            }
        }

        // „z z z" — nur im Schlaf (weich ein-/ausblenden beim Wechsel);
        // steigen rechts vom Kopf auf und verblassen (PAG-Original).
        val zzzAlpha by animateFloatAsState(
            targetValue = if (sleeping) 1f else 0f,
            animationSpec = tween(500),
            label = "mascotZzzAlpha"
        )
        if (zzzAlpha > 0.01f) {
            val density = LocalDensity.current
            val px = with(density) { mascotSize.toPx() }
            val rPx = px * 0.31f
            val zFontSize = with(density) { (px * 0.070f).toSp() }
            listOf(0, 1, 2).forEach { i ->
                val t = ((zzzPhase * 2.7f) + i * 0.9f) % 2.7f
                val frac = t / 2.7f
                val alpha = (1f - frac) * 0.78f * zzzAlpha
                if (alpha > 0.02f) {
                    Text(
                        text = "z",
                        fontSize = zFontSize,
                        fontWeight = FontWeight.Bold,
                        color = PAG_ZZZ.copy(alpha = alpha),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationX = rPx * (0.5f + frac * 0.5f)
                                translationY = px * 0.045f - rPx * (0.85f + 0.85f * frac)
                            }
                    )
                }
            }
        }

        // Reaktions-Partikel (Sternchen / Funkelsterne) — laufen mit p aus.
        if (activeReaction != null && p > 0f && p < 1f) {
            if (activeReaction == MascotReaction.STAR_HOP) {
                listOf(0, 1, 2).forEach { i ->
                    // Staffelung kompakt, damit alle drei Sternchen klar
                    // sichtbar sind (Lesson aus M19.5).
                    val prog = ((p - i * 0.06f) / 0.94f).coerceIn(0f, 1f)
                    val visible = p >= i * 0.06f
                    val alpha = if (visible) sin(PI * prog).toFloat() * 0.95f else 0f
                    if (alpha > 0.02f) {
                        Text(
                            text = "\u2605",
                            fontSize = ((13f + 4f * prog) * scaleFactor).sp,
                            color = PAG_GOLD.copy(alpha = alpha),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    val px = mascotSize.toPx()
                                    translationX = (i - 1) * px * 0.36f +
                                        sin(prog * PI.toFloat() * 2f) * px * 0.03f
                                    translationY = -px * (0.34f + 0.36f * prog)
                                }
                        )
                    }
                }
            } else if (activeReaction == MascotReaction.WIGGLE_SPARKLES) {
                listOf(0, 1, 2).forEach { i ->
                    val alpha = sin(PI * p).toFloat() * 0.85f
                    val angle = (i * 120f + p * 180f) * PI.toFloat() / 180f
                    if (alpha > 0.02f) {
                        Text(
                            text = "\u2726",
                            fontSize = ((10f + 3f * p) * scaleFactor).sp,
                            color = PAG_GOLD.copy(alpha = alpha),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    val px = mascotSize.toPx()
                                    val radius = px * (0.10f + 0.30f * p)
                                    translationX = radius * cos(angle)
                                    translationY = radius * sin(angle) - px * 0.08f
                                    rotationZ = 14f
                                }
                        )
                    }
                }
            }
        }
    }
}

/**
 * M19.6: Antennen-Spitze je Personalisierung — Kugel (PAG-Original),
 * Sternchen oder Blitz, jeweils mit warmem Gold-Glow.
 */
private fun DrawScope.drawAntennaTip(style: String, tip: Offset, r: Float) {
    when (style) {
        MascotRepository.ANTENNA_STAR -> {
            drawCircle(PAG_GOLD.copy(alpha = 0.18f), r * 0.17f, tip)
            val star = starPath(tip, r * 0.135f)
            drawPath(star, PAG_GOLD)
            drawPath(
                star,
                Color.White.copy(alpha = 0.40f),
                style = Stroke(width = (r * 0.012f).coerceAtLeast(1f))
            )
        }
        MascotRepository.ANTENNA_BOLT -> {
            drawCircle(PAG_GOLD.copy(alpha = 0.16f), r * 0.17f, tip)
            drawPath(boltPath(tip, r * 1.10f), PAG_GOLD)
        }
        else -> { // ANTENNA_BALL — exakt wie im PAG-Original.
            drawCircle(PAG_GOLD.copy(alpha = 0.20f), r * 0.133f, tip)
            drawCircle(PAG_GOLD, r * 0.070f, tip)
            drawCircle(
                Color.White.copy(alpha = 0.75f),
                r * 0.022f,
                Offset(tip.x - r * 0.025f, tip.y - r * 0.030f)
            )
        }
    }
}

/** Fünfzackiger Stern um [center] (Spitze oben). */
private fun starPath(center: Offset, radius: Float): Path {
    val path = Path()
    val points = 5
    for (i in 0 until points * 2) {
        val rad = if (i % 2 == 0) radius else radius * 0.45f
        val angle = (-90.0 + i * 180.0 / points) * PI / 180.0
        val x = center.x + (rad * cos(angle)).toFloat()
        val y = center.y + (rad * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Kompakte Blitz-Silhouette um [center] — Kantenlänge skaliert mit [unit]. */
private fun boltPath(center: Offset, unit: Float): Path {
    val points = listOf(
        -0.025f to -0.20f,
        0.100f to -0.20f,
        0.020f to -0.03f,
        0.125f to -0.03f,
        -0.025f to 0.20f,
        0.0125f to 0.02f,
        -0.075f to 0.02f
    )
    val path = Path()
    points.forEachIndexed { i, (dx, dy) ->
        val x = center.x + dx * unit
        val y = center.y + dy * unit
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Linearer Farb-Mix (lokal — DashboardHero hat denselben als private). */
private fun lerpMascotColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)
