package com.d_drostes_apps.aevum.ui.screens.placetimeline

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceVisit
import com.d_drostes_apps.aevum.domain.placetimeline.VisitEvidence
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M24: Ort in der Orts-Timeline antippen → Detail-Sheet → „Geofence
 * hinzufügen" muss den übernommenen Standort (und ggf. den Ortsnamen)
 * an die Navigation geben.
 *
 * Getestet wird der echte zustandsfreie Sheet-Rumpf mit einem vorgegebenen
 * [PlaceVisit] — ohne Hilt, ohne DB. Die Koordinaten sind absichtlich
 * ungewöhnlich, damit ein versehentliches 0/0 sofort auffällt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PlaceTimelineGeofenceSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val addLabel: String get() = context.getString(R.string.place_timeline_detail_add_geofence)

    private fun visit(
        id: String = "u1",
        geofenceId: String? = null,
        name: String = "",
        evidence: VisitEvidence = VisitEvidence.UNNAMED_PLACE,
        lat: Double? = 52.520008,
        lon: Double? = 13.404954
    ) = PlaceVisit(
        id = id,
        geofenceId = geofenceId,
        name = name,
        icon = "📍",
        color = "#64748B",
        latitude = lat,
        longitude = lon,
        startAt = 1_760_000_000_000L,
        endAt = 1_760_003_600_000L,
        evidence = evidence,
        isOngoing = false
    )

    @Test
    fun `unbenannter Ort bietet Geofence an und uebergibt Koordinaten`() {
        var captured: Triple<Double, Double, String?>? = null
        compose.setContent {
            MaterialTheme {
                VisitDetailSheet(
                    visit = visit(),
                    onAddGeofence = { lat, lon, name -> captured = Triple(lat, lon, name) },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText(addLabel).assertIsDisplayed().performClick()

        assertThat(captured).isNotNull()
        assertThat(captured!!.first).isWithin(0.000001).of(52.520008)
        assertThat(captured!!.second).isWithin(0.000001).of(13.404954)
        assertThat(captured!!.third).isNull()
    }

    @Test
    fun `benannter Ort uebergibt seinen Namen an den Editor`() {
        var captured: Triple<Double, Double, String?>? = null
        compose.setContent {
            MaterialTheme {
                VisitDetailSheet(
                    visit = visit(name = "Bibliothek", evidence = VisitEvidence.NAMED_PLACE),
                    onAddGeofence = { lat, lon, name -> captured = Triple(lat, lon, name) },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText(addLabel).performClick()

        assertThat(captured!!.third).isEqualTo("Bibliothek")
    }

    @Test
    fun `bestehender Geofence bietet keinen zweiten an`() {
        compose.setContent {
            MaterialTheme {
                VisitDetailSheet(
                    visit = visit(geofenceId = "g1", name = "Zuhause", evidence = VisitEvidence.GEOFENCE_LONG),
                    onAddGeofence = { _, _, _ -> },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText(addLabel).assertDoesNotExist()
        compose.onNodeWithText(
            context.getString(R.string.place_timeline_detail_geofence_exists)
        ).assertIsDisplayed()
    }

    @Test
    fun `Ort ohne Koordinaten bietet keinen Geofence an`() {
        compose.setContent {
            MaterialTheme {
                VisitDetailSheet(
                    visit = visit(lat = null, lon = null),
                    onAddGeofence = { _, _, _ -> },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText(addLabel).assertDoesNotExist()
        compose.onNodeWithText(
            context.getString(R.string.place_timeline_detail_no_coordinates)
        ).assertIsDisplayed()
    }
}
