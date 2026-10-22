package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M28: Auto-Fahrt darf keine laufende Aufzeichnung unterbrechen.
 *
 * User:
 *  - „Automatische Fahrtaufzeichnung darf keine laufenden Aufzeichnungen
 *    unterbrechen. Das passiert aber aktuell."
 *  - „Aber das System darf wissen dass gerade gefahren wird und die
 *    Aufzeichnung starten sobald die laufende aktivität gestoppt wird."
 *  - „Aber natürlich nicht unendlich lange, also wenn die autofahrt aufhört
 *    und erst danach die laufende andere aktivität gestoppt wird dann soll
 *    natürlich nicht Autofahren starten."
 *
 * Die Tests decken beide Richtungen ab: NICHT unterbrechen (Defer) und
 * NICHT ewig nachholen (Evidenz-Fenster + harte Obergrenze).
 */
class PendingDrivePolicyTest {

    // ── Block-Erkennung ───────────────────────────────────────────────────

    @Test
    fun `laufende-manuelle-Aufzeichnung-blockiert-den-Fahrt-Start`() {
        assertThat(
            PendingDrivePolicy.shouldDefer(
                liveIsLive = true,
                liveActivityTypeId = "reading",
                liveSourceType = "MANUAL"
            )
        ).isTrue()
    }

    @Test
    fun `laufende-Digital-App-und-Geofence-Aufzeichnungen-blockieren-ebenfalls`() {
        listOf(
            "digital" to "SCREEN_AUTO",
            "reading" to "APP_TRACKING",
            "work" to "GEOFENCE_AUTO",
            "social" to "CALL_AUTO",
            "sport" to "CALENDAR_AUTO",
            "walking" to "WALKING_AUTO"
        ).forEach { (type, source) ->
            assertThat(PendingDrivePolicy.shouldDefer(true, type, source))
                .isTrue()
        }
    }

    @Test
    fun `eine-bereits-laufende-Fahrt-blockiert-nicht-sie-ist-ein-Duplikat`() {
        assertThat(
            PendingDrivePolicy.shouldDefer(
                liveIsLive = true,
                liveActivityTypeId = PendingDrivePolicy.DRIVE_TYPE_ID,
                liveSourceType = PendingDrivePolicy.DRIVE_SOURCE_TYPE
            )
        ).isFalse()
    }

    @Test
    fun `ohne-live-Session-gibt-es-nichts-zu-blockieren`() {
        assertThat(PendingDrivePolicy.shouldDefer(false, "reading", "MANUAL")).isFalse()
    }

    // ── Nachhol-Entscheidung ──────────────────────────────────────────────

    @Test
    fun `frische-GPS-Evidenz-startet-die-Fahrt-sofort-nach-dem-Block`() {
        val now = 1_000_000_000L
        assertThat(
            PendingDrivePolicy.shouldStartAfterBlock(
                drivingEvidenceNow = true,
                lastEvidenceAtMs = now,
                pendingStartedAtMs = now - 60_000L,
                nowMs = now
            )
        ).isTrue()
    }

    @Test
    fun `auch-ohne-aktuelle-Klassifikation-reicht-frische-Bestaetigung`() {
        val now = 1_000_000_000L
        assertThat(
            PendingDrivePolicy.shouldStartAfterBlock(
                drivingEvidenceNow = false,
                lastEvidenceAtMs = now - PendingDrivePolicy.EVIDENCE_TTL_MS,
                pendingStartedAtMs = now - 10 * 60_000L,
                nowMs = now
            )
        ).isTrue()
    }

    @Test
    fun `Fahrt-vorbei-und-danach-gestoppt-startet-NICHT`() {
        val now = 1_000_000_000L
        assertThat(
            PendingDrivePolicy.shouldStartAfterBlock(
                drivingEvidenceNow = false,
                // Letzte Evidenz 20 Minuten alt: das Auto steht längst.
                lastEvidenceAtMs = now - 20 * 60_000L,
                pendingStartedAtMs = now - 40 * 60_000L,
                nowMs = now
            )
        ).isFalse()
    }

    @Test
    fun `ohne-jede-Evidenz-startet-nichts`() {
        val now = 1_000_000_000L
        assertThat(
            PendingDrivePolicy.shouldStartAfterBlock(
                drivingEvidenceNow = false,
                lastEvidenceAtMs = 0L,
                pendingStartedAtMs = now - 60_000L,
                nowMs = now
            )
        ).isFalse()
    }

    @Test
    fun `ohne-Vormerk-startet-nichts`() {
        assertThat(
            PendingDrivePolicy.shouldStartAfterBlock(
                drivingEvidenceNow = true,
                lastEvidenceAtMs = 1_000_000_000L,
                pendingStartedAtMs = 0L,
                nowMs = 1_000_000_000L
            )
        ).isFalse()
    }

    @Test
    fun `harte-Obergrenze-verhindert-endloses-Warten`() {
        val now = 1_000_000_000L
        assertThat(
            PendingDrivePolicy.shouldStartAfterBlock(
                drivingEvidenceNow = true,
                lastEvidenceAtMs = now,
                pendingStartedAtMs = now - PendingDrivePolicy.MAX_PENDING_AGE_MS - 1L,
                nowMs = now
            )
        ).isFalse()
    }

    @Test
    fun `Evidenz-Fenster-ist-groesser-als-der-2-Minuten-Takt`() {
        // Der DriveProbeWorker läuft alle 2 Minuten — eine verpasste Probe
        // darf die Fahrt nicht verfallen lassen.
        assertThat(PendingDrivePolicy.EVIDENCE_TTL_MS)
            .isAtLeast(3L * 2L * 60_000L)
    }

    // ── Start-Anker ───────────────────────────────────────────────────────

    @Test
    fun `Start-Anker-ist-das-Ende-des-blockierenden-Blocks`() {
        val now = 1_000_000_000L
        assertThat(
            PendingDrivePolicy.startAtAfterBlock(blockingEndAtMs = now - 5_000L, nowMs = now)
        ).isEqualTo(now - 5_000L)
    }

    @Test
    fun `Start-Anker-liegt-nie-in-der-Zukunft`() {
        val now = 1_000_000_000L
        assertThat(
            PendingDrivePolicy.startAtAfterBlock(blockingEndAtMs = now + 60_000L, nowMs = now)
        ).isEqualTo(now)
    }

    @Test
    fun `ohne-Block-Ende-gilt-jetzt`() {
        val now = 1_000_000_000L
        assertThat(PendingDrivePolicy.startAtAfterBlock(blockingEndAtMs = null, nowMs = now))
            .isEqualTo(now)
    }
}
