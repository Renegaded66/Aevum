package com.d_drostes_apps.aevum.domain.call

/**
 * ════════════════════════════════════════════════════════════════════════
 * M26: ANRUF-ERKENNUNG — RANGFOLGE UND ÜBERNAHME-REGELN (reine Logik)
 * ════════════════════════════════════════════════════════════════════════
 *
 * User-Spezifikation (wörtlich):
 *  - „Aevum neue Erkennung. Und zwar Anruf-Erkennung. Sollte in den
 *    Einstellungen erlaubt werden, so wie die anderen Berechtigungen. Und
 *    dann eine Zuweisung der Activity. Default sozial (auf deutsch)."
 *  - „Und immer während ein Anruf läuft wird die Activity direkt
 *    aufgezeichnet, immer falls gerade nichts läuft; es soll auch aktuelle
 *    Aktivitäten unterbrechen, die durch Digital-Nutzung stattfinden, also
 *    Bildschirmaufzeichnung oder App-Aufzeichnung, auch PC digital. Aber
 *    mehr darf nicht unterbrochen werden, also keine manuell gestartete
 *    Aktivität und auch keine Geofences und Kalender etc."
 *
 * Daraus folgen genau drei Entscheidungen, die hier als reine Funktionen
 * liegen (ohne Android-Abhängigkeiten → JVM-testbar, Muster:
 * AppTrackingTakeoverPolicy / GeofenceAutoStopPolicy):
 *
 *  1. [onCallActive] — darf der Anruf JETZT die Aufzeichnung übernehmen?
 *  2. [onCallEnded] — darf das Anruf-Ende die laufende Aufzeichnung beenden?
 *  3. [isDigital]   — welche Quellen gelten als „Digital-Nutzung"?
 *
 * ── DIE RANGFOLGE (bewusst NICHT verhandelbar) ────────────────────────
 *
 *   Digital (unterbrechbar):   SCREEN_AUTO (Bildschirmaufzeichnung),
 *                              APP_TRACKING (App-Aufzeichnung),
 *                              PC_TRACKER / PC_REMOTE (PC-digital)
 *   Höherprior / tabu:          MANUAL/LIVE (selbst gestartet), GEOFENCE_AUTO,
 *                              CALENDAR_AUTO, ACTIVITY_RECOGNITION_AUTO,
 *                              WALKING_AUTO, GARMIN_*, PING_AUTO und alles
 *                              Unbekannte.
 *
 * Die Rangfolge ist eine ALLOWLIST, keine Blockliste: Alles, was nicht
 * ausdrücklich als Digital gilt, wird nicht angefasst. Eine neue
 * Aufzeichnungsquelle (künftige Milestones) wird damit automatisch tabu,
 * statt versehentlich vom Anruf übernommen zu werden — genau der Fehler,
 * den die Geofence-Policy (M18.144) und die Digital-Übergabe (M18.151) je
 * einmal hatten.
 *
 * ── WARUM DER ANRUF NICHT EINFACH `start()` AUFRUFT ───────────────────
 *
 * `LiveActivityManager.start()` beendet eine laufende Session automatisch
 * (Überlappungs-Trimm, M18.71) — für JEDE Quelle. Ein Anruf, der blind
 * startet, würde also auch eine manuell gestartete Aktivität oder eine
 * Geofence-Session abschneiden. Deshalb entscheidet VORHER diese Policy,
 * und `start()` läuft nur in den Fällen START_CALL und
 * TAKE_OVER_DIGITAL. Der Trimm trifft dann ausschließlich eine
 * Digital-Session — das ist die gewünschte Wirkung.
 */
object CallDetectionPolicy {

    /** Quell-Typ der Anruf-Aufzeichnung. */
    const val SOURCE_CALL_AUTO = "CALL_AUTO"

    /**
     * Quelle der Bildschirm-Aufzeichnung („Digital").
     * Muss identisch sein mit `AppTrackingTakeoverPolicy.SOURCE_SCREEN_AUTO`
     * und `ScreenStatisticsPolicy.SOURCE_SCREEN_RECORDING` — der
     * `CallDetectionPolicyTest` nagelt die Gleichheit fest, damit die vier
     * Stellen nicht auseinanderdriften.
     */
    const val SOURCE_SCREEN_AUTO = "SCREEN_AUTO"

    /** Quelle der App-zugewiesenen Aufzeichnung (App-Aufzeichnung). */
    const val SOURCE_APP_TRACKING = "APP_TRACKING"

