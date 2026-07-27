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
