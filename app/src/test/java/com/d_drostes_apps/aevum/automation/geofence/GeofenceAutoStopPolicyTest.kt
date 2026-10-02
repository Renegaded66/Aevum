package com.d_drostes_apps.aevum.automation.geofence

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * M18.144: Verhaltenstests der Auto-Stop-Entscheidung.
 *
 * Der gemeldete Fehler: „Als ich [den Geofence] verlassen habe, wurde die
 * manuell gestartete Aktivität nicht geendet."
 *
 * Die Kernaussage, die hier festgenagelt wird:
 *
 *   **Eine vom Nutzer selbst gestartete Aktivität (sourceType = MANUAL,
 *   sourceTriggerId = null) wird beim Verlassen beendet, wenn ihre
 *   Aktivität der im Geofence konfigurierten entspricht.**
 *
 * Die Herkunft der Session ist für den Stop irrelevant. Sie ist nur für den
 * Auto-Discard-Schutz relevant (unbestätigte AUTO-Sessions dürfen verworfen
 * werden, manuelle nie).
 */
class GeofenceAutoStopPolicyTest {

    private val autoActivity = "activity-fitness"
    private val otherActivity = "activity-reading"
    private val enterTrigger = "trigger-enter-1"

    // ═══════════════════════════════════════════════════════════════════
    // Der gemeldete Fall: MANUELL gestartet, beim Verlassen beenden
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `manuell gestartete Aktivität wird beim Verlassen beendet`() {
        // Genau Devons Fall: Er hat die Aktivität selbst gestartet
        // (sourceTriggerId = null weil kein Geofence-Trigger sie erzeugt
        // hat), steht im Geofence, und geht.
        val reason = GeofenceAutoStopPolicy.stopReason(
            sessionActivityTypeId = autoActivity,
            sessionSourceTriggerId = null,          // <- manueller Start
            geofenceAutoActivityTypeId = autoActivity,
            geofenceEnterTriggerIds = emptySet(),
            sessionIsLive = true
        )
        assertWithMessage(
            "Eine manuell gestartete Aktivität, die der Geofence-Automatisierung " +
                "entspricht, MUSS beim Verlassen beendet werden. Vorher verlangte " +
                "der Code sourceType == GEOFENCE_AUTO — deshalb passierte nichts."
        ).that(reason).isEqualTo(GeofenceAutoStopPolicy.StopReason.ACTIVITY_MATCHES)

        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = autoActivity,
                sessionSourceTriggerId = null,
                geofenceAutoActivityTypeId = autoActivity,
                geofenceEnterTriggerIds = emptySet(),
                sessionIsLive = true
            )
        ).isTrue()
    }

    @Test
    fun `manuelle Aktivität mit laufender Session und ohne Trigger wird gestoppt`() {
        // Zweiter Durchgang mit explizit leerer Trigger-Menge: der
        // Aktivitäts-Vergleich allein muss reichen.
        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = "activity-gym",
                sessionSourceTriggerId = null,
                geofenceAutoActivityTypeId = "activity-gym",
                geofenceEnterTriggerIds = emptySet(),
                sessionIsLive = true
            )
        ).isTrue()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Was NICHT gestoppt werden darf
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `fremde Aktivität bleibt unangetastet`() {
        // Der Nutzer liest im Fitness-Geofence ein Buch — eine Session mit
        // anderer Aktivität und ohne Bezug zum Geofence darf nicht enden.
        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = otherActivity,
                sessionSourceTriggerId = null,
                geofenceAutoActivityTypeId = autoActivity,
                geofenceEnterTriggerIds = emptySet(),
                sessionIsLive = true
            )
        ).isFalse()
    }

    @Test
    fun `Geofence ohne Automatisierung stoppt nichts`() {
        // Kein autoStartActivityTypeId konfiguriert → der Geofence hat
        // keine Aktivität, die er beenden dürfte.
        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = autoActivity,
                sessionSourceTriggerId = null,
                geofenceAutoActivityTypeId = null,
                geofenceEnterTriggerIds = emptySet(),
                sessionIsLive = true
            )
        ).isFalse()
    }

    @Test
    fun `ohne laufende Session gibt es nichts zu stoppen`() {
        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = autoActivity,
                sessionSourceTriggerId = null,
                geofenceAutoActivityTypeId = autoActivity,
                geofenceEnterTriggerIds = setOf(enterTrigger),
                sessionIsLive = false
            )
        ).isFalse()
    }

    @Test
    fun `Session ohne Aktivität wird nicht gestoppt`() {
        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = null,
                sessionSourceTriggerId = null,
                geofenceAutoActivityTypeId = autoActivity,
                geofenceEnterTriggerIds = emptySet(),
                sessionIsLive = true
            )
        ).isFalse()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Automatisch gestartet: der bisherige Pfad muss weiter funktionieren
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `automatisch gestartete Session wird weiterhin gestoppt`() {
        // Regression-Schutz: der Fix darf den Auto-Start-Pfad nicht brechen.
        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = autoActivity,
                sessionSourceTriggerId = enterTrigger,
                geofenceAutoActivityTypeId = autoActivity,
                geofenceEnterTriggerIds = setOf(enterTrigger),
                sessionIsLive = true
            )
        ).isTrue()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Aktivitätswechsel während des Aufenthalts
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `Aktivitaetswechsel im Geofence wird ueber den Trigger erkannt`() {
        // Der Nutzer kommt per Geofence-Automatik als „Fitness" herein und
        // startet dann manuell eine andere Aktivität. Die Aktivität passt
        // nicht mehr, aber der Ursprungs-Trigger beweist die Zugehörigkeit.
        val reason = GeofenceAutoStopPolicy.stopReason(
            sessionActivityTypeId = otherActivity,
            sessionSourceTriggerId = enterTrigger,
            geofenceAutoActivityTypeId = autoActivity,
            geofenceEnterTriggerIds = setOf(enterTrigger),
            sessionIsLive = true
        )
        assertThat(reason).isEqualTo(GeofenceAutoStopPolicy.StopReason.TRIGGER_BELONGS_TO_GEOFENCE)
    }

    @Test
    fun `Trigger eines anderen Geofence stoppt nicht`() {
        // Wichtig für Anwender mit mehreren Geofences: ein Fremd-Trigger
        // darf keine Session beenden, die zu einem anderen Ort gehört.
        assertThat(
            GeofenceAutoStopPolicy.shouldStopOnExit(
                sessionActivityTypeId = otherActivity,
                sessionSourceTriggerId = "trigger-enter-anderer-ort",
                geofenceAutoActivityTypeId = autoActivity,
                geofenceEnterTriggerIds = setOf(enterTrigger),
                sessionIsLive = true
            )
        ).isFalse()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Auto-Discard: nur automatische Sessions dürfen verworfen werden
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `nur automatische Sessions duerfen verworfen werden`() {
        // Der GPS-Spike-Schutz darf eine manuell gestartete Aktivität
        // niemals verwerfen — der Nutzer hat sie bewusst gestartet.
        assertThat(GeofenceAutoStopPolicy.mayDiscardUnconfirmed("GEOFENCE_AUTO")).isTrue()
        assertThat(GeofenceAutoStopPolicy.mayDiscardUnconfirmed("MANUAL")).isFalse()
        assertThat(GeofenceAutoStopPolicy.mayDiscardUnconfirmed(null)).isFalse()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Der Vorher-Nachher-Vergleich: der alte Code hätte versagt
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `der alte sourceType-Check haette den gemeldeten Fall verpasst`() {
        // Beweisfuehrung: Der entfernte Check lautete
        //   sourceType == "GEOFENCE_AUTO"
        // Eine manuelle Session hat sourceType = "MANUAL" → false → kein
        // Stop. Der neue Pfad kennt sourceType gar nicht mehr.
        val manualSourceType = "MANUAL"
        val oldCheckWouldStop = manualSourceType == "GEOFENCE_AUTO"
        assertWithMessage("Der alte Check muss im gemeldeten Fall versagt haben")
            .that(oldCheckWouldStop).isFalse()
        assertWithMessage("Der neue Pfad muss ihn beenden")
            .that(
                GeofenceAutoStopPolicy.shouldStopOnExit(
                    sessionActivityTypeId = autoActivity,
                    sessionSourceTriggerId = null,
                    geofenceAutoActivityTypeId = autoActivity,
                    geofenceEnterTriggerIds = emptySet(),
                    sessionIsLive = true
                )
            ).isTrue()
    }
}
