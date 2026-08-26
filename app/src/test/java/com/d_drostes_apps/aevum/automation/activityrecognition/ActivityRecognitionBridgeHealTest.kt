package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * M18.79: Regression-Tests für die driveActive-Selbstheilung mit
 * Start-in-flight-Fenster.
 *
 * Hintergrund: markDriveConfirmed() und der asynchrone DriveStartWorker-
 * Lauf sind nicht atomar. Die M18.76-Selbstheilung („driveActive ohne
 * laufende Auto-Session → clear") durfte eine FRISCHE Bestätigung nicht
 * zerstören — sonst stirbt der Heartbeat-Refresh und der Watchdog stoppt
 * die frisch gestartete Fahrt nach 5 Minuten (M18.67-FIX3-Pfad), bzw.
 * die Erkennung bleibt blockiert (Blackout).
 *
 * Bewusst Android-frei (reine JVM-Unit-Tests, kein Robolectric) — die
 * Bridge braucht nur das Settings-Repository-Interface, das hier mit
 * einem Inline-Objekt gestubbt wird.
 */
class ActivityRecognitionBridgeHealTest {

    private fun bridge(settings: AutomationSettings = AutomationSettings()) =
        ActivityRecognitionBridge(
            object : AutomationSettingsRepository {
                override fun get() = flowOf(settings)
                override suspend fun upsert(s: AutomationSettings) {}
            }
        )

    // ── M18.79: Start-in-flight-Fenster ──────────────────────────

    @Test
    fun `frische Bestaetigung wird nicht geheilt — Start-in-flight laeuft`() {
        // markDriveConfirmed() → DriveStartWorker ist noch nicht gelaufen
        // (keine Session live). Die Heilung darf das Flag NICHT nehmen,
        // sonst stirbt der Heartbeat-Refresh und der Watchdog stoppt die
        // Fahrt nach 5 Minuten.
        val b = bridge()
        b.markDriveConfirmed()
        val now = System.currentTimeMillis() + 30_000L // 30s später
        assertThat(b.healIfOrphaned(now)).isFalse()
        assertThat(b.isDriveActive()).isTrue()
    }

    @Test
    fun `verwaiste Bestaetigung aelter als das Fenster wird geheilt — Blackout verhindert`() {
        // Bestätigung ist verloren (Worker nie gelaufen / Flag-Race), die
        // Session startet nie. Nach dem In-flight-Fenster muss die
        // Erkennung neu klassifizieren können.
        val b = bridge()
        b.markDriveConfirmed()
        val now = System.currentTimeMillis() +
            DriveDetectionEngine.DRIVE_CONFIRM_IN_FLIGHT_MS + 10_000L
        assertThat(b.healIfOrphaned(now)).isTrue()
        assertThat(b.isDriveActive()).isFalse()
        // Auch das Bestätigungs-Flag ist weg — ein verspäteter Worker-Lauf
        // darf keine längst vorbei gefahrene Fahrt starten.
        assertThat(b.isDriveConfirmed()).isFalse()
    }

    @Test
    fun `nach Heilung erkennt eine neue Fahrt wieder — kein Blackout`() {
        val b = bridge()
        b.markDriveConfirmed()
        val later = System.currentTimeMillis() +
            DriveDetectionEngine.DRIVE_CONFIRM_IN_FLIGHT_MS + 60_000L
        b.healIfOrphaned(later)
        assertThat(b.isDriveActive()).isFalse()

        // Neue Fahrt: Bestätigung ist wieder frisch → Heilung greift nicht.
        b.markDriveConfirmed()
        val now2 = System.currentTimeMillis() + 20_000L
        assertThat(b.healIfOrphaned(now2)).isFalse()
        assertThat(b.isDriveActive()).isTrue()
    }

    @Test
    fun `ohne aktives driveActive passiert nichts`() {
        val b = bridge()
        assertThat(b.healIfOrphaned(System.currentTimeMillis())).isFalse()
        assertThat(b.isDriveActive()).isFalse()
    }
}
