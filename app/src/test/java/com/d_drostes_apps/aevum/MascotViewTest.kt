package com.d_drostes_apps.aevum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.ui.screens.dashboard.MASCOT_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.MascotView
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M19.5: Robolectric-Evidenz für das Maskottchen selbst (echte
 * Compose-Hierarchie, echte Pixel — keine Mocks).
 *
 * Geprüft wird:
 *  - Rendern + Tippen ruft den Callback (Reaktions-Trigger),
 *  - Schlaf-Semantik wechselt live („schläft" ↔ „ist wach") — der
 *    Zustand aus der 22–6-Regel,
 *  - alle drei Ohrenformen rendern,
 *  - Sichtprüfungs-Screenshots: wach (lächelnd), schlafend (z z z),
 *    mitten in der Hüpf-Herzchen-Reaktion und ein Stil-Streifen
 *    (rund/spitz/lang) für den Erstellen-Bereich.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MascotViewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val ctx
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun config(
        colorId: String = "ice",
        earStyle: String = MascotRepository.EARS_ROUND,
        cheeks: Boolean = true
    ) = MascotRepository.MascotConfig(
        enabled = true, colorId = colorId, earStyle = earStyle, cheeks = cheeks
    )

    @Test
    fun clickTriggersReactionCallback() {
        var clicks = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface { MascotView(config = config(), smileLevel = 0.8f, sleeping = false, onClick = { clicks += 1 }) }
            }
        }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag(MASCOT_TEST_TAG).assertExists()
        composeRule.onNodeWithTag(MASCOT_TEST_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(400)
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun sleepingStateIsExposedAndSwitchesLive() {
        val sleeping = mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface {
                    MascotView(
                        config = config(),
                        smileLevel = 0.6f,
                        sleeping = sleeping.value
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag(MASCOT_TEST_TAG).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                ctx.getString(R.string.mascot_state_sleeping)
            )
        )
        // Aufzeichnung startet → wach (wechselt live, kein Neustart nötig).
        sleeping.value = false
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag(MASCOT_TEST_TAG).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                ctx.getString(R.string.mascot_state_awake)
            )
        )
    }

    @Test
    fun allEarStylesRender() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MascotView(
                            config = config(earStyle = MascotRepository.EARS_ROUND),
                            smileLevel = 0.7f, sleeping = false,
                            testTag = "mascot-round"
                        )
                        MascotView(
                            config = config(colorId = "sun", earStyle = MascotRepository.EARS_POINTY),
                            smileLevel = 0.7f, sleeping = false,
                            testTag = "mascot-pointy"
                        )
                        MascotView(
                            config = config(colorId = "lavender", earStyle = MascotRepository.EARS_LONG),
                            smileLevel = 0.7f, sleeping = false,
                            testTag = "mascot-long"
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("mascot-round").assertExists()
        composeRule.onNodeWithTag("mascot-pointy").assertExists()
        composeRule.onNodeWithTag("mascot-long").assertExists()
    }

    @Test
    fun mascotScreenshotAwake() {
        captureMascot(sleeping = false, fileName = "mascot-awake.png")
    }

    @Test
    fun mascotScreenshotSleeping() {
        captureMascot(sleeping = true, fileName = "mascot-sleeping.png")
    }

    @Test
    fun mascotScreenshotReactionHearts() {
        // Mitten in der ersten Tipp-Reaktion (Hüpfer + Herzchen): kurz nach
        // dem Klick einfrieren — die Partikel schweben sichtbar.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(MASCOT_SHOT_ROOT),
                        contentAlignment = Alignment.Center
                    ) {
                        MascotView(
                            config = config(),
                            smileLevel = 0.9f,
                            sleeping = false,
                            mascotSize = 200.dp,
                            testTag = MASCOT_TEST_TAG
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.onNodeWithTag(MASCOT_TEST_TAG).performClick()
        // Reaktion dauert 1700 ms — bei ~700 ms schweben alle drei
        // Herzchen sichtbar (gestaffelt, am höchsten Punkt).
        composeRule.mainClock.advanceTimeBy(700)
        captureMascotRootToFile("mascot-reaction-hearts.png", minBytes = 3_000L)
    }

    @Test
    fun mascotStylesScreenshot() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .testTag(MASCOT_SHOT_ROOT),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MascotView(
                            config = config(earStyle = MascotRepository.EARS_ROUND),
                            smileLevel = 0.7f, sleeping = false, mascotSize = 140.dp
                        )
                        MascotView(
                            config = config(colorId = "sun", earStyle = MascotRepository.EARS_POINTY),
                            smileLevel = 0.7f, sleeping = false, mascotSize = 140.dp
                        )
                        MascotView(
                            config = config(colorId = "lavender", earStyle = MascotRepository.EARS_LONG),
                            smileLevel = 0.7f, sleeping = false, mascotSize = 140.dp
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        captureMascotRootToFile("mascot-styles.png", minBytes = 5_000L)
    }

    private fun captureMascot(sleeping: Boolean, fileName: String) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(MASCOT_SHOT_ROOT),
                        contentAlignment = Alignment.Center
                    ) {
                        MascotView(
                            config = config(),
                            smileLevel = 0.9f,
                            sleeping = sleeping,
                            mascotSize = 200.dp
                        )
                    }
                }
            }
        }
        // 1,2 s: Atmen/Blinzeln an einem friedlichen Punkt; bei Schlaf
        // schweben die ersten „z" bereits auf.
        composeRule.mainClock.advanceTimeBy(1_200)
        captureMascotRootToFile(fileName, minBytes = 3_000L)
    }

    private fun captureMascotRootToFile(fileName: String, minBytes: Long) {
        val view = composeRule.activity.window.decorView
        val full = Bitmap.createBitmap(
            view.width,
            view.height,
            Bitmap.Config.ARGB_8888
        )
        composeRule.runOnUiThread {
            view.draw(android.graphics.Canvas(full))
        }
        val bounds = composeRule.onNodeWithTag(MASCOT_SHOT_ROOT).fetchSemanticsNode().boundsInRoot
        val cropBottom = (bounds.bottom.toInt() + 24).coerceIn(1, full.height)
        val bitmap = Bitmap.createBitmap(full, 0, 0, full.width, cropBottom)
        val dir = File("build/reports/dashboard-hero").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        println("MASCOT-SCREENSHOT: ${file.absolutePath} (${file.length()} bytes, ${bitmap.width}x${bitmap.height})")
        assertThat(file.length()).isGreaterThan(minBytes)
    }

    private companion object {
        const val MASCOT_SHOT_ROOT = "mascot-shot-root"
    }
}
