package com.d_drostes_apps.aevum.automation.activityrecognition

/**
 * M28: Auto-Fahrt vs. laufende Aufzeichnung — reine Entscheidungslogik.
 *
 * ── Der gemeldete Fehler ────────────────────────────────────────────────
 *
 * User: „Automatische Fahrtaufzeichnung darf keine laufenden Aufzeichnungen
 * unterbrechen. Das passiert aber aktuell."
 *
 * Ursache: [LiveActivityManager.start] beendet/kürzt die bestehende
 * Live-Session, damit nie zwei Blöcke parallel laufen. Der automatische
 * Fahrt-Start (DriveStartWorker) rief das ohne Rücksicht auf eine bereits
 * laufende Aufzeichnung auf — eine manuelle Aktivität, eine
 * Geofence-/App-/Digital-Aufzeichnung wurde also mitten im Block beendet,
 * sobald eine Fahrt erkannt wurde (zusätzlich rückdatiert auf den
 * Fahrt-Anker, was den alten Block sogar verkürzte).
 *
 * ── Die gewünschte Regel ────────────────────────────────────────────────
 *
 * User: „Aber das System darf wissen dass gerade gefahren wird und die
 * Aufzeichnung starten sobald die laufende aktivität gestoppt wird. Aber
 * natürlich nicht unendlich lange, also wenn die autofahrt aufhört und erst
 * danach die laufende andere aktivität gestoppt wird dann soll natürlich
 * nicht Autofahren starten."
 *
 * Daraus:
 *  1. Läuft eine andere Aufzeichnung → Fahrt nur VORMERKEN
 *     ([shouldDefer]), keine Session, kein Eingriff in den laufenden Block.
 *  2. Endet die Aufzeichnung → Fahrt starten, aber NUR wenn es in diesem
 *     Moment noch belastbare Fahrt-Evidenz gibt ([shouldStartAfterBlock]).
 *     Die Evidenz ist entweder eine frische GPS-Klassifikation oder eine
 *     höchstens [EVIDENCE_TTL_MS] alte Bestätigung (der 2-Minuten-Takt des
 *     DriveProbeWorkers hält sie während der Fahrt frisch). Steht das Auto
 *     schon länger, verfällt der Vormerk — genau der gewünschte Fall
 *     „Fahrt vorbei, danach erst gestoppt".
 *  3. [MAX_PENDING_AGE_MS] ist das harte Sicherheitsnetz gegen einen
 *     Vormerk, der nie aufgelöst wird (z. B. Prozess-Tod der blockierenden
 *     Session ohne Stop-Event).
 *
 * Bewusst pure Funktionen — ohne Android, ohne Zeitquelle, unit-testbar.
 */
object PendingDrivePolicy {

    /** activityTypeId der automatischen Fahrt. */
    const val DRIVE_TYPE_ID = "driving"

    /** sourceType der automatischen Fahrt. */
    const val DRIVE_SOURCE_TYPE = "ACTIVITY_RECOGNITION_AUTO"

    /**
     * Wie lange eine Fahrt-Bestätigung nach dem letzten Signal noch als
     * „das Auto fährt gerade" gilt.
     *
     * 6 Minuten = drei Läufe des 2-Minuten-DriveProbeWorkers. Größer als
     * zwei Taktlücken (eine verpasste Probe darf die Fahrt nicht verfallen
     * lassen), kleiner als eine typische Parkzeit vor dem nächsten Einkauf.
     */
    const val EVIDENCE_TTL_MS = 6L * 60_000L

    /** Harte Obergrenze für einen Vormerk (4 Stunden). */
    const val MAX_PENDING_AGE_MS = 4L * 60L * 60_000L

    /** Ist diese Live-Session die automatische Fahrt selbst? */
    fun isDriveSession(activityTypeId: String?, sourceType: String?): Boolean =
        activityTypeId == DRIVE_TYPE_ID && sourceType == DRIVE_SOURCE_TYPE

    /**
     * Darf der automatische Fahrt-Start JETZT keine Session anlegen?
     *
     * true, wenn irgendeine andere Aufzeichnung live ist (RUNNING oder
     * PAUSED) — sie wird nie unterbrochen. Eine bereits laufende
     * Fahrt-Session ist KEIN Block: Sie wird vom Duplikat-Schutz des
     * DriveStartWorkers behandelt (Herzschlag-Refresh).
     */
    fun shouldDefer(
        liveIsLive: Boolean,
        liveActivityTypeId: String?,
        liveSourceType: String?
    ): Boolean = liveIsLive && !isDriveSession(liveActivityTypeId, liveSourceType)

    /**
     * Soll die vorgemerkte Fahrt jetzt starten, nachdem die blockierende
     * Aufzeichnung beendet wurde?
     *
     * @param drivingEvidenceNow aktuelle GPS-Klassifikation ist „Driving"
     * @param lastEvidenceAtMs Zeitpunkt der letzten Fahrt-Evidenz (0 = keine)
     * @param pendingStartedAtMs Zeitpunkt des Vormerks (0 = kein Vormerk)
     * @param nowMs jetzt
     */
    fun shouldStartAfterBlock(
        drivingEvidenceNow: Boolean,
        lastEvidenceAtMs: Long,
        pendingStartedAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (pendingStartedAtMs <= 0L) return false
        if (nowMs - pendingStartedAtMs > MAX_PENDING_AGE_MS) return false
        if (drivingEvidenceNow) return true
        if (lastEvidenceAtMs <= 0L) return false
        val evidenceAge = nowMs - lastEvidenceAtMs
        return evidenceAge in 0..EVIDENCE_TTL_MS
    }

    /**
     * Start-Anker der nachgeholten Fahrt: exakt das Ende des Blocks, der
     * die Fahrt blockiert hat — nicht der ursprüngliche Fahrt-Anker.
     *
     * Sonst würde die Fahrt rückwirkend IN die andere Aufzeichnung
     * hineinlaufen und sie (über trimOverlappingForNewSession) erneut
     * zerschneiden — genau der Fehler, der behoben wird.
     */
    fun startAtAfterBlock(blockingEndAtMs: Long?, nowMs: Long): Long =
        (blockingEndAtMs ?: nowMs).coerceAtMost(nowMs)
}
