package com.d_drostes_apps.aevum.automation.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M13: Screen On/Off Receiver.
 *
 * Tracks whenever the user turns the screen on or off. Primary signal
 * for sleep detection on devices WITHOUT Health Connect.
 *
 * Sleep heuristic (in [SleepHeuristicEngine]):
 * - "Schlaf-Kandidat" entsteht, wenn der Screen zwischen 22:00 und 08:00
 *   für mindestens 4 Stunden aus bleibt.
 * - Endzeit = erste Screen-On nach dem langen Aus-Periode, oder "jetzt + Schlafbeginn".
 * - Der Vorschlag landet in der Review-Inbox mit `confidence = 0.55` (User muss bestätigen).
 *
 * Health Connect bleibt als zusätzliche Quelle aktiv (M12.2 SleepImportWorker).
 *
 * M12.1.1: Exceptions werden jetzt geloggt statt verschluckt. Wenn der
 * Hilt EntryPoint scheitert, sehen wir das im Logcat. Außerdem wird
 * `init()` defensiv vor `insert()` aufgerufen, damit `appContext`
 * garantiert gesetzt ist.
 */
@AndroidEntryPoint
class ScreenEventReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun screenEventRepository(): ScreenEventRepository
        fun sleepHeuristicEngine(): SleepHeuristicEngine
        fun liveActivityManager(): LiveActivityManager
        fun automationSettingsDao(): com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
    }

    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val type = when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> "OFF"
            Intent.ACTION_SCREEN_ON -> "ON"
            Intent.ACTION_USER_PRESENT -> "UNLOCK"
            else -> return
        }
        Log.d(TAG, "Received $type at $now")
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val appContext = context.applicationContext
                val deps = EntryPointAccessors.fromApplication(appContext, Deps::class.java)
                val repo = deps.screenEventRepository()
                // M12.1.1: init defensiv vor insert aufrufen, damit
                // appContext garantiert gesetzt ist, bevor prefs() genutzt wird.
                repo.init(appContext)
                repo.insert(ScreenEvent(type = type, timestamp = now))
                Log.d(TAG, "Stored $type at $now (total=${repo.readAll().size})")

                // M16: Die einfache M13-Heuristik aufrufen (Screen-only).
                // Das reicht für den Basis-Fall, aber nicht für die 3-Signal-Fusion.
                deps.sleepHeuristicEngine().init(appContext)
                deps.sleepHeuristicEngine().analyzeLatest()

                // M16: Bei Screen-ON / UNLOCK zusätzlich den SleepFusionWorker
                // enqueuen. Der prüft alle drei Signale (Screen + STILL + Digital)
                // und ist die zuverlässigere Erkennung. Bei Screen-OFF ist eine
                // Fusion sinnlos (die Nacht ist noch nicht vorbei), also nur
                // bei ON/UNLOCK. ExistingWorkPolicy.KEEP verhindert Doppel-Enqueue.
                if (type == "ON" || type == "UNLOCK") {
                    try {
                        val request = androidx.work.OneTimeWorkRequestBuilder<SleepFusionWorker>()
                            .setInitialDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        androidx.work.WorkManager.getInstance(appContext)
                            .enqueueUniqueWork(
                                SleepFusionWorker.WORK_NAME,
                                androidx.work.ExistingWorkPolicy.KEEP,
                                request
                            )
                    } catch (e: Exception) {
                        Log.w(TAG, "SleepFusionWorker enqueue failed for $type", e)
                    }
                }

                // M18.70: Bildschirm-Aufzeichnung („Digital").
                // ON  → Worker mit Delay = x Minuten enqueuen (x = Vorlauf).
                //       Bei x = 0 feuert er sofort. Der Worker prüft beim
                //       Feuern erneut: Screen noch an? nichts anderes live?
                // OFF → M18.71: NICHT sofort stoppen — erst nach 30 s
                //       Screen-Aus (ScreenOffStopWorker). Kommt vorher ein
                //       Screen-ON/UNLOCK, wird der Stop-Worker gecancelt
                //       und die Aufzeichnung läuft weiter (kurzes
                //       Ausschalten: Tasche, Anruf, Display-Taste).
                try {
                    val settings = deps.automationSettingsDao().getSettingsSync()
                    val minutes = settings?.screenRecordingMinutes ?: 5
                    if (type == "ON" || type == "UNLOCK") {
                        // Screen ist wieder an → ausstehenden 30s-Stop canceln.
                        androidx.work.WorkManager.getInstance(appContext)
                            .cancelUniqueWork(com.d_drostes_apps.aevum.automation.screen.ScreenOffStopWorker.WORK_NAME)
                        if (minutes != com.d_drostes_apps.aevum.automation.screen.ScreenRecordingEngine.DEACTIVATED) {
                            val delay = minutes.coerceAtLeast(0).toLong()
                            val request = androidx.work.OneTimeWorkRequestBuilder<com.d_drostes_apps.aevum.automation.screen.ScreenRecordingWorker>()
                                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MINUTES)
                                .build()
                            androidx.work.WorkManager.getInstance(appContext)
                                .enqueueUniqueWork(
                                    com.d_drostes_apps.aevum.automation.screen.ScreenRecordingWorker.WORK_NAME,
                                    androidx.work.ExistingWorkPolicy.REPLACE,
                                    request
                                )
                            Log.d(TAG, "Screen-Aufzeichnung geplant (Vorlauf=${minutes}min)")
                        }
                    } else if (type == "OFF") {
                        androidx.work.WorkManager.getInstance(appContext)
                            .cancelUniqueWork(com.d_drostes_apps.aevum.automation.screen.ScreenRecordingWorker.WORK_NAME)
                        // M18.71: Laufende SCREEN_AUTO-Session erst nach 30s
                        // Screen-Aus stoppen (nicht sofort). Der Delay-Worker
                        // prüft beim Feuern, ob der Screen immer noch aus ist.
                        val live = deps.liveActivityManager().liveSession.value
                        if (live != null && live.isLive && live.sourceType == "SCREEN_AUTO") {
                            com.d_drostes_apps.aevum.automation.screen.ScreenOffStopWorker.schedule(appContext)
                            Log.d(TAG, "Screen-Aufzeichnung: Stop in 30s geplant (Screen OFF)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Screen-Aufzeichnung handling failed for $type", e)
                }
            } catch (e: Exception) {
                // M12.1.1: nicht mehr still verschlucken — loggen, damit
                // der Datenfluss bei einem realen Gerät nachvollziehbar bleibt.
                Log.e(TAG, "Failed to handle screen event $type", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "ScreenEventReceiver"
    }
}

