package com.d_drostes_apps.aevum.automation.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.d_drostes_apps.aevum.domain.call.CallDetectionPolicy
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import com.d_drostes_apps.aevum.util.BackgroundNotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ════════════════════════════════════════════════════════════════════════
 * M26: ANRUF-ERKENNUNG — Vordergrunddienst mit Telefonie-Callback
 * ════════════════════════════════════════════════════════════════════════
 *
 * User-Spezifikation: „Immer während ein Anruf läuft wird die Activity
 * direkt aufgezeichnet […] es soll auch aktuelle Aktivitäten unterbrechen,
 * die durch Digital-Nutzung stattfinden […] aber mehr darf nicht
 * unterbrochen werden." Die Rangfolge selbst liegt als reine Logik in
 * [CallDetectionPolicy] (JVM-getestet) — dieser Dienst ist nur die
 * Verdrahtung mit Android.
 *
 * ── WARUM EIN FOREGROUND-DIENST UND KEIN BROADCAST-RECEIVER ────────────
 *
 * `android.intent.action.PHONE_STATE` steht NICHT auf der
 * Implicit-Broadcast-Ausnahmeliste: ein im Manifest registrierter Receiver
 * bekommt den Broadcast ab Android 8 unzuverlässig bis gar nicht. Der
 * belastbare Weg ist ein Telefonie-Callback im lebenden Prozess
 * (`TelephonyManager.registerTelephonyCallback`, ab API 31; darunter
 * `PhoneStateListener`) — und damit der Prozess lebt, braucht es einen
 * Vordergrunddienst. Beides zusammen ist die dokumentierte Variante für
 * `READ_PHONE_STATE`.
 *
 * ── LEBENSZYKLUS (vier Beine, Muster wie AppTrackingService) ───────────
 *
 *  1. App-Prozess-Start ([com.d_drostes_apps.aevum.AevumApplication])
 *  2. Einstellungen (Toggle „Anruf-Erkennung" an)
 *  3. Boot / App-Update ([com.d_drostes_apps.aevum.automation.geofence.BootReceiver])
 *  4. [CallDetectionWatchdogWorker] (15-Minuten-Takt) + START_STICKY
 *
 * Der Dienst beendet sich selbst, sobald der Schalter aus ist ODER die
 * Berechtigung fehlt — beides mit Logzeile (kein stiller Ausfall).
 */
@AndroidEntryPoint
class CallDetectionService : Service() {

    @Inject lateinit var settingsRepository: AutomationSettingsRepository
    @Inject lateinit var liveActivityManager: LiveActivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telephonyCallback: Any? = null
    private var listening = false
    private var startedAtMs = 0L

    /** Von diesem Dienst gestartete Session (für das Ende des Gesprächs). */
    @Volatile private var activeCallSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        startedAtMs = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FGS-Vertrag: startForeground MUSS synchron und sofort laufen,
        // sonst RemoteServiceException/Prozess-Kill (M18.121/122-Lehre).
        startForegroundCompat()
        scope.launch { syncListener() }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        unregisterListener()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ====================================================================
    // Gate + Registrierung
    // ====================================================================

    /**
     * Schalter + Berechtigung prüfen und den Callback registrieren.
     * Fehlt eines von beidem, beendet sich der Dienst — mit Logzeile, damit
     * „es zeichnet nichts auf" im Feld diagnostizierbar bleibt.
     */
    private suspend fun syncListener() {
        val settings = readSettings()
        if (!settings.callDetectionEnabled) {
            Log.i(TAG, "Anruf-Erkennung aus — Dienst beendet sich")
            stopSelf()
            return
        }
        if (!hasPhonePermission()) {
            Log.i(
                TAG,
                "READ_PHONE_STATE fehlt — Anruf-Erkennung inaktiv. " +
                    "Berechtigung in den Einstellungen erteilen."
            )
            stopSelf()
            return
        }
        if (!listening) registerListener()
        // Diagnose: lebt die Registrierung wirklich? (Der Dienst kann vom
        // System wiederbelebt werden, ohne dass der Callback noch hängt.)
        Log.i(TAG, "Anruf-Erkennung aktiv (Registrierung=$listening, Start=${if (startedAtMs > 0) "ok" else "?"})")
    }

    private suspend fun readSettings(): AutomationSettings =
        try {
            settingsRepository.get().first() ?: AutomationSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Einstellungen nicht lesbar — nehme Defaults (Anruf-Erkennung aus)", e)
            AutomationSettings()
        }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun registerListener() {
        val telephony = getSystemService(TelephonyManager::class.java)
        if (telephony == null) {
            Log.e(TAG, "Kein TelephonyManager — Anruf-Erkennung nicht möglich")
            stopSelf()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleCallState(state)
                }
                telephony.registerTelephonyCallback(mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        handleCallState(state)
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = listener
            }
            listening = true
        } catch (e: Exception) {
            // SecurityException bei entzogener Berechtigung, RuntimeException
            // bei fehlender Telefonie-Hardware — beides kein Crashgrund.
            Log.e(TAG, "Registrierung des Telefonie-Callbacks fehlgeschlagen", e)
            listening = false
            stopSelf()
        }
    }

    private fun unregisterListener() {
        val callback = telephonyCallback ?: return
        telephonyCallback = null
        listening = false
        try {
            val telephony = getSystemService(TelephonyManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (callback as? TelephonyCallback)?.let { telephony.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                (callback as? PhoneStateListener)?.let {
                    @Suppress("DEPRECATION")
                    telephony.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Abmelden des Callbacks fehlgeschlagen: ${e.message}")
        }
    }

    // ====================================================================
    // Zustandswechsel
    // ====================================================================

    private fun handleCallState(state: Int) {
        when (state) {
            // Gespräch läuft (abgehend oder angenommen). RINGING ist bewusst
            // NICHT dabei: „während ein Anruf läuft" heißt Gespräch, nicht
            // Klingeln — ein abgelehnter Anruf soll nichts aufzeichnen.
            TelephonyManager.CALL_STATE_OFFHOOK -> scope.launch { onCallStarted() }
            TelephonyManager.CALL_STATE_IDLE -> scope.launch { onCallFinished() }
            else -> Unit
        }
    }

    private suspend fun onCallStarted() {
        val settings = readSettings()
        if (!settings.callDetectionEnabled) return
        val live = liveActivityManager.liveSession.value
        val action = CallDetectionPolicy.onCallActive(
            enabled = true,
            liveSourceType = live?.sourceType,
            liveIsLive = live?.isLive == true
        )
        Log.i(TAG, "Anruf läuft — Entscheidung=$action (live=${live?.sourceType ?: "nichts"})")
        if (!CallDetectionPolicy.mayRecord(action)) return
        try {
            val session = liveActivityManager.start(
                activityTypeId = settings.callActivityTypeId,
                sourceType = CallDetectionPolicy.SOURCE_CALL_AUTO
            )
            activeCallSessionId = session.id
            // Banner/Notification der laufenden Aufzeichnung (wie beim
            // PC-Fernstart M23): ohne diesen Start bliebe die Session zwar
            // in der DB, aber im System unsichtbar.
            LiveActivityService.start(this)
            Log.i(TAG, "Anruf-Aufzeichnung gestartet (Typ=${settings.callActivityTypeId}, $action)")
        } catch (e: Exception) {
            Log.e(TAG, "Anruf-Aufzeichnung konnte nicht starten", e)
        }
    }

    private suspend fun onCallFinished() {
        val live = liveActivityManager.liveSession.value
        val action = CallDetectionPolicy.onCallEnded(
            liveSourceType = live?.sourceType,
            liveIsLive = live?.isLive == true
        )
        val target = activeCallSessionId ?: live?.id
        activeCallSessionId = null
        if (action != CallDetectionPolicy.EndAction.STOP_OWN || target == null) {
            Log.i(TAG, "Anruf beendet — nichts zu beenden (live=${live?.sourceType ?: "nichts"})")
            return
        }
        // stopIfLive: Hat zwischenzeitlich eine andere Quelle übernommen
        // (manueller Start, Geofence, Kalender), bleibt sie unangetastet.
        val stopped = liveActivityManager.stopIfLive(target)
        Log.i(TAG, "Anruf beendet — eigene Aufzeichnung ${if (stopped) "gestoppt" else "war nicht mehr live"}")
    }

    // ====================================================================
    // Vordergrund-Benachrichtigung (konsolidiert, M19)
    // ====================================================================

    private fun startForegroundCompat() {
        BackgroundNotificationHelper.ensureChannel(this)
        val notification = BackgroundNotificationHelper.buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    BackgroundNotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(BackgroundNotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground fehlgeschlagen — stopSelf", e)
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "CallDetection"

        /** ACTION für interne Starts (null-Action bleibt dem Rebirth vorbehalten). */
        const val ACTION_INTERNAL_START = "com.d_drostes_apps.aevum.action.CALL_DETECTION_START"

        const val EXTRA_START_REASON = "call_start_reason"

        /** Lebt der Dienst? (Watchdog/Diagnose) */
        @Volatile
        var alive: Boolean = false
            private set

        /** Startet den Dienst aus jedem Kontext (Fehler werden protokolliert). */
        fun start(context: Context, reason: String) {
            val intent = Intent(context, CallDetectionService::class.java)
                .setAction(ACTION_INTERNAL_START)
                .putExtra(EXTRA_START_REASON, reason)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                val blocked = e.javaClass.name.contains("ForegroundServiceStartNotAllowed")
                if (blocked) {
                    Log.w(
                        TAG,
                        "START($reason) blockiert — Android-Hintergrund-Startverbot. " +
                            "Ausnahme „Akku-Optimierung aus\" setzen."
                    )
                } else {
                    Log.w(TAG, "START($reason) fehlgeschlagen: ${e.message}")
                }
            }
        }

        /** Einstiegspunkt aller Wiederbelebungs-Pfade (idempotent). */
        fun ensureAlive(context: Context, reason: String) {
            if (alive) return
            start(context, reason)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallDetectionService::class.java))
        }
    }
}
