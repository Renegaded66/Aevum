package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * M18.117: Motion-Kontext (AR-Typ) in der ActivityRecognitionBridge.
 *
 * Der Kontext wird aus den kontinuierlichen AR-Samples gespeist und mit
 * 60-s-Hysterese gegen Flapping stabilisiert (Audit docs/activity-detection.md
 * §4.5): Erst 2 AUFEINANDERFOLGENDE Samples desselben Typs wechseln den
 * Kontext. Ein einzelnes WALKING-Sample während Stop&Go-Fahrt flippt den
 * Kontext nicht — sonst würde die 30er-Zone-Fahrt fälschlich unter die
 * 12-m/s-Schwelle fallen.
 */
class ActivityRecognitionMotionContextTest {

    private fun bridge() = ActivityRecognitionBridge(
        object : AutomationSettingsRepository {
            override fun get() = flowOf(AutomationSettings())
            override suspend fun upsert(s: AutomationSettings) {}
        }
    )

    @Test
    fun `Default ist UNKNOWN — kein AR-Signal, Verhalten wie heute`() {
        val b = bridge()
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.UNKNOWN)
    }

    @Test
    fun `zwei aufeinanderfolgende ON_FOOT-Samples wechseln den Kontext`() {
        val b = bridge()
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
        // Ein einzelnes Sample reicht nicht (Hysterese).
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.UNKNOWN)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_FOOT)
    }

    @Test
    fun `einzelnes WALKING-Sample waehrend Stop-and-Go flippt IN_VEHICLE nicht`() {
        val b = bridge()
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        // AR-Flackern: EIN WALKING-Sample (Anfahren/Kriechen) — der
        // Kontext bleibt IN_VEHICLE, die 8-m/s-Schwelle bleibt aktiv.
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        // Zwei aufeinanderfolgende ON_FOOT-Samples wechseln dann doch.
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_FOOT)
    }

    @Test
    fun `IN_VEHICLE nach ON_FOOT braucht ebenfalls zwei Samples`() {
        val b = bridge()
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_FOOT)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.ON_FOOT)
        b.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
        assertThat(b.currentMotionContext())
            .isEqualTo(DriveDetectionEngine.MotionContext.IN_VEHICLE)
    }
}
