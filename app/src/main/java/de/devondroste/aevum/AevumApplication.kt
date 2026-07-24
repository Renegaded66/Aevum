package de.devondroste.aevum

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import de.devondroste.aevum.automation.geofence.GeofenceRefreshScheduler
import de.devondroste.aevum.automation.health.SleepImportScheduler
import de.devondroste.aevum.automation.sleep.ScreenEvent
import de.devondroste.aevum.automation.sleep.ScreenEventRepository
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
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun screenEventRepository(): ScreenEventRepository
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
                    repo.insert(ScreenEvent(type = type, timestamp = System.currentTimeMillis()))
                } catch (e: Exception) {
                    Log.w("AevumApplication", "Lifecycle insert failed for $type", e)
                }
            }
        } catch (e: Exception) {
            Log.w("AevumApplication", "Lifecycle fallback init failed for $type", e)
        }
    }
}
