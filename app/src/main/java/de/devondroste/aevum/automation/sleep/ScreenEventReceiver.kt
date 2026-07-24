package de.devondroste.aevum.automation.sleep

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
                deps.sleepHeuristicEngine().init(appContext)
                deps.sleepHeuristicEngine().analyzeLatest()
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
    val timestamp: Long
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
        val serialized = trimmed.joinToString("\n") { "${it.type}|${it.timestamp}" }
        p.edit().putString(KEY_EVENTS, serialized).apply()
    }

    fun readAll(): List<ScreenEvent> {
        val p = prefs() ?: return emptyList()
        val raw = p.getString(KEY_EVENTS, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            if (line.isBlank()) null
            else {
                val parts = line.split("|")
                if (parts.size == 2) {
                    val t = parts[1].toLongOrNull() ?: return@mapNotNull null
                    ScreenEvent(parts[0], t)
                } else null
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