data class ScreenEvent(
    val type: String, // "ON" | "OFF" | "UNLOCK"
    val timestamp: Long,
    // M16.3: Herkunft des Events — beeinflusst die Wake-Time-Priorität.
    //   "BROADCAST" (Default): echtes System-Event vom SCREEN_ON/OFF/USER_PRESENT.
    //   "LIFECYCLE": aus dem ActivityLifecycleCallbacks-Fallback (App in Vordergrund).
    //   "USAGE_STATS": aus UsageStatsManager (für spätere Erweiterung).
    // Beim Pairing werden BROADCAST/UNLOCK-Events gegenüber LIFECYCLE bevorzugt,
    // damit das Aufwachen aus echter Bildschirm-Nutzung erkannt wird und nicht
    // aus dem Zeitpunkt, an dem die App geöffnet wurde.
    val source: String = "BROADCAST"
)

/**
 * Lightweight SharedPreferences-backed storage for screen events.
 * Keeps the most recent 200 events. No new Room table — fewer migrations.
 */
@Singleton
class ScreenEventRepository @Inject constructor() {
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun insert(event: ScreenEvent) {
        val p = prefs() ?: return
        val events = readAll().toMutableList()
        events.add(event)
        val trimmed = if (events.size > MAX_EVENTS) events.takeLast(MAX_EVENTS) else events
        // M16.3: Source-Feld in der Serialisierung mitführen.
        val serialized = trimmed.joinToString("\n") { "${it.type}|${it.timestamp}|${it.source}" }
        p.edit().putString(KEY_EVENTS, serialized).apply()
    }

    fun readAll(): List<ScreenEvent> {
        val p = prefs() ?: return emptyList()
        val raw = p.getString(KEY_EVENTS, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            if (line.isBlank()) null
            else {
                val parts = line.split("|")
                // M16.3: Backwards-compatible Parsing. Alte Einträge haben 2
                // Felder (type|timestamp) — source wird als "BROADCAST"
                // default interpretiert. Neue Einträge haben 3 Felder.
                when (parts.size) {
                    2 -> {
                        val t = parts[1].toLongOrNull() ?: return@mapNotNull null
                        ScreenEvent(parts[0], t)
                    }
                    3 -> {
                        val t = parts[1].toLongOrNull() ?: return@mapNotNull null
                        ScreenEvent(parts[0], t, parts[2])
                    }
                    else -> null
                }
            }
        }
    }

    fun readSince(sinceMs: Long): List<ScreenEvent> = readAll().filter { it.timestamp >= sinceMs }

    fun clear() {
        prefs()?.edit()?.remove(KEY_EVENTS)?.apply()
    }

    companion object {
        private const val PREFS_NAME = "aevum_screen_events"
        private const val KEY_EVENTS = "events_v1"
        private const val MAX_EVENTS = 200
    }
}
