package com.d_drostes_apps.aevum.automation.geofence

/**
 * ════════════════════════════════════════════════════════════════════════
 * M18.144: AUTO-STOP-ENTSCHEIDUNG — HERKUNFT IST IRRELEVANT
 * ════════════════════════════════════════════════════════════════════════
 *
 * ── DER GEMELDETE FEHLER ──────────────────────────────────────────────
 *
 *   „Als ich [den Geofence] verlassen habe, wurde die manuell gestartete
 *    Aktivität nicht geendet."
 *
 * Beide Stop-Pfade verlangten `sourceType == "GEOFENCE_AUTO"`:
 *
 *   CurrentZoneProvider:      existing.sourceType == "GEOFENCE_AUTO"
 *   GeofenceTransitionProcessor: isAutoSession = (sourceType == "GEOFENCE_AUTO")
 *                             → sonst „Auto-Stop übersprungen: Session .. ist manuell"
 *
 * Eine vom Nutzer selbst gestartete Aktivität trägt `sourceType = "MANUAL"`.
 * Sie wurde beim Verlassen deshalb nie beendet.
 *
 * ── WARUM DAS FALSCH WAR ──────────────────────────────────────────────
 *
 * Die Herkunft („wer hat gestartet?") beantwortet nicht die Frage, die der
 * Stop-Pfad eigentlich klären muss: **„Gehört die laufende Aktivität zu
 * diesem Geofence?"**
 *
 * Der Nutzer hat den Geofence mit einer Aktivität konfiguriert und fährt
 * mit einer laufenden Aufzeichnung dieser Aktivität los. Für ihn ist die
 * Erwartung eindeutig: Beim Verlassen endet sie. Ob er sie selbst gestartet
 * hat oder Aevum sie beim Betreten gestartet hat, ist für ihn unsichtbar.
 *
 * Die Herkunft ist nur in EINEM Fall relevant: Eine automatisch gestartete
 * Session darf beim Verlassen verworfen werden, wenn sie nie bestätigt
 * wurde (Auto-Discard-Schutz gegen GPS-Spikes). Eine manuelle Session ist
 * immer gewollt — sie wird nie verworfen, aber sehr wohl beendet.
 *
 * ── DIE ENTSCHEIDUNG ──────────────────────────────────────────────────
 *
 * Der Stop läuft über die **Aktivität**, nicht über die Herkunft:
 *
 *   1. Es läuft eine Live-Session mit exakt der Aktivität, die der
 *      verlassene Geofence konfiguriert hat → beenden.
 *   2. Es läuft eine Session, deren Ursprungs-Trigger zum Geofence gehört
 *      (ENTER-Trigger) → beenden, auch wenn die Aktivität abweicht (der
 *      Nutzer hat die Aktivität während des Aufenthalts gewechselt).
 *   3. Sonst → nicht anfassen.
 *
 * Bewusst NICHT beendet wird eine Session, die zu einem ANDEREN Geofence
 * oder zu einem laufenden Kalender-Termin gehört — sonst würden sich
 * Automatisierungen gegenseitig abschalten.
 *
 * Diese Logik liegt hier als reine Funktion, damit beide Runtime-Pfade
 * dieselbe Entscheidung treffen und sie ohne Emulator prüfbar ist.
 */
object GeofenceAutoStopPolicy {

    /** Warum eine laufende Session beendet wird (für Log/Debug-Banner). */
    enum class StopReason {
        /** Aktivität der Session == konfigurierte Aktivität des Geofence. */
        ACTIVITY_MATCHES,

        /** Der Ursprungs-Trigger der Session stammt von diesem Geofence. */
        TRIGGER_BELONGS_TO_GEOFENCE,

        /** Kein Stop. */
        NO_STOP
    }

