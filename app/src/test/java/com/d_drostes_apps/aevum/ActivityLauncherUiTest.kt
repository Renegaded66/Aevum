package com.d_drostes_apps.aevum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.domain.liveactivity.RecentActivityType
import com.d_drostes_apps.aevum.ui.components.ACTIVITY_LAUNCHER_NEW_TAG
import com.d_drostes_apps.aevum.ui.components.ACTIVITY_LAUNCHER_TAG
import com.d_drostes_apps.aevum.ui.components.ACTIVITY_LAUNCHER_TILE_PREFIX
import com.d_drostes_apps.aevum.ui.components.ActivityLauncherSheet
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
 * M33: UI-Evidenz für den neuen Activity-Starter.
 *
 * Der Test rendert die echte Compose-Hierarchie (Robolectric, NATIVE-Grafik)
 * und prüft genau die drei Versprechen des Umbaus:
 *  1. Alle Aktivitäten sind als Raster sichtbar (kein Planetensystem).
 *  2. Ein Tap öffnet den Start-Pfad (Sofort / Vorlaufzeit).
 *  3. Das „+ Neu" ist vorhanden und führt in den Anlegen-Screen (Callback).
 *
 * Zusätzlich wird ein PNG geschrieben — ein leerer Render komprimiert auf
 * wenige KB, echte Pixel mit Text/Gradient liegen deutlich darüber.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityLauncherUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleTypes() = listOf(
        ActivityType("work", "Work", "work", true, null, false, 50, "💼", 0xFF5C6BC0),
        ActivityType("deep_work", "Deep work", "work", true, null, true, 80, "🧠", 0xFF7E57C2),
        ActivityType("fitness", "Fitness", "sport", true, null, true, 85, "🏋️", 0xFF43A047),
        ActivityType("joggen", "Running", "sport", true, null, false, 90, "🏃", 0xFF26A69A),
        ActivityType("learning", "Learning", "learning", true, null, false, 75, "📚", 0xFF26A69A),
        ActivityType("reading", "Reading", "leisure", true, null, false, 65, "📖", 0xFF8D6E63),
        ActivityType("meditation", "Meditation", "health", true, null, false, 90, "🧘", 0xFF66BB6A),
        ActivityType("social", "Friends", "relationships", true, null, false, 80, "👥", 0xFFEC407A),
        ActivityType("household", "Household", "household", true, null, false, 40, "🧹", 0xFF78909C),
    )

    private fun setLauncher(
        onCreateActivity: () -> Unit = {},
        onStart: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                ActivityLauncherSheet(
                    activityTypes = sampleTypes(),
                    recents = listOf(
                        RecentActivityType("fitness", "Fitness", 1L),
                        RecentActivityType("work", "Work", 2L),
                    ),
                    onStart = onStart,
                    onStartWithTime = { _, _, _ -> },
                    onCreateActivity = onCreateActivity,
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun launcher_showsGridAndSections() {
        setLauncher()
        composeRule.onNodeWithTag(ACTIVITY_LAUNCHER_TAG).assertIsDisplayed()
        // Überschriften sind bewusst in Versalien gesetzt.
        composeRule.onNodeWithText("RECENT").assertIsDisplayed()
        composeRule.onNodeWithText("ALL ACTIVITIES").assertIsDisplayed()
        composeRule.onNodeWithTag(ACTIVITY_LAUNCHER_TILE_PREFIX + "fitness").assertIsDisplayed()
        composeRule.onNodeWithTag(ACTIVITY_LAUNCHER_TILE_PREFIX + "meditation").assertIsDisplayed()
    }

    @Test
    fun tappingActivity_opensStartPanel() {
        setLauncher()
        composeRule.onNodeWithTag(ACTIVITY_LAUNCHER_TILE_PREFIX + "fitness").performClick()
        composeRule.onNodeWithText("Start now").assertIsDisplayed()
        composeRule.onNodeWithText("Lead time").assertIsDisplayed()
    }

    @Test
    fun newButton_leadsToCreateScreenCallback() {
        var requested = false
        setLauncher(onCreateActivity = { requested = true })
        composeRule.onNodeWithTag(ACTIVITY_LAUNCHER_NEW_TAG).performClick()
        assertThat(requested).isTrue()
    }

    @Test
    fun launcher_rendersToPng() {
        setLauncher()
        capture("launcher.png")
    }

    @Test
    fun retroPanel_rendersToPng() {
        setLauncher()
        composeRule.onNodeWithTag(ACTIVITY_LAUNCHER_TILE_PREFIX + "fitness").performClick()
        composeRule.onNodeWithText("Lead time").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Exact time").assertIsDisplayed()
        // Der letzte Schnellwahl-Chip muss ohne Scrollen erreichbar sein
        // (7 Chips, 400dp-Testbreite) — sonst ist die Reihe abgeschnitten.
        composeRule.onNodeWithText("-60m").assertIsDisplayed()
        capture("retro.png")
    }

    private fun capture(fileName: String) {
        val view = composeRule.activity.window.decorView
        assertThat(view.width).isGreaterThan(0)
        assertThat(view.height).isGreaterThan(0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        composeRule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/reports/activity-launcher").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        println("LAUNCHER-SCREENSHOT: ${file.absolutePath} (${file.length()} bytes, ${bitmap.width}x${bitmap.height})")
        assertThat(file.length()).isGreaterThan(20_000L)
    }
}
