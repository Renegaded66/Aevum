package com.d_drostes_apps.aevum.domain.addiction

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.d_drostes_apps.aevum.data.model.AddictionSession
import com.d_drostes_apps.aevum.data.repository.AddictionSessionRepository
import com.d_drostes_apps.aevum.data.repository.AddictionWatchRepository
import com.d_drostes_apps.aevum.domain.digital.UsageEventFolding
import com.d_drostes_apps.aevum.domain.digital.UsageStatsPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * M27: Sammelt die Vordergrund-Sitzungen der beobachteten Apps aus
 * [UsageStatsManager] und legt sie idempotent in Room ab.
 *
 * ── Warum überhaupt persistieren? ────────────────────────────────────────
 *
 * Android hält Usage-Events nur begrenzt vor (je nach Hersteller oft nur
 * wenige Tage). Für „Verlauf über Monate" muss Aevum die Sitzungen selbst
 * behalten. Jeder Scan schreibt dieselbe Sitzung erneut (ID = Paket@Start):
 * eine noch offene Sitzung wächst dadurch einfach bis zum aktuellen
 * Zeitpunkt, statt Duplikate zu erzeugen.
 *
 * ── Scan-Fenster ────────────────────────────────────────────────────────
 *
 * - erster Lauf / neue App: [AddictionAnalytics.BACKFILL_DAYS] rückwärts,
 * - Folgeläufe: ab letztem Sync minus 6 h Puffer (deckt offene Sitzungen
 *   und verspätete Events ab), höchstens jedoch 45 Tage.
 *
 * Der Puffer ist bewusst großzügig: ein verpasster Scan (Doze, Prozess-Tod)
 * darf keine Lücke im Verlauf hinterlassen. Mehrfach gelesene Events sind
 * wegen der stabilen ID harmlos.
 */
@Singleton
class AddictionSessionCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchRepository: AddictionWatchRepository,
    private val sessionRepository: AddictionSessionRepository
) {
    private val mutex = Mutex()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val usageStats by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    /**
     * @param backfillDays null = normaler Folge-Scan; gesetzt = erzwungener
     *        Rückblick (beim Hinzufügen einer App).
     * @return true, wenn Daten geschrieben wurden (oder nichts zu tun war).
     */
    suspend fun sync(backfillDays: Int? = null): Boolean {
        if (!UsageStatsPermission.isGranted(context)) return false
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val watched = watchRepository.getAllOnce()
                    if (watched.isEmpty()) {
                        // Keine Auswahl: nichts zu messen. lastSync bewusst
                        // NICHT vorziehen — beim ersten Hinzufügen soll der
                        // volle Backfill laufen.
                        return@withContext true
                    }

                    val now = System.currentTimeMillis()
                    val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
                    val maxLookback = TimeUnit.DAYS.toMillis(AddictionAnalytics.BACKFILL_DAYS.toLong())
                    val from = when {
                        backfillDays != null ->
                            now - TimeUnit.DAYS.toMillis(backfillDays.toLong().coerceAtLeast(1))
                        lastSync <= 0L -> now - maxLookback
                        else -> max(lastSync - RESCAN_BUFFER_MS, now - maxLookback)
                    }.coerceAtLeast(now - TimeUnit.DAYS.toMillis(AddictionAnalytics.RETENTION_DAYS.toLong()))
                        .coerceAtMost(now - 1_000L)

                    val manager = usageStats ?: return@withContext false
                    val events = try {
                        manager.queryEvents(from, now)
                    } catch (e: Exception) {
                        Log.w(TAG, "queryEvents fehlgeschlagen", e)
                        return@withContext false
                    } ?: return@withContext false

                    val folder = UsageEventFolding.Folder(clipStart = from)
                    val watchedPackages = watched.map { it.packageName }.toSet()
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        val pkg = event.packageName ?: ""
                        when (event.eventType) {
                            UsageEvents.Event.MOVE_TO_FOREGROUND,
                            UsageEvents.Event.ACTIVITY_RESUMED -> {
                                // Nur beobachtete Pakete füttern: Die Faltung
                                // ist paketunabhängig, und 45 Tage Events
                                // aller Apps wären bei jedem Backfill unnötig
                                // teuer (Speicher + CPU).
                                if (pkg in watchedPackages) {
                                    folder.onForeground(pkg, event.className, event.timeStamp)
                                }
                            }

                            UsageEvents.Event.MOVE_TO_BACKGROUND,
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED -> {
                                if (pkg in watchedPackages) {
                                    folder.onBackground(pkg, event.className, event.timeStamp)
                                }
                            }

                            // M27: Beim Sperren kommt auf vielen Geräten
                            // kein ACTIVITY_PAUSED — ohne diesen Pfad würde
                            // eine nachts offene App bis zum Morgen
                            // weiterzählen. Screen-Off betrifft alle offenen
                            // (also alle beobachteten) Fenster.
                            UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                            UsageEvents.Event.DEVICE_SHUTDOWN ->
                                folder.onScreenOff(event.timeStamp)
                        }
                    }

                    val phases = folder.finish(now)
                    val sessions: List<AddictionSession> = AddictionSessionMapper.toSessions(
                        phases = phases,
                        watchedPackages = watched.map { it.packageName }.toSet()
                    )
                    if (sessions.isNotEmpty()) {
                        sessionRepository.upsertAll(sessions)
                    }
                    sessionRepository.deleteOlderThan(
                        now - TimeUnit.DAYS.toMillis(AddictionAnalytics.RETENTION_DAYS.toLong())
                    )
                    prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Sucht-Detektor-Sync fehlgeschlagen", e)
                    false
                }
            }
        }
    }

    companion object {
        private const val TAG = "AddictionCollector"
        private const val PREFS = "aevum_addiction"
        private const val KEY_LAST_SYNC = "last_sync"

        /** Überlappung, damit offene/verspätete Sitzungen aktualisiert werden. */
        private val RESCAN_BUFFER_MS = TimeUnit.HOURS.toMillis(6)
    }
}
