package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.113: Das erweiterte Fahrzeug-Veto der Walking-Phase.
 *
 * Root-Cause (User: „Spazieren aufgezeichnet während 30er-Zone-Fahrt"):
 * Das M18.110-Veto prüfte nur loc.hasSpeed() — im TRACK_WALK-Modus
 * (BALANCED, 60s) liefern viele Fixes KEIN Speed-Feld, 30 km/h rutschte
 * unter das Veto. Der Fix leitet das Tempo aus Vor-Fix-Distanz/Zeit ab.
 * Diese Tests dokumentieren die Schwelle-Geometrie (pure Logik).
 */
class WalkingVehicleVetoTest {

    @Test
    fun `30er-Zone-Geschwindigkeit überschreitet das Fahrzeug-Veto`() {
        // 30 km/h = 8,33 m/s ≥ WALKING_VEHICLE_SPEED_MPS (8,0) — ein Fix
        // MIT Speed-Feld vetoiert die Walking-Phase.
        assertThat(8.33f >= WalkingDetectionEngine.WALKING_VEHICLE_SPEED_MPS).isTrue()
    }

    @Test
    fun `Abgeleitetes 30er-Tempo aus 60s-Fix-Fenster überschreitet das Veto`() {
        // 60s × 8,33 m/s = 500 m Distanz zwischen Fixes → abgeleitet 8,33 m/s.
        val dtMs = 60_000L
        val distM = 500.0
        val derived = distM / (dtMs / 1000.0)
        assertThat(derived >= WalkingDetectionEngine.WALKING_VEHICLE_SPEED_MPS).isTrue()
    }

    @Test
    fun `Spaziergang (5 kmh) über 60s liegt unter dem abgeleiteten Veto`() {
        // 5 km/h = 1,39 m/s → 60s × 1,39 = 83 m zwischen Fixes.
        val derived = 83.3 / 60.0
        assertThat(derived < WalkingDetectionEngine.WALKING_VEHICLE_SPEED_MPS).isTrue()
    }

    @Test
    fun `Fix-Fenster außerhalb 30s-2min nutzt KEIN abgeleitetes Veto`() {
        // dt = 25s (unter MIN) oder 130s (über MAX) → abgeleitete Speed
        // wird ignoriert (GPS-Lücken-Distanz ist zu unzuverlässig).
        val underMin = 25_000L
        val overMax = 130_000L
        assertThat(underMin < WalkingDetectionEngine.WALKING_DISPLACEMENT_VETO_MIN_DT_MS).isTrue()
        assertThat(overMax > WalkingDetectionEngine.WALKING_DISPLACEMENT_VETO_MAX_DT_MS).isTrue()
    }
}
