package com.d_drostes_apps.aevum.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import kotlin.math.cos
import kotlin.math.sin

/**
 * M23: Zeichnet das Aevum-Maskottchen als statische Bitmap fürs Widget.
 *
 * Bewusst Android-Canvas statt Compose: RemoteViews kann keine Composable-
 * Views rendern, und ein Compose-View-Rendering im Sekundentakt wäre für
 * ein 42-dp-Bild unnötig teuer. Die Formensprache folgt dem PAG-Vorbild
 * (runder Körper, Antenne mit goldener Spitze, große Augen, Bäckchen).
 */
object WidgetMascotRenderer {

    fun render(config: MascotRepository.MascotConfig, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = size.toFloat()
        val body = MascotRepository.colorFor(config.colorId).toArgb()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Bodenschatten — erdet die Figur auch auf hellen Widgets.
        paint.color = 0x22000000
        canvas.drawOval(
            RectF(w * 0.22f, w * 0.84f, w * 0.78f, w * 0.94f),
            paint
        )

        val cx = w * 0.5f
        val cy = w * 0.58f
        val radius = w * 0.30f

        // Antenne (vor dem Körper, damit die Linie sauber einmündet).
        paint.shader = null
        paint.color = 0xFF9FB0C6.toInt()
        paint.strokeWidth = w * 0.035f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy - radius * 0.86f, cx, w * 0.19f, paint)
        paint.style = Paint.Style.FILL

        // Antennen-Spitze: Kugel / Stern / Blitz (personalisierbar).
        val tipY = w * 0.16f
        val tipR = w * 0.065f
        paint.color = 0xFFFFD166.toInt()
        when (config.antennaStyle) {
            MascotRepository.ANTENNA_STAR -> {
                val path = Path()
                val spikes = 5
                val outer = tipR * 1.35f
                val inner = tipR * 0.58f
                for (i in 0 until spikes * 2) {
                    val r = if (i % 2 == 0) outer else inner
                    val angle = Math.PI / 2 + i * Math.PI / spikes
                    val x = cx + (cos(angle) * r).toFloat()
                    val y = tipY - (sin(angle) * r).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            MascotRepository.ANTENNA_BOLT -> {
                val path = Path().apply {
                    moveTo(cx + tipR * 0.35f, tipY - tipR * 1.15f)
                    lineTo(cx - tipR * 0.55f, tipY + tipR * 0.15f)
                    lineTo(cx + tipR * 0.02f, tipY + tipR * 0.15f)
                    lineTo(cx - tipR * 0.25f, tipY + tipR * 1.25f)
                    lineTo(cx + tipR * 0.75f, tipY - tipR * 0.25f)
                    lineTo(cx + tipR * 0.12f, tipY - tipR * 0.25f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            else -> canvas.drawCircle(cx, tipY, tipR, paint)
        }

        // Körper mit weichem Vertikal-Verlauf (kein harter Umriss).
        paint.shader = LinearGradient(
            cx, cy - radius, cx, cy + radius,
            lighten(body, 0.16f), darken(body, 0.20f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        // Bauchlicht — ein dezentes Oval unten.
        paint.color = lighten(body, 0.22f)
        paint.alpha = 70
        canvas.drawOval(
            RectF(cx - radius * 0.52f, cy + radius * 0.18f, cx + radius * 0.52f, cy + radius * 0.78f),
            paint
        )
        paint.alpha = 255

        // Augen: weiß + dunkle Pupillen, leicht nach innen schauend.
        val eyeY = cy - radius * 0.10f
        val eyeDx = radius * 0.36f
        val eyeR = radius * 0.25f
        paint.color = 0xFFF4F8FF.toInt()
        canvas.drawCircle(cx - eyeDx, eyeY, eyeR, paint)
        canvas.drawCircle(cx + eyeDx, eyeY, eyeR, paint)
        paint.color = 0xFF232B3A.toInt()
        val pupilR = eyeR * 0.44f
        canvas.drawCircle(cx - eyeDx + eyeR * 0.10f, eyeY + eyeR * 0.04f, pupilR, paint)
        canvas.drawCircle(cx + eyeDx - eyeR * 0.10f, eyeY + eyeR * 0.04f, pupilR, paint)

        // Lächeln — ruhig und freundlich.
        paint.color = 0xFF1B2333.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.028f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(
            RectF(cx - radius * 0.34f, cy + radius * 0.06f, cx + radius * 0.34f, cy + radius * 0.48f),
            20f, 140f, false, paint
        )
        paint.style = Paint.Style.FILL

        // Bäckchen (abschaltbar).
        if (config.cheeks) {
            paint.color = 0xFFFF8FA3.toInt()
            paint.alpha = 170
            canvas.drawCircle(cx - radius * 0.62f, cy + radius * 0.28f, radius * 0.15f, paint)
            canvas.drawCircle(cx + radius * 0.62f, cy + radius * 0.28f, radius * 0.15f, paint)
        }

        return bitmap
    }

    private fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int = blend(color, Color.BLACK, amount)

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return Color.argb(
            channel(24), channel(16), channel(8), channel(0)
        )
    }
}
