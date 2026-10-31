package com.d_drostes_apps.aevum.ui.screens.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import com.d_drostes_apps.aevum.util.LocaleHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * M34 — erzeugt echte Pixel der Wizard-Seiten (Robolectric, NATIVE Grafik).
 *
 * Ein Test kann keine Ästhetik bewerten — er kann aber Bilder erzeugen, die
 * man sich ansieht, bevor die APK an Nutzer geht (Überläufe, abgeschnittene
 * Chips, zu kleine Schrift). Die PNGs landen in `build/screenshots/`.
 *
 * Wichtig: gezeichnet wird über `ComposeView.draw(Canvas)`. `captureToImage()`
 * funktioniert unter Robolectric nicht (wartet auf einen echten Redraw).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingScreenshotTest {

    private val outputDir = File("build/screenshots").apply { mkdirs() }

    private val state = OnboardingViewModel.OnboardingUiState(
        selectedCategoryIds = setOf("work", "sleep", "sport", "learning", "health", "leisure"),
        selectedActivityIds = setOf("work", "deep_work", "sleep", "fitness", "joggen", "spazieren", "reading", "meditation"),
        categories = listOf(
            OnboardingViewModel.PickItem("work", "Arbeit", "💼", 0xFF5B8DEFL, true),
            OnboardingViewModel.PickItem("sleep", "Schlaf", "🌙", 0xFF6C7BE0L, true),
            OnboardingViewModel.PickItem("sport", "Sport", "🏃", 0xFF3FB984L, true),
            OnboardingViewModel.PickItem("learning", "Lernen", "📚", 0xFFB07BE0L, true),
            OnboardingViewModel.PickItem("health", "Gesundheit", "🩺", 0xFFE0707BL, true),
            OnboardingViewModel.PickItem("leisure", "Freizeit", "🎧", 0xFFE0A24BL, true),
            OnboardingViewModel.PickItem("household", "Haushalt", "🧺", 0xFF8A94A6L, false)
        ),
        activities = listOf(
            OnboardingViewModel.PickItem("work", "Arbeit", "💼", 0xFF5B8DEFL, true),
            OnboardingViewModel.PickItem("deep_work", "Fokusarbeit", "🎯", 0xFF4A6FD4L, true),
            OnboardingViewModel.PickItem("sleep", "Schlaf", "😴", 0xFF6C7BE0L, true),
            OnboardingViewModel.PickItem("fitness", "Fitness", "🏋️", 0xFF3FB984L, true),
            OnboardingViewModel.PickItem("joggen", "Laufen", "🏃", 0xFF2FA574L, true),
            OnboardingViewModel.PickItem("spazieren", "Spazieren", "🚶", 0xFF57C495L, true),
            OnboardingViewModel.PickItem("reading", "Lesen", "📖", 0xFFB07BE0L, true),
            OnboardingViewModel.PickItem("meditation", "Meditation", "🧘", 0xFF8E6BD0L, true),
            OnboardingViewModel.PickItem("social", "Freunde", "🫂", 0xFFE0707BL, false)
        ),
        permissions = setOf("activity", "notifications", "usage"),
        mascotEnabled = true,
        mascotColor = MascotRepository.COLOR_DEFAULT,
        mascotAntenna = MascotRepository.ANTENNA_DEFAULT,
        mascotCheeks = true,
        language = "de"
    )

    /** Seite 0..4 — identisch zum echten Pager-Aufbau. */
    @Composable
    private fun pageAt(page: Int) {
        when (page) {
            0 -> LanguagePage(selected = "de", onSelect = {})
            1 -> FocusPage(state = state, onToggleCategory = {}, onToggleActivity = {}, onAddCategory = {}, onAddActivity = {})
            2 -> MascotPage(state = state, onEnable = {}, onColor = {}, onAntenna = {}, onCheeks = {})
            3 -> PermissionsPage(state = state, onToggle = {})
            else -> TrustPage()
        }
    }

    /**
     * Vollbild inklusive Kopfzeile, Punkten und Fußzeile — genau das, was der
     * Nutzer beim Durchklicken sieht (ohne ViewModel, daher feste Werte).
     */
    private fun shootFrame(name: String, language: String, page: Int) {
        // `shoot` legt bereits den Wizard-Hintergrund (OnboardingSurface) an.
        shoot("$name-frame", language, height = 2400) {
            Column(Modifier.fillMaxSize()) {
                OnboardingTopBar(page = page)
                Box(Modifier.fillMaxWidth().weight(1f)) { pageAt(page) }
                OnboardingFooter(page = page, finishing = false, onBack = {}, onNext = {})
            }
        }
    }

    @Test
    fun `vollbild seite 1 sprache`() = shootFrame("01-sprache-de", "de", 0)

    @Test
    fun `vollbild seite 2 fokus`() = shootFrame("02-fokus-de", "de", 1)

    @Test
    fun `vollbild seite 3 maskottchen`() = shootFrame("03-maskottchen-de", "de", 2)

    @Test
    fun `vollbild seite 4 berechtigungen`() = shootFrame("04-berechtigungen-de", "de", 3)

    @Test
    fun `vollbild seite 5 abschluss`() = shootFrame("05-abschluss-de", "de", 4)

    @Test
    fun `vollbild seite 5 abschluss spanisch`() = shootFrame("05-abschluss-es", "es", 4)

    private fun shoot(
        name: String,
        language: String,
        height: Int = 2500,
        content: @Composable () -> Unit
    ) {
        val resources = LocaleHelper.resourcesFor(ApplicationProvider.getApplicationContext(), language)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity).apply {
            setContent {
                AevumTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalOnboardingRes provides resources) {
                        // Echter Wizard-Hintergrund (Verlauf + Lichtkegel),
                        // sonst rendert der Test helle Schrift auf Weiß.
                        OnboardingSurface { content() }
                    }
                }
            }
        }
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        val width = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        view.draw(Canvas(bitmap))
        File(outputDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `sprachseite deutsch`() = shoot("01-sprache-de", "de") {
        LanguagePage(selected = "de", onSelect = {})
    }

    @Test
    fun `sprachseite englisch`() = shoot("01-sprache-en", "en") {
        LanguagePage(selected = "en", onSelect = {})
    }

    @Test
    fun `sprachseite spanisch`() = shoot("01-sprache-es", "es") {
        LanguagePage(selected = "es", onSelect = {})
    }

    @Test
    fun `sprachseite tuerkisch`() = shoot("01-sprache-tr", "tr") {
        LanguagePage(selected = "tr", onSelect = {})
    }

    @Test
    fun `fokusseite`() = shoot("02-fokus-de", "de") {
        FocusPage(state = state, onToggleCategory = {}, onToggleActivity = {}, onAddCategory = {}, onAddActivity = {})
    }

    @Test
    fun `maskottchenseite`() = shoot("03-maskottchen-de", "de") {
        MascotPage(state = state, onEnable = {}, onColor = {}, onAntenna = {}, onCheeks = {})
    }

    @Test
    fun `berechtigungsseite`() = shoot("04-berechtigungen-de", "de") {
        PermissionsPage(state = state, onToggle = {})
    }

    @Test
    fun `abschlussseite`() = shoot("05-abschluss-de", "de") { TrustPage() }

    @Test
    fun `abschlussseite spanisch`() = shoot("05-abschluss-es", "es") { TrustPage() }
}
