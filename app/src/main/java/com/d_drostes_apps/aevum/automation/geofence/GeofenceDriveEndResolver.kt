package com.d_drostes_apps.aevum.automation.geofence

import com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine
import com.d_drostes_apps.aevum.data.model.PlaceGeofence

// ══════════════════════════════════════════════════════════════════════
// M18.114: GEOFENCE-RE-ENTER NACH FAHRT-ENDE
//
// Bug (User-Report, Kanban t_d6639d07): User betritt einen Geofence mit
// autoStartActivityTypeId → Geofence-Activity startet. Auf dem Weg
// dorthin wird eine Autofahrt erkannt (oder der Geofence-EXIT selbst
// triggert den CONFIRM-Burst) → Drive-Session (ACTIVITY_RECOGNITION_AUTO,
// "driving") override'd die Geofence-Session. Endet die Fahrt (Watchdog/
// Google-EXIT/manueller Stop), startet die Geofence-Activity NICHT wieder,
// obwohl der User nachweislich in der Zone steht.
//
// Root Cause: Beide Auto-Start-Pfade (GeofenceTransitionProcessor und
// CurrentZoneProvider.checkNow) feuern nur bei einem Zonenevent (ENTER/
// ZONENWECHSEL). Beim Drive-Start kam kein ENTER (User war schon in der
// Zone oder in deren Nähe — Zonenradius ⊂ Fahrt-Umweg), beim Drive-Ende
// kommt keiner mehr (kein GMS-Transition, kein Zonenwechsel). Der
// prev_zone_id-Status des CurrentZoneProvider bleibt auf dem Geofence
// (der GMS-EXIT fehlt oft, weil der Drive-Start außerhalb des Radius
// lag und der Weg zurück in den Kreis nie eine Grenze gekreuzt hat) —
// und selbst ein geloggt-EXIT würde den Autostop-Pfad (stoppen, JA)
// mit dem Re-Enter-Pfad (starten, NEIN) kombinieren.
//
// Lösung (Kanban t_d6639d07, Ansatz "Re-Evaluierung bei Fahrt-Ende"):
// Beide Stop-Pfade des Drive-Workers (DriveStopWorker bei Google-EXIT,
// DriveWatchdogWorker bei Stillstand/5-Min-Regel) rufen nach dem
// Session-Stop einen DriveEnd-Geofence-Check auf: Wenn der letzte GPS-Fix
// in einem nicht gelöschten, aktivierten Geofence mit
// autoStartActivityTypeId liegt UND keine Live-Session läuft →
// Geofence-Session neu starten (sourceType GEOFENCE_AUTO, Titel =
// ActivityType-Name wie der ENTER-Pfad, M18.66-FIX9).
//
// Der Resolver selbst ist PURE JVM (keine Android-Klassen) — die
// Entscheidung (welcher Geofence, welche Gate-Bedingungen) ist hier
// testbar; der Worker macht nur noch den DB-Read + Session-Start.
// ══════════════════════════════════════════════════════════════════════

/**
 * M18.114: Reine Entscheidung, ob nach einem Drive-Ende die Geofence-
 * Activity neu gestartet werden soll.
 *
 * Alle Gate-Bedingungen an EINER Stelle (JVM-testbar):
 *  1. Kein passender Geofence → NoRestart.
 *  2. Geofence deaktiviert oder gelöscht → NoRestart (kein Auto-Start
 *     für ausgeschaltete Zonen — Konsistenz mit ENTER-Pfad).
 *  3. Kein autoStartActivityTypeId konfiguriert → NoRestart.
 *  4. Letzter GPS-Fix liegt außerhalb des Kreises (User ist weggefahren,
 *     nicht nur ausgestiegen) → NoRestart (Kanban-AK: "kein Restart,
 *     wenn User die Zone verlassen hat").
 *  5. Fix zu alt (> [MAX_FIX_AGE_MS]) → NoRestart (keine Stale-Evidenz).
 *  6. Fix-Genauigkeit schlechter als [MAX_ACCURACY_M] → NoRestart
 *     (Indoor-Drift-Fixes dürfen keine Sessions starten).
 *  7. Es läuft noch eine Live-Session (Auto-Stop-Worker feuert nach
 *     dem Drive-Stop noch einmal — dann läuft schon die Geofence-Session
 *     oder eine andere) → NoRestart.
 *  8. Sonst → Restart mit dem Geofence- und Activity-Typ.
 */
