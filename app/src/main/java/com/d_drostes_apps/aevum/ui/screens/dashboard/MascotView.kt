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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

/**
 * M19.5 — DAS MASKOTTCHEN (Devon: „so ein richtig süßes putziges wesen").
 *
 * Ein weiches Blob-Wesen, komplett in Canvas gezeichnet — keine Assets,
 * anpassbar über [MascotRepository.MascotConfig] (Farbe, Ohren, Bäckchen).
 *
 * Verhalten:
 *  - Lächeln wächst mit [smileLevel] (Tages-Güte, 0..1) — „je besser die
 *    güte, desto mehr soll das maskottchen lächeln". Ab sehr guter Güte
 *    funkeln kleine Glanzpunkte in den Augen.
 *  - Lebendig: atmet (Scale), blinzelt (alle ~4,3 s kurz), schaut umher
 *    (langsame Blick-Wanderung), Bäckchen bei aktivierter Option.
 *  - [sleeping] (22–6 Uhr ohne Aufzeichnung): geschlossene Augen, ruhigeres
 *    Atmen, kleine „z z z" schweben auf. Wechselt live mit dem Zustand.
 *  - Tippen: zyklische niedliche Reaktionen (Hüpfer+Herzen, Wackel+Funkeln,
 *    Bäckchen-Pop) — pure Auswahl in [MascotBehavior.reactionFor].
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
    val bodyT = rememberInfiniteTransition(label = "mascotBody")
    val breath by bodyT.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (sleeping) 3600 else 2600, easing = FastOutSlowInEasing),
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
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "mascotZzz"
    )
    // Blinzeln: sehr kurzes Zukneifen im späten Zyklus.
    val blink = when {
        sleeping -> 1f
        blinkPhase < 0.88f -> 0f
        blinkPhase < 0.93f -> 1f
        else -> 0f
    }

    val baseColor = MascotRepository.colorFor(config.colorId)
    val darkColor = lerpMascotColor(baseColor, Color(0xFF1B2433), 0.74f)
    val outlineColor = lerpMascotColor(baseColor, Color(0xFF1B2433), 0.35f)
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
            val breathScale = 1f + (if (sleeping) 0.022f else 0.03f) * breath
            val popScale = if (activeReaction == MascotReaction.BLUSH_POP) {
                1f + 0.07f * sin(PI * p).toFloat()
            } else 1f
            val hop = if (activeReaction == MascotReaction.BOUNCE_HEARTS) {
                -sin(PI * p).toFloat() * h * 0.16f
            } else 0f
            val wiggle = if (activeReaction == MascotReaction.WIGGLE_SPARKLES) {
                sin(p * PI.toFloat() * 3f) * (1f - p) * 9f
            } else 0f

            val groundPad = h * 0.07f
            val bottomY = h - groundPad
            val bodyW = w * 0.70f
            val bodyH = h * 0.56f * breathScale * popScale
            val cx = w / 2f
            val bodyCy = bottomY - bodyH / 2f + hop
            val bodyTopY = bodyCy - bodyH / 2f

            // Bodenschatten (bleibt am Boden, verkleinert sich beim Hüpfer).
            val hopFactor = (-hop / (h * 0.16f)).coerceIn(0f, 1f)
            val shadowW = bodyW * 0.84f * (1f - 0.15f * hopFactor)
            drawOval(
                color = Color.Black.copy(alpha = 0.26f * (1f - 0.55f * hopFactor)),
                topLeft = Offset(cx - shadowW / 2f, bottomY - h * 0.035f),
                size = Size(shadowW, h * 0.07f)
            )

            rotate(degrees = wiggle, pivot = Offset(cx, bottomY)) {
                // Ohren (hinter dem Körper).
                when (config.earStyle) {
                    MascotRepository.EARS_POINTY -> {
                        listOf(-1f, 1f).forEach { side ->
                            val ex = cx + side * bodyW * 0.30f
                            val ey = bodyTopY + h * 0.06f
                            val path = Path().apply {
                                moveTo(ex - w * 0.085f, ey)
                                lineTo(ex + w * 0.085f, ey)
                                lineTo(ex, ey - h * 0.20f)
                                close()
                            }
                            drawPath(path, color = baseColor)
                            drawPath(path, color = outlineColor, style = Stroke(width = 2.5.dp.toPx()))
                        }
                    }
                    MascotRepository.EARS_LONG -> {
                        listOf(-1f, 1f).forEach { side ->
                            val ex = cx + side * bodyW * 0.36f
                            val ey = bodyTopY - h * 0.06f
                            rotate(degrees = side * 20f, pivot = Offset(ex, ey)) {
                                val earTopLeft = Offset(ex - w * 0.075f, ey - h * 0.24f)
                                val earSize = Size(w * 0.15f, h * 0.32f)
                                val earRadius = CornerRadius(w * 0.075f, w * 0.075f)
                                drawRoundRect(
                                    color = baseColor,
                                    topLeft = earTopLeft,
                                    size = earSize,
                                    cornerRadius = earRadius
                                )
                                drawRoundRect(
                                    color = outlineColor,
                                    topLeft = earTopLeft,
                                    size = earSize,
                                    cornerRadius = earRadius,
                                    style = Stroke(width = 2.5.dp.toPx())
                                )
                            }
                        }
                    }
                    else -> { // EARS_ROUND
                        listOf(-1f, 1f).forEach { side ->
                            val ex = cx + side * bodyW * 0.30f
                            val ey = bodyTopY + h * 0.045f
                            drawCircle(color = baseColor, radius = w * 0.135f, center = Offset(ex, ey))
                            drawCircle(
                                color = outlineColor, radius = w * 0.135f,
                                center = Offset(ex, ey), style = Stroke(width = 2.5.dp.toPx())
                            )
                        }
                    }
                }

                // Körper (weicher Blob) + Umriss + Bäuchlein.
                val bodyTopLeft = Offset(cx - bodyW / 2f, bodyTopY)
                val bodySize = Size(bodyW, bodyH)
                drawOval(
                    brush = Brush.verticalGradient(
                        listOf(lerpMascotColor(baseColor, Color.White, 0.12f), baseColor)
                    ),
                    topLeft = bodyTopLeft,
                    size = bodySize
                )
                drawOval(
                    color = outlineColor,
                    topLeft = bodyTopLeft,
                    size = bodySize,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawOval(
                    color = lerpMascotColor(baseColor, Color.White, 0.45f).copy(alpha = 0.55f),
                    topLeft = Offset(cx - bodyW * 0.26f, bodyCy + bodyH * 0.02f),
                    size = Size(bodyW * 0.52f, bodyH * 0.38f)
                )

                // Gesicht.
                val eyeY = bodyCy - bodyH * 0.07f
                val eyeDX = bodyW * 0.195f
                val eyeW = w * 0.095f
                val eyeH = h * 0.095f * (1f - 0.92f * blink)
                val lookShiftX = if (sleeping) 0f else (lookX - 0.5f) * 2f * w * 0.018f
                val lookShiftY = if (sleeping) 0f else (lookY - 0.5f) * 2f * h * 0.012f
                val happyEyes = activeReaction == MascotReaction.BLUSH_POP && p in 0.12f..0.78f

                listOf(-1f, 1f).forEach { side ->
                    val ex = cx + side * eyeDX + lookShiftX
                    val ey = eyeY + lookShiftY
                    if (sleeping || happyEyes) {
                        // Geschlossene / Freuden-Augen: freundlicher Bogen.
                        drawArc(
                            color = darkColor,
                            startAngle = 200f,
                            sweepAngle = 140f,
                            useCenter = false,
                            topLeft = Offset(ex - eyeW * 0.62f, ey - eyeW * 0.45f),
                            size = Size(eyeW * 1.24f, eyeW * 0.9f),
                            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                        )
                    } else {
                        drawOval(
                            color = darkColor,
                            topLeft = Offset(ex - eyeW / 2f, ey - eyeH / 2f),
                            size = Size(eyeW, eyeH)
                        )
                        // Glanzpunkt (+ extra Funkeln bei sehr guter Güte).
                        drawCircle(
                            color = Color.White.copy(alpha = 0.92f),
                            radius = eyeW * 0.20f,
                            center = Offset(ex - eyeW * 0.20f, ey - eyeH * 0.22f)
                        )
                        if (smile >= 0.85f) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.45f + 0.35f * breath),
                                radius = eyeW * 0.11f,
                                center = Offset(ex + eyeW * 0.18f, ey + eyeH * 0.16f)
                            )
                        }
                    }
                }

                // Mund: Lächeln wächst mit der Güte.
                val my = bodyCy + bodyH * 0.16f
                val mw: Float
                val depth: Float
                if (sleeping) {
                    mw = w * 0.085f
                    depth = h * 0.018f
                } else {
                    mw = w * (0.11f + 0.15f * smile)
                    depth = h * (0.015f + 0.085f * smile)
                }
                val mouth = Path().apply {
                    moveTo(cx - mw / 2f, my)
                    quadraticBezierTo(cx, my + depth * 2f, cx + mw / 2f, my)
                }
                drawPath(
                    path = mouth,
                    color = darkColor,
                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                )

                // Bäckchen (optional): sanftes Rosa; beim Bäckchen-Pop glühen sie.
                if (config.cheeks) {
                    val blushBoost = if (activeReaction == MascotReaction.BLUSH_POP) {
                        0.45f * sin(PI * p).toFloat()
                    } else 0f
                    listOf(-1f, 1f).forEach { side ->
                        drawCircle(
                            color = Color(0xFFFB7185).copy(alpha = (0.38f + blushBoost).coerceAtMost(0.9f)),
                            radius = w * 0.055f,
                            center = Offset(cx + side * bodyW * 0.335f, my - h * 0.03f)
                        )
                    }
                }
            }
        }

        // „z z z" — nur im Schlaf (weich ein-/ausblenden beim Zustandswechsel).
        val zzzAlpha by animateFloatAsState(
            targetValue = if (sleeping) 1f else 0f,
            animationSpec = tween(500),
            label = "mascotZzzAlpha"
        )
        if (zzzAlpha > 0.01f) {
            listOf(0, 1, 2).forEach { i ->
                val phase = ((zzzPhase + i * 0.33f) % 1f)
                val alpha = sin(PI * phase).toFloat() * 0.85f * zzzAlpha
                if (alpha > 0.01f) {
                    Text(
                        text = "z",
                        fontSize = ((10f + 6f * phase) * scaleFactor).sp,
                        color = lerpMascotColor(baseColor, Color(0xFF1B2433), 0.45f).copy(alpha = alpha),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                val px = mascotSize.toPx()
                                translationX = px * (0.30f + i * 0.06f)
                                translationY = -px * (0.22f * phase + 0.12f * (i + 1))
                                rotationZ = 12f
                            }
                    )
                }
            }
        }

        // Reaktions-Partikel (Herzen / Funkelsterne) — laufen mit p aus.
        if (activeReaction != null && p > 0f && p < 1f) {
            if (activeReaction == MascotReaction.BOUNCE_HEARTS) {
                listOf(0, 1, 2).forEach { i ->
                    // M19.5: Staffelung kompakt (0.06 statt 0.12) — alle
                    // drei Herzchen sind gemeinsam klar sichtbar, statt
                    // dass das letzte (fast transparent über dem dunklen
                    // Grund) als „schwarzes" Herz wahrgenommen wird.
                    val prog = ((p - i * 0.06f) / 0.94f).coerceIn(0f, 1f)
                    val visible = p >= i * 0.06f
                    val alpha = if (visible) sin(PI * prog).toFloat() * 0.9f else 0f
                    if (alpha > 0.02f) {
                        Text(
                            text = "\u2665",
                            fontSize = ((14f + 4f * prog) * scaleFactor).sp,
                            color = Color(0xFFFB7185).copy(alpha = alpha),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    val px = mascotSize.toPx()
                                    translationX = (i - 1) * px * 0.34f +
                                        sin(prog * PI.toFloat() * 2f) * px * 0.03f
                                    translationY = -px * (0.30f + 0.34f * prog)
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
                            color = Color(0xFFFDE047).copy(alpha = alpha),
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

/** Linearer Farb-Mix (lokal — DashboardHero hat denselben als private). */
private fun lerpMascotColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)
