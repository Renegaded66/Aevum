package de.devondroste.aevum

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import de.devondroste.aevum.automation.geofence.GeofenceRefreshScheduler
import de.devondroste.aevum.automation.activityrecognition.ActivityRecognitionRegistrar
import de.devondroste.aevum.automation.health.SleepImportScheduler
import de.devondroste.aevum.automation.sleep.ScreenEvent
import de.devondroste.aevum.automation.sleep.ScreenEventRepository
import de.devondroste.aevum.automation.sleep.SleepFusionWorker
import de.devondroste.aevum.domain.seed.EnsureDefaultDataUseCase
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class AevumApplication : Application() {
    @Inject lateinit var sleepImportScheduler: SleepImportScheduler
    @Inject lateinit var geofenceRefreshScheduler: GeofenceRefreshScheduler

    /**
     * M12.1.1: Hilt EntryPoint, damit AevumApplication (kein @AndroidEntryPoint)
     * an den [ScreenEventRepository] kommt, ohne die volle Hilt-ViewModel-Pipeline.
     *
     * M16.4: Erweitert um [ensureDefaultData] — wird einmalig in [onCreate]
     * aufgerufen, damit Category/ActivityType/Tag-Seeds ZWINGEND vor dem
     * ersten WorkManager-Job (Schlaf-Worker, Geofence-Worker) in der DB sind.
     * Ohne diese Seeds schlagen Foreign-Key-Inserts fehl und der Schlaf
     * erscheint nicht in der Timeline (Bug aus M16.3-Real-Test).
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun screenEventRepository(): ScreenEventRepository
        fun ensureDefaultData(): EnsureDefaultDataUseCase
        // M16.7: Heuristic-Engine auch aus dem Lifecycle-Fallback aus triggern.
        // Hintergrund: ACTION_SCREEN_ON/ACTION_USER_PRESENT werden seit Android 8+
        // für manifest-registrierte Receiver zunehmend unterdrückt. Wenn der
        // echte Broadcast ausbleibt, ist die einzige zuverlässige "erste
        // Handynutzung am Morgen" der ActivityLifecycleCallbacks-Fallback
        // (recordForegroundEvent("ON")). Dieser Pfad muss daher ebenfalls
        // die Heuristic-Engine anstoßen, sonst läuft sie nie.
        fun sleepHeuristicEngine(): de.devondroste.aevum.automation.sleep.SleepHeuristicEngine
    }

    /**
     * M12.1.1: Zählt aktive Activities, um Vordergrund / Hintergrund
     * zu erkennen — einfache Alternative zu ProcessLifecycleOwner, ohne
     * die zusätzliche androidx.lifecycle:lifecycle-process Dependency.
     */
    private var activeActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        // M12.0.2: Defensive Initialisierung — jede Komponente wird einzeln
        // in try-catch gewrappt. Ein Fehler in MapLibre, SleepImport oder
        // GeofenceRefresh darf niemals den App-Start abbrechen.
        try {
            MapLibre.getInstance(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "MapLibre init failed — continuing without maps", e)
        }

        // M16.4: ensureDefaultData ZUERST. Wir warten nicht auf das Resultat,
        // weil die Seeds per INSERT OR IGNORE idempotent sind und ein
        // nachfolgender ViewModel-Init nochmal nachlegt. Aber wir geben der
        // DB einen Moment, damit die FK-Constraints für Category/ActivityType
        // vorhanden sind, bevor der Schlaf-Worker läuft. Das löst den
        // "Schlaf in Dashboard, aber nicht in Timeline"-Bug.
        try {
            val deps = EntryPointAccessors.fromApplication(this, Deps::class.java)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    deps.ensureDefaultData().invoke()
                    Log.d("AevumApplication", "ensureDefaultData abgeschlossen (Seeds vorhanden)")
                } catch (e: Exception) {
                    Log.e("AevumApplication", "ensureDefaultData failed — continuing", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AevumApplication", "ensureDefaultData EntryPoint init failed — continuing", e)
        }

        // M9.2: ensure Health Connect sleep import runs in the background
        try {
            sleepImportScheduler.schedule()
        } catch (e: Exception) {
            Log.e("AevumApplication", "SleepImportScheduler failed — continuing", e)
        }
        // M9.2: ensure Geofences stay registered even when the user is away
        try {
            geofenceRefreshScheduler.schedule()
        } catch (e: Exception) {
            Log.e("AevumApplication", "GeofenceRefreshScheduler failed — continuing", e)
        }
        // M14: ActivityRecognition (IN_VEHICLE + STILL) Transition-Updates
        // abonnieren. No-Op, falls ACTIVITY_RECOGNITION nicht gewährt — wird
        // dann nachgeholt, sobald der User die Permission in den Settings erteilt.
        try {
            ActivityRecognitionRegistrar.register(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "ActivityRecognitionRegistrar failed — continuing", e)
        }
        // M14: Beim App-Start einen einmaligen SleepFusionWorker enqueuen.
        // Der entscheidet selbst, ob genug Signale da sind, und ist sonst ein No-Op.
        try {
            val request = androidx.work.OneTimeWorkRequestBuilder<SleepFusionWorker>()
                .setInitialDelay(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(this)
                .enqueueUniqueWork(SleepFusionWorker.WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.e("AevumApplication", "SleepFusionWorker enqueue failed — continuing", e)
        }
        // M12.1.1: Fallback für SCREEN_ON / SCREEN_OFF, falls der
        // BroadcastReceiver von Battery-Optimierung oder OEM-ROMs
        // unterdrückt wird. Wir registrieren einen ActivityLifecycleCallbacks
        // an der Application: jeder Wechsel in den Vordergrund wird als "ON"
        // aufgezeichnet, jeder Wechsel in den Hintergrund als "OFF". Damit
        // funktioniert die Sleep-Heuristik auch ohne zuverlässige
        // System-Broadcasts, solange die App selbst gelegentlich geöffnet wird.
        try {
            registerLifecycleFallback()
        } catch (e: Exception) {
            Log.e("AevumApplication", "Lifecycle fallback failed — continuing", e)
        }

        // M16.7: Zusätzlich zur Lifecycle-Fallback-Registrierung registrieren
        // wir einen Runtime-BroadcastReceiver für ACTION_SCREEN_ON und
        // ACTION_USER_PRESENT. Beide Aktionen sind seit Android 8 (API 26) für
        // manifest-registrierte Receiver gesperrt — sie werden nur an Receiver
        // zugestellt, die zur Laufzeit via registerReceiver() registriert wurden.
        // Ohne diesen Runtime-Receiver verpassen wir das echte morgendliche
        // Aufwachen, wenn die App selbst noch nicht im Vordergrund ist (z.B.
        // User liest nur die Statusleiste / eine Notification). Wir nutzen den
        // existierenden ScreenEventReceiver, der diese Logik ohnehin schon
        // implementiert hat, mit einem dedizierten IntentFilter.
        try {
            registerScreenEventRuntimeReceiver()
        } catch (e: Exception) {
            Log.e("AevumApplication", "Screen event runtime receiver registration failed — continuing", e)
        }
    }

    /**
     * M16.7: Runtime-Registrierung für SCREEN_ON/USER_PRESENT-Broadcasts.
     *
     * Diese Aktionen sind seit Android 8 nicht mehr an manifest-registrierte
     * Receiver deliverbar. Wir registrieren stattdessen [ScreenEventReceiver]
     * dynamisch mit einem IntentFilter. Wichtig: Der Receiver bleibt nur so
     * lange aktiv, wie der App-Prozess läuft. Wird er durch Battery-Optimization
     * getötet, fängt der Lifecycle-Fallback ab. Beide Pfade ergänzen sich.
     */
    private fun registerScreenEventRuntimeReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        val receiver = de.devondroste.aevum.automation.sleep.ScreenEventReceiver()
        // RECEIVER_NOT_EXPORTED: Wir wollen die Broadcasts nur aus dem eigenen
        // Prozess empfangen. Da die Actions zudem implizit sind (vom System),
        // ist dies Pflicht ab Android 14 (target SDK 34).
        val flags = android.content.Context.RECEIVER_NOT_EXPORTED
        registerReceiver(receiver, filter, flags)
        Log.d("AevumApplication", "ScreenEventReceiver runtime-registriert (SCREEN_ON/OFF/USER_PRESENT)")
    }

    private fun registerLifecycleFallback() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                val newCount = activeActivityCount + 1
                activeActivityCount = newCount
                if (newCount == 1) {
                    // Erste Activity im Vordergrund → App geht in den Vordergrund.
                    // Bildschirm ist praktisch sicher an.
                    recordForegroundEvent("ON")
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                val newCount = (activeActivityCount - 1).coerceAtLeast(0)
                activeActivityCount = newCount
                if (newCount == 0) {
                    // Letzte Activity im Hintergrund → App vollständig im Hintergrund.
                    // Bildschirm ist wahrscheinlich aus (kann aber noch an sein).
                    recordForegroundEvent("OFF")
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun recordForegroundEvent(type: String) {
        try {
            val deps = EntryPointAccessors.fromApplication(this, Deps::class.java)
            val repo = deps.screenEventRepository()
            repo.init(applicationContext)
            // M12.1.1: insert ist suspend. ActivityLifecycleCallbacks laufen
            // auf dem Main-Thread, deshalb schicken wir das Schreiben auf
            // einen Hintergrund-Dispatcher.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    repo.insert(ScreenEvent(type = type, timestamp = System.currentTimeMillis(), source = "LIFECYCLE"))
                    // M16: Bei ON (App in den Vordergrund) zusätzlich den
                    // SleepFusionWorker enqueuen. Morgens beim ersten Blick
                    // aufs Handy läuft die App-Resume-Phase durch diesen
                    // Callback, der Worker wird gestartet, prüft die Signale
                    // und erzeugt ggf. einen Schlaf-Vorschlag. Das ist der
                    // "morgens sofort sichtbar"-Pfad ohne extra Job.
                    if (type == "ON") {
                        try {
                            val request = androidx.work.OneTimeWorkRequestBuilder<SleepFusionWorker>()
                                .setInitialDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            androidx.work.WorkManager.getInstance(this@AevumApplication)
                                .enqueueUniqueWork(
                                    SleepFusionWorker.WORK_NAME,
                                    androidx.work.ExistingWorkPolicy.KEEP,
                                    request
                                )
                        } catch (e: Exception) {
                            Log.w("AevumApplication", "SleepFusionWorker enqueue failed for $type", e)
                        }
                        // M16.7: Heuristic-Engine direkt aus dem Lifecycle-Fallback
                        // triggern. Grund: Auf modernem Android (8+) kommen die
                        // ACTION_SCREEN_ON / ACTION_USER_PRESENT-Broadcasts nicht
                        // zuverlässig beim manifest-registrierten Receiver an.
                        // Wenn der echte Broadcast ausbleibt, ist der erste
                        // Hinweis auf "Morgen, User ist wach" die App-in-den-
                        // Vordergrund-Bewegung — und genau das ist dieser Callback.
                        // Ohne diesen Trigger-Aufruf blieb die Heuristic stumm.
                        try {
                            deps.sleepHeuristicEngine().init(this@AevumApplication)
                            deps.sleepHeuristicEngine().analyzeLatest()
                        } catch (e: Exception) {
                            Log.w("AevumApplication", "SleepHeuristicEngine trigger failed for $type", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AevumApplication", "Lifecycle insert failed for $type", e)
                }
            }
        } catch (e: Exception) {
            Log.w("AevumApplication", "Lifecycle fallback init failed for $type", e)
        }
    }
}
