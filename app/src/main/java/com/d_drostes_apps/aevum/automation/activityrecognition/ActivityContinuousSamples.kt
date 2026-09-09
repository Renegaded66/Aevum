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

            when (top.type) {
                DetectedActivity.IN_VEHICLE -> {
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
                    if (bridge.isWalkingEnabled() && !bridge.isDriveActive()) {
                        DriveDetectionService.start(
                            context,
                            DriveDetectionService.ACTION_WALKING_CHECK
                        )
                    }
                }
                DetectedActivity.ON_BICYCLE -> {
                    // Radfahren ist ein Fahr-Verdacht im breitesten Sinn —
                    // der CONFIRM-Burst verwirft ihn über die 8-m/s-Gates
                    // zuverlässig (keine Session, aber schnellste Reaktion
                    // für den Fall, dass der Speed-Sensor doch Auto sieht).
                    if (bridge.isDrivingEnabled() && !bridge.isDriveActive()) {
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