    /**
     * Entscheidet, ob eine laufende Session beim Verlassen des Geofence
     * beendet wird.
     *
     * @param sessionActivityTypeId Aktivität der laufenden Session (`null`,
     *   wenn keine läuft oder die Session keine Aktivität hat).
     * @param sessionSourceTriggerId ENTER-Trigger, mit dem die Session
     *   gestartet wurde — `null` bei manuellem Start, Kalender oder
     *   Fremd-Herkunft.
     * @param geofenceAutoActivityTypeId Die im Geofence konfigurierte
     *   Aktivität (`autoStartActivityTypeId`) — `null`, wenn keine
     *   Automatisierung konfiguriert ist.
     * @param geofenceEnterTriggerIds IDs der jüngsten ENTER-Trigger DIESES
     *   Geofence (inkl. ARRIVED/DWELL-Bestätigungen). Leer, wenn keine
     *   Evidenz vorliegt.
     * @param sessionIsLive Läuft die Session überhaupt?
     */
    fun shouldStopOnExit(
        sessionActivityTypeId: String?,
        sessionSourceTriggerId: String?,
        geofenceAutoActivityTypeId: String?,
        geofenceEnterTriggerIds: Set<String>,
        sessionIsLive: Boolean
    ): Boolean = stopReason(
        sessionActivityTypeId = sessionActivityTypeId,
        sessionSourceTriggerId = sessionSourceTriggerId,
        geofenceAutoActivityTypeId = geofenceAutoActivityTypeId,
        geofenceEnterTriggerIds = geofenceEnterTriggerIds,
        sessionIsLive = sessionIsLive
    ) != StopReason.NO_STOP

    /** Wie [shouldStopOnExit], liefert aber den Grund. */
    fun stopReason(
        sessionActivityTypeId: String?,
        sessionSourceTriggerId: String?,
        geofenceAutoActivityTypeId: String?,
        geofenceEnterTriggerIds: Set<String>,
        sessionIsLive: Boolean
    ): StopReason {
        if (!sessionIsLive) return StopReason.NO_STOP

        // 1. Die Aktivität stimmt mit der konfigurierten überein.
        //    Deckt den manuellen Start ab (sourceTriggerId == null) UND den
        //    Auto-Start. Die frühere sourceType-Prüfung ist hier bewusst
        //    NICHT mehr enthalten — genau sie war der Fehler.
        if (geofenceAutoActivityTypeId != null &&
            sessionActivityTypeId != null &&
            sessionActivityTypeId == geofenceAutoActivityTypeId
        ) {
            return StopReason.ACTIVITY_MATCHES
        }

        // 2. Der Ursprungs-Trigger gehört zu diesem Geofence. Fängt den
        //    Fall ab, dass der Nutzer die Aktivität während des Aufenthalts
        //    gewechselt hat — die Session gehört trotzdem hierher.
        if (sessionSourceTriggerId != null &&
            sessionSourceTriggerId in geofenceEnterTriggerIds
        ) {
            return StopReason.TRIGGER_BELONGS_TO_GEOFENCE
        }

        return StopReason.NO_STOP
    }

    /**
     * Darf eine automatisch gestartete Session verworfen werden, wenn sie
     * nie bestätigt wurde (GPS-Spike-Schutz)?
     *
     * Nur für AUTOMATISCH gestartete Sessions: Eine manuell gestartete
     * Session ist immer gewollt und wird niemals verworfen — sie darf nur
     * beendet werden, wenn der Nutzer den Geofence verlässt.
     *
     * Aufgerufen aus [com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager.discardLiveSession]
     * (manueller Discard) — dort ersetzt sie den früheren hartkodierten
     * String-Vergleich. So gibt es genau EINE Herkunfts-Regel statt zweier
     * Kopien, die auseinanderlaufen können.
     */
    fun mayDiscardUnconfirmed(sourceType: String?): Boolean =
        sourceType == SOURCE_GEOFENCE_AUTO

    /** Herkunfts-Marker einer automatisch per Geofence gestarteten Session. */
    const val SOURCE_GEOFENCE_AUTO = "GEOFENCE_AUTO"
}