    /**
     * Quelle importierter PC-Tracker-Sessions (Desktop-Aufzeichnung).
     * Normalerweise abgeschlossen (Pull-Sync liefert fertige Sessions) —
     * steht hier trotzdem: „auch PC digital" (User-Spezifikation). Sollte
     * eine PC-Session je live sein, ist sie Digital und damit unterbrechbar.
     */
    const val SOURCE_PC_TRACKER = "PC_TRACKER"

    /** Quelle einer vom PC gestarteten Aufzeichnung (Live-Kanal). */
    const val SOURCE_PC_REMOTE = "PC_REMOTE"

    /** Alle Quellen, die ein laufender Anruf übernehmen darf. */
    val DIGITAL_SOURCES: Set<String> = setOf(
        SOURCE_SCREEN_AUTO,
        SOURCE_APP_TRACKING,
        SOURCE_PC_TRACKER,
        SOURCE_PC_REMOTE
    )

    /** Was soll passieren, wenn ein Anruf läuft? */
    enum class Action {
        /** Nichts läuft → die zugeordnete Anruf-Aktivität starten. */
        START_CALL,

        /** Digital-Nutzung läuft → übernehmen (Digital endet, Anruf beginnt). */
        TAKE_OVER_DIGITAL,

        /** Die eigene Anruf-Aufzeichnung läuft bereits → nichts tun. */
        ALREADY_TRACKING,

        /** Höherpriore Quelle läuft (manuell/Geofence/Kalender/…) → tabu. */
        KEEP_FOREIGN,

        /** Feature ist ausgeschaltet → nichts tun. */
        DISABLED
    }

    /** Was soll passieren, wenn der Anruf endet? */
    enum class EndAction {
        /** Die eigene Anruf-Aufzeichnung läuft noch → beenden. */
        STOP_OWN,

        /** Etwas anderes läuft → NIEMALS anfassen. */
        NOTHING
    }

    /** Gilt diese Quelle als Digital-Nutzung (unterbrechbar)? */
    fun isDigital(sourceType: String?): Boolean =
        sourceType != null && sourceType in DIGITAL_SOURCES

    /**
     * Entscheidung beim Beginn eines Gesprächs (`CALL_STATE_OFFHOOK`).
     *
     * @param enabled Feature-Schalter aus den Einstellungen.
     * @param liveSourceType sourceType der laufenden Session (null = keine).
     * @param liveIsLive lebt die Session überhaupt (RUNNING/PAUSED)?
     */
    fun onCallActive(
        enabled: Boolean,
        liveSourceType: String?,
        liveIsLive: Boolean
    ): Action = when {
        !enabled -> Action.DISABLED
        // Keine laufende Aufzeichnung → direkt starten.
        !liveIsLive || liveSourceType == null -> Action.START_CALL
        // Die eigene Anruf-Aufzeichnung läuft schon (z. B. zweiter
        // OFFHOOK-Callback desselben Gesprächs, Anklopfen, Call-Waiting):
        // NICHT neu starten — sonst zerlegt jeder Callback die Session.
        liveSourceType == SOURCE_CALL_AUTO -> Action.ALREADY_TRACKING
        // Digital-Nutzung → übernehmen (der einzige erlaubte Eingriff).
        isDigital(liveSourceType) -> Action.TAKE_OVER_DIGITAL
        // Alles andere ist tabu: manuell, Geofence, Kalender, Fahrt,
        // Wandern, Garmin, Ping, unbekannte Quellen.
        else -> Action.KEEP_FOREIGN
    }

    /**
     * Entscheidung beim Ende eines Gesprächs (`CALL_STATE_IDLE`).
     *
     * Beendet wird AUSSCHLIESSLICH die eigene Anruf-Aufzeichnung. Hat
     * währenddessen eine andere Quelle übernommen (der Nutzer startet
     * manuell, ein Geofence greift, der Kalender-Termin beginnt), bleibt
     * sie unangetastet — auch dann, wenn der Anruf sie nie gestartet hat.
     */
    fun onCallEnded(liveSourceType: String?, liveIsLive: Boolean): EndAction =
        if (liveIsLive && liveSourceType == SOURCE_CALL_AUTO) EndAction.STOP_OWN
        else EndAction.NOTHING

    /**
     * Darf der Anruf-Beginn überhaupt in die Aufzeichnung eingreifen?
     * Bequeme Fassung für die Verdrahtung (Service): true bei START_CALL
     * und TAKE_OVER_DIGITAL.
     */
    fun mayRecord(action: Action): Boolean =
        action == Action.START_CALL || action == Action.TAKE_OVER_DIGITAL
}
