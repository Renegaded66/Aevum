package com.d_drostes_apps.aevum.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * M23: Das Widget-Layout muss als RemoteViews inflatierbar sein — Fehler
 * hier sind auf dem Gerät ein leerer Homescreen-Block, den man ohne
 * Launcher-Test nicht sieht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "de-rDE")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AevumWidgetLayoutTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `Widget-Layout-laesst-sich-als-RemoteViews-aufbauen`() {
        val views = RemoteViews(context.packageName, R.layout.aevum_widget)
        val inflated = views.apply(context, FrameLayout(context))

        assertThat(inflated.findViewById<TextView>(R.id.widget_status)).isNotNull()
        assertThat(inflated.findViewById<TextView>(R.id.widget_title)).isNotNull()
        assertThat(inflated.findViewById<TextView>(R.id.widget_timer)).isNotNull()
        assertThat(inflated.findViewById<TextView>(R.id.widget_action)).isNotNull()
    }

    @Test
    fun `Maskottchen-Renderer-liefert-eine-Bitmap-in-Zielgroesse`() {
        val bitmap = WidgetMascotRenderer.render(
            MascotRepository.MascotConfig(
                enabled = true,
                colorId = "blue",
                antennaStyle = MascotRepository.ANTENNA_STAR,
                cheeks = true
            ),
            sizePx = 120
        )
        assertThat(bitmap.width).isEqualTo(120)
        assertThat(bitmap.height).isEqualTo(120)
        assertThat(bitmap.isRecycled).isFalse()
    }

    @Test
    fun `laufende-Session-zeigt-Status-Timer-und-Aktivitaetsnamen`() {
        val session = ActivitySession(
            id = "s-running",
            title = "Lernen",
            activityTypeId = "learning",
            startAt = System.currentTimeMillis() - 65_000L,
            endAt = null,
            sessionStatus = "RUNNING"
        )
        val views = AevumWidgetUpdater.buildViews(
            context = context,
            session = session,
            mascotConfig = MascotRepository.MascotConfig(enabled = false)
        )
        val inflated = views.apply(context, FrameLayout(context))
        val status = inflated.findViewById<TextView>(R.id.widget_status).text.toString()
        val title = inflated.findViewById<TextView>(R.id.widget_title).text.toString()
        val timer = inflated.findViewById<TextView>(R.id.widget_timer)

        // Sprache hängt vom App-Locale ab (Default EN, auf Devons Gerät DE) —
        // der Test prüft die Verdrahtung, nicht die Übersetzung.
        assertThat(status).isNotEmpty()
        assertThat(timer.text.toString()).isEqualTo("01:05")
        assertThat(timer.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(title).isEqualTo("Lernen")
    }

    @Test
    fun `ohne-Session-zeigt-Widget-BEREIT-und-Starten`() {
        val views = AevumWidgetUpdater.buildViews(
            context = context,
            session = null,
            mascotConfig = MascotRepository.MascotConfig(enabled = false)
        )
        val inflated = views.apply(context, FrameLayout(context))
        val status = inflated.findViewById<TextView>(R.id.widget_status).text.toString()
        val title = inflated.findViewById<TextView>(R.id.widget_title).text.toString()
        val timer = inflated.findViewById<TextView>(R.id.widget_timer)
        assertThat(listOf("BEREIT", "READY")).contains(status)
        assertThat(listOf("Starten", "Start")).contains(title)
        assertThat(timer.visibility).isEqualTo(android.view.View.GONE)
    }

    /**
     * Sichtprüfung: rendert das echte RemoteViews-Layout in eine PNG-Datei.
     * Die Datei landet in `/root/builds/aevum-1.0.43-widget-*.png` und wird
     * zusätzlich auf den APK-Server kopiert. Kein Ersatz für einen
     * Launcher-Test, aber deckt abgeschnittene Texte/Farben auf.
     */
    @Test
    fun `Screenshot-Widget-laeuft-und-bereit`() {
        // Devons Gerätesprache ist Deutsch — für die Sichtprüfung denselben
        // App-Locale-Override verwenden wie die App zur Laufzeit.
        com.d_drostes_apps.aevum.util.LocaleHelper.applyLocale(context, "de")
        val density = context.resources.displayMetrics.density
        val widthPx = (160 * density).toInt()
        val heightPx = (64 * density).toInt()

        fun render(name: String, session: ActivitySession?) {
            val views = AevumWidgetUpdater.buildViews(
                context = context,
                session = session,
                mascotConfig = MascotRepository.MascotConfig(
                    enabled = true,
                    colorId = "blue",
                    antennaStyle = MascotRepository.ANTENNA_BALL,
                    cheeks = true
                )
            )
            val view = views.apply(context, FrameLayout(context))
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, widthPx, heightPx)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val out = File("/root/builds/aevum-1.0.43-widget-$name.png")
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            assertThat(out.length()).isGreaterThan(0L)
        }

        render(
            "running",
            ActivitySession(
                id = "s-shot",
                title = "Lernen",
                activityTypeId = "learning",
                startAt = System.currentTimeMillis() - 125_000L,
                endAt = null,
                sessionStatus = "RUNNING"
            )
        )
        render("idle", null)
    }
}