object GeofenceDriveEndResolver {

    /** Max. Alter des letzten GPS-Fixes (ms). DriveStopWorker feuert
     *  i. d. R. < 1 Min nach dem letzten Stream-Fix (Google-EXIT) bzw.
     *  < 5 Min nach dem letzten Watchdog-Signal; 10 Min decken beide
     *  Pfade + Worker-Verzögerungen ab, ohne Stale-Evidenz zu akzeptieren. */
    const val MAX_FIX_AGE_MS: Long = 10L * 60 * 1000

    /** Max. GPS-Genauigkeit des letzten Fixes (m) — gleiche Schwelle wie
     *  DriveDetectionEngine.MAX_ACCURACY_M (Indoor-Drift-Filter). */
    const val MAX_ACCURACY_M: Float = 50f

    sealed class Decision {
        /** Geofence-Session neu starten (type = autoStartActivityTypeId). */
        data class Restart(val geofenceId: String, val type: String) : Decision()
        /** Kein Restart (kein Kandidat / Gate blockiert). */
        data object NoRestart : Decision()
    }

    /**
     * Reine Entscheidungsfunktion — keine Android-Dependencies.
     *
     * @param geofences alle nicht gelöschten Geofences (aktiv + deaktiviert)
     * @param fixLat/Lon letzter GPS-Fix (null = kein Fix → kein Restart)
     * @param fixAccuracyMs Genauigkeit des Fixes in Metern
     * @param fixAtMs Zeitstempel des Fixes
     * @param nowMs aktuelle Zeit
     * @param liveSessionTypeId activityTypeId der laufenden Live-Session (null = keine)
     * @param liveSessionIsLive true, wenn die Session RUNNING/PAUSED ist
     */
    fun resolve(
        geofences: List<PlaceGeofence>,
        fixLat: Double?,
        fixLon: Double?,
        fixAccuracyM: Float?,
        fixAtMs: Long,
        nowMs: Long,
        liveSessionTypeId: String?,
        liveSessionIsLive: Boolean
    ): Decision {
        // Gate 1: Live-Session läuft → nichts starten (Kollisionsschutz).
        if (liveSessionIsLive) return Decision.NoRestart

        // Gate 2: Kein brauchbarer GPS-Fix.
        if (fixLat == null || fixLon == null || fixAccuracyM == null) {
            return Decision.NoRestart
        }
        if (nowMs - fixAtMs > MAX_FIX_AGE_MS) return Decision.NoRestart
        if (fixAccuracyM > MAX_ACCURACY_M) return Decision.NoRestart

        // Gate 3: Passender Geofence (Zonen-Mitgliedschaft über Haversine,
        // identische Formel wie CurrentZoneProvider/DriveDetectionEngine).
        // Bewusst über ALLE nicht gelöschten Geofences iterieren und den
        // passenden per ID suchen — der letzte GPS-Fix kann nach einer
        // Fahrt in EINER von mehreren Zonen liegen (z. B. Zuhause nach
        // der Heimfahrt).
        for (gf in geofences) {
            if (gf.deletedAt != null) continue
            if (!gf.enabled) continue
            val autoType = gf.autoStartActivityTypeId ?: continue
            if (fixAccuracyM <= gf.radiusMeters * 0 + gf.radiusMeters &&
                haversineMeters(fixLat, fixLon, gf.latitude, gf.longitude) <= gf.radiusMeters
            ) {
                // Passender, aktivierter Geofence mit Auto-Start gefunden.
                return Decision.Restart(gf.id, autoType)
            }
        }
        return Decision.NoRestart
    }

    /** Haversine-Distanz in Metern — identische Formel wie überall in
     *  Aevum (CurrentZoneProvider, DriveDetectionService, DriveWorkers). */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}