package com.d_drostes_apps.aevum.automation.activityrecognition

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.EntryPointAccessors

// ══════════════════════════════════════════════════════════════════════
// M18.112: AR-CONTINUOUS-SAMPLES — die Life360-Start-Latenz-Lösung.
//
// PROBLEM: requestActivityTransitionUpdates liefert Events erst, wenn
// Google den Aktivitäts-WECHSEL bestätigt hat ("The latency of event
// detection might vary by device", Android-Doku). Im Hintergrund kann
// ein IN_VEHICLE-ENTER Sekunden bis Minuten spät kommen — oder ganz
// ausbleiben (Dann greift der 10-Min-Fallback-Check). Life360 startet
// die Fahrtaufzeichnung nach ~5-15s, weil es die KONTINUIERLICHEN
// Activity-Samples nutzt: Die liefen bei Bewegung im Sekunden-Takt,
// OHNE GPS-Chip (nur Low-Power-Sensor-Stream, Doku: "It only makes use
// of low power sensors in order to keep the power usage to a minimum").
//
// WICHTIG (False-Positive-Schutz Zuhause, Doku-belegt): "To conserve
// battery, activity reporting may stop when the device is 'STILL' for
// an extended period of time. It will resume once the device moves
// again (Significant Motion)." → Zuhause still sitzend liefert dieser
// Stream KEINE Events und verbraucht ~nichts; beim Losfahren startet
// er von selbst. Die Samples ersetzen keine Gates — sie starten nur
// den GPS-CONFIRM-Burst FRÜHER. Die DriveDetectionEngine-Gates
// (Netto-Displacement, Geofence-Veto, Spread, 2er-Kette) entscheiden
// unverändert über jeden Start.
//
// Verdrahtung: requestActivityUpdates(30_000L, pendingIntent) beim
// App-Start (AevumApplication), nach Permission-Grant (TriggerSettings)
// und nach Boot (BootReceiver) — idempotent (gleicher PendingIntent,
// FLAG_UPDATE_CURRENT, GMS dedupliziert identische Requests).
// ══════════════════════════════════════════════════════════════════════

object ActivityContinuousSamplesRequester {

    const val ACTION_AR_CONTINUOUS_SAMPLE =
        "com.d_drostes_apps.aevum.AR_CONTINUOUS_SAMPLE"
    private const val REQUEST_CODE = 9102
    private const val TAG = "ArContinuousSamples"

    /** 30s detectionInterval: Kompromiss aus Start-Latenz und Wakes.
     *  Bewegte Szenarien liefern real öfter (andere Apps + Aktivitäts-
     *  wechsel-Dringlichkeit), STILL wird vom System pausiert — die
     *  Wake-Kosten sind also praktisch nur bei echter Bewegung. */
    private const val DETECTION_INTERVAL_MS = 30_000L

    fun register(context: Context) {
        if (!ActivityRecognitionPermission.isGranted(context)) {
            Log.d(TAG, "ACTIVITY_RECOGNITION nicht gewährt — Continuous-Samples übersprungen")
            return
        }
        val pendingIntent = continuousPendingIntent(context)
        try {
            ActivityRecognition.getClient(context)
                .requestActivityUpdates(DETECTION_INTERVAL_MS, pendingIntent)
            Log.d(TAG, "Continuous-AR-Samples registriert (Intervall ${DETECTION_INTERVAL_MS / 1000}s)")
        } catch (e: Exception) {
            Log.w(TAG, "requestActivityUpdates fehlgeschlagen: ${e.message}")
        }
    }

    /** Gleicher PendingIntent-Code wie der Registrar der Transitions
     *  bewusst NICHT (9002) — eigener Code, damit Remove/Replace der
     *  Transition-Registrierung den Continuous-Stream nie berührt. */
    private fun continuousPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ActivityContinuousSamplesReceiver::class.java).apply {
                action = ACTION_AR_CONTINUOUS_SAMPLE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    /** M18.127: Continuous-Stream sauber beenden (removeActivityUpdates) —
     *  API-Vertrag: „make sure to call removeActivityUpdates when you no
     *  longer need it“. Aufgerufen vom Trigger-Settings-Screen, wenn ALLE
     *  AR-abhängigen Automatisierungen (driving + walking + bicycle)
     *  deaktiviert sind. Idempotent: GMS toleriert Remove ohne aktive
     *  Registrierung; ohne Permission No-Op (nie registriert). */
    fun unregister(context: Context) {
        if (!ActivityRecognitionPermission.isGranted(context)) {
            Log.d(TAG, "ACTIVITY_RECOGNITION nicht gewährt — Continuous-Samples nicht registriert, Remove übersprungen")
            return
        }
        // Identity des Remove-PendingIntents muss exakt der Registrierung
        // entsprechen (RequestCode + Intent + Component), sonst entfernt
        // GMS nichts. getBroadcast ist hier nur eine Identitäts-Fabrik —
        // kein Broadcast wird gesendet, kein Sender-Empfänger-Problem.
        val pendingIntent = continuousPendingIntent(context)
        try {
            ActivityRecognition.getClient(context)
                .removeActivityUpdates(pendingIntent)
            Log.d(TAG, "Continuous-AR-Samples entfernt")
        } catch (e: Exception) {
            Log.w(TAG, "removeActivityUpdates fehlgeschlagen: ${e.message}")
        }
    }
}

/**
 * Empfängt die kontinuierlichen Activity-Samples (kein Transition-Event!)
 * und leitet sie als VERDACHTS-Signale in die Burst-Pipeline:
 *   IN_VEHICLE → CONFIRM-Burst (DriveDetectionEngine entscheidet)
 *   WALKING / RUNNING → WALKING_CHECK-Burst (nur wenn nicht fahrend)
 *   ON_BICYCLE → CONFIRM-Burst (hohe Speed-Gates entscheiden)
 *   STILL / UNKNOWN / Sonstiges -> nichts (Zuhause-Ruhe).
 *
 * M18.108-Muster: try/catch um den GMS-Pfad (3rd-party-Parsing) —
 * ein Fehler hier darf den Prozess NIE killen (Receiver läuft auf dem
 * Main-Thread des App-Prozesses).
 */
class ActivityContinuousSamplesReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionPermission.isGranted(context)) return
        if (intent.action != ActivityContinuousSamplesRequester.ACTION_AR_CONTINUOUS_SAMPLE) return
        try {
            val result = com.google.android.gms.location.ActivityRecognitionResult.extractResult(intent)
                ?: return
            val top = result.mostProbableActivity ?: return
            val bridge = EntryPointAccessors.fromApplication(
                context.applicationContext,
                ActivityRecognitionBridgeProvider::class.java
            ).activityRecognitionBridge()
            // M18.135: Die Live-Session für die Stop-Trigger-Gates (siehe
            // WALKING/RUNNING-Zweig) — dieselbe Quelle wie im Service.
            val liveActivityManager = EntryPointAccessors.fromApplication(
                context.applicationContext,
                ActivityRecognitionBridgeProvider::class.java
            ).liveActivityManager()

            when (top.type) {
                DetectedActivity.IN_VEHICLE -> {
                    // M18.117: Motion-Kontext für das Motion-Gate melden
                    // (IN_VEHICLE → 8-m/s-Schwelle bleibt).
                    bridge.updateMotionContext(DriveDetectionEngine.MotionContext.IN_VEHICLE)
                    // M18.128: Fahrzeug-Evidence für das Fast-Start-Gate
                    // registrieren (Confidence + Frische entscheidet die
                    // pure Funktion — einzeln nie ein Start-Beweis).
                    bridge.onVehicleSample(top.confidence)
                    // M18.127: Fahrzeug-Sample bestätigt die Fahrt →
                    // Walk-Stop-Evidenz verwerfen (M18.84: Google meldet
                    // WALKING auch während Stop&Go-Fahrten).
                    bridge.resetWalkStopEvidence()
                    // Fahrzeug-Sample = Fahrzeug-Verdacht → GPS-CONFIRM-Burst.
                    // Die Engine-Gates entscheiden über den Start (kein
                    // direkter Session-Start hier!).
                    if (bridge.isDrivingEnabled() && !bridge.isDriveActive()) {
                        DriveDetectionService.start(
                            context,
                            DriveDetectionService.ACTION_CONFIRM
                        )
                    }
                }
                DetectedActivity.WALKING, DetectedActivity.RUNNING -> {
                    // M18.117: Motion-Kontext für das Motion-Gate melden
                    // (ON_FOOT → 12-m/s-Schwelle, Joggen-Spikes zählen
                    // nicht mehr als Fahrt).
                    bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_FOOT)
                    // M18.127: Läuft gerade eine Auto-Session, wird dieses
                    // Geh-Sample zur WALK-STOP-EVIDENZ: 2 Samples à 30 s
                    // (Confidence ≥ 60, ~75 s Gnadenfrist, widerlegt durch
                    // frisches Fahrzeug-Tempo via GPS-Probes) → sofortiger
                    // Session-Stop über den bestehenden DriveStopWorker.
                    // VORHER (die Lücke): Walking während aktiver Fahrt
                    // wurde ignoriert — die Session endete erst nach 5
                    // Minuten ohne Signal.
                    //
                    // M18.135 (Kanban t_8e2889cd): Das Gate liest jetzt die
                    // LIVE-SESSION (Fahrt ODER automatische Radfahrt),
                    // nicht mehr nur `isDriveActive()`. Für eine
                    // radfahren-Session ist dieses Flag false (nur
                    // markDriveConfirmed setzt es) — der Walk-Stop-Trigger
                    // war deshalb geschlossen, obwohl der DETEKTOR korrekt
                    // feuerte: Eine Radfahrt endete nur über den
                    // 5-Minuten-Watchdog, nicht über „abgestellt + geht".
                    // M18.75/M18.76-Lehre: Session-Zustand direkt lesen,
                    // kein zweites Flag pflegen.
                    val autoSession = liveActivityManager.liveSession.value
                    if (bridge.isDriveActive() || isLiveAutoTrackedSession(autoSession)) {
                        if (bridge.onWalkStopSample(top.type, top.confidence)) {
                            Log.i(
                                "ArContinuousSamples",
                                "WalkStop-Detector: Gehen ≥ ${WalkStopDetector.WALK_STOP_CONFIDENCE} " +
                                    "für ≥ ${WalkStopDetector.WALK_STOP_GRACE_MS / 1000}s " +
                                    "während Auto-/Rad-Session → sofortiger Stop"
                            )
                            bridge.resetWalkStopEvidence()
                            DriveStopWorker.schedule(context)
                        }
                    } else if (bridge.isWalkingEnabled()) {
                        DriveDetectionService.start(
                            context,
                            DriveDetectionService.ACTION_WALKING_CHECK
                        )
                    }
                }
                DetectedActivity.ON_BICYCLE -> {
                    // M18.128: Radfahren widerlegt die Fahrzeug-Evidence
                    // (V1-Konkurrenz: frisches Rad-Signal des Sensor-Hubs).
                    bridge.onBicycleSample()
                    // M18.134 (Kanban t_a860c07f): Rad-Evidence MIT
                    // Confidence registrieren — sie qualifiziert die
                    // radfahren-Session, wenn das ON_BICYCLE-Gate der
                    // Engine den Auto-Start blockiert (Radfahren IST
                    // die Aktivität, die der User will).
                    bridge.onBicycleSampleWithConfidence(top.confidence)
                    // M18.127: Radfahren ist kein Gehen — Fahrt lebt.
                    bridge.resetWalkStopEvidence()
                    // M18.134: Motion-Kontext ON_BICYCLE melden — das
                    // M18.117-Gate gilt bisher nur für ON_FOOT/IN_VEHICLE.
                    // VORHER (der User-Bug): „bewusst KEIN
                    // updateMotionContext (UNKNOWN-Verhalten, 8 m/s)" —
                    // damit war 28,8 km/h die Fahrtschwelle, und jede
                    // Radfahrt mit 25-km/h-Schnitt (Antritte/Gefälle
                    // über 28,8 km/h) wurde als Autofahrt aufgezeichnet
                    // (gemessen: 29 km/h → Driving 500/500, 25 km/h +
                    // 30-s-Antritte → Driving 300/300).
                    // JETZT: ON_BICYCLE hebt die Auto-Schwelle auf
                    // 12 m/s (43,2 km/h) — rad-unmöglich, motorisiert
                    // ab 50 km/h unverändert erkannt. Die Confidence
                    // wird NICHT hier geprüft (der Kontext hat eine
                    // eigene 2-Sample-Hysterese in der Bridge); sie
                    // entscheidet über die Rad-Session im Start-Pfad.
                    // BEWUSST TOGGLE-FREI: Auch mit ausgeschalteter
                    // Rad-Erkennung darf 25 km/h Radfahren nicht als
                    // Autofahrt gelten (der Toggle steuert die Session,
                    // nicht die Klassifikations-Korrektheit).
                    bridge.updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)
                    // M18.134: Rad-Setting-Gate (wie der Transition-
                    // Receiver) — der UI-Toggle „Radfahren" muss auch
                    // hier wirken, nicht nur dort.
                    // Der CONFIRM-Burst ist der GPS-Stream, auf dem die
                    // Rad-Erkennung rechnet (detectBikeRide liest die
                    // Probe-Serie des Services) — er läuft deshalb,
                    // solange Fahr- ODER Rad-Erkennung an ist.
                    if ((bridge.isDrivingEnabled() || bridge.isBicycleEnabled()) &&
                        !bridge.isDriveActive()
                    ) {
                        DriveDetectionService.start(
                            context,
                            DriveDetectionService.ACTION_CONFIRM
                        )
                    }
                }
                else -> Unit // STILL / UNKNOWN / etc. — Zuhause-Ruhe, nichts tun
            }
        } catch (t: Throwable) {
            Log.e("ArContinuousSamples", "Continuous-Sample-Verarbeitung fehlgeschlagen — Broadcast verworfen", t)
        }
    }
}
