package com.d_drostes_apps.aevum

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import com.d_drostes_apps.aevum.automation.geofence.GeofenceRefreshScheduler
import com.d_drostes_apps.aevum.automation.geofence.GeofenceRefreshWorker
import com.d_drostes_apps.aevum.automation.midnight.MidnightAllowanceScheduler
import com.d_drostes_apps.aevum.automation.sleep.SleepFusionMorningScheduler
import com.d_drostes_apps.aevum.automation.unknownplace.UnknownPlaceDetectorScheduler
import com.d_drostes_apps.aevum.automation.activityrecognition.ActivityRecognitionRegistrar
import com.d_drostes_apps.aevum.automation.health.SleepImportScheduler
import com.d_drostes_apps.aevum.automation.garmin.GarminSyncScheduler
import com.d_drostes_apps.aevum.automation.sleep.ScreenEvent
import com.d_drostes_apps.aevum.automation.sleep.ScreenEventRepository
import com.d_drostes_apps.aevum.automation.sleep.SleepFusionWorker
import com.d_drostes_apps.aevum.domain.seed.EnsureDefaultDataUseCase
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class AevumApplication : Application() {
    @Inject lateinit var sleepImportScheduler: SleepImportScheduler
    // M18.58: Garmin Connect Sync
    @Inject lateinit var garminSyncScheduler: GarminSyncScheduler
    @Inject lateinit var geofenceRefreshScheduler: GeofenceRefreshScheduler
    @Inject lateinit var unknownPlaceScheduler: UnknownPlaceDetectorScheduler
    @Inject lateinit var midnightAllowanceScheduler: MidnightAllowanceScheduler
    // M18.9: Garantierter Morgen-Trigger für die Schlaf-Fusion.
    @Inject lateinit var sleepFusionMorningScheduler: SleepFusionMorningScheduler

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
        // M18.86: Track-Punkt-Retention (90 Tage) beim App-Start.
        fun locationTrackPointRepository(): com.d_drostes_apps.aevum.data.repository.LocationTrackPointRepository
        // AEVUM-1: Einmaliger Daten-Aufräumlauf beim App-Start — löscht
        // Duplikate (gleiche externalId oder gleicher Typ + zeitliche
        // Überlappung; z.B. der mehrfach gesyncte Garmin-Schlaf).
        fun cleanupDuplicateSessions(): com.d_drostes_apps.aevum.data.cleanup.CleanupDuplicateSessionsUseCase
        // M18.21: LiveActivityManager für den Notification-Restore beim
        // App-Start (falls bereits eine Session läuft, z.B. nach
        // Ultra-Energie-Sparmodus, muss die Notification wieder erscheinen).
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
        // M16.7: Heuristic-Engine auch aus dem Lifecycle-Fallback aus triggern.
        // Hintergrund: ACTION_SCREEN_ON/ACTION_USER_PRESENT werden seit Android 8+
        // für manifest-registrierte Receiver zunehmend unterdrückt. Wenn der
        // echte Broadcast ausbleibt, ist die einzige zuverlässige "erste
        // Handynutzung am Morgen" der ActivityLifecycleCallbacks-Fallback
        // (recordForegroundEvent("ON")). Dieser Pfad muss daher ebenfalls
        // die Heuristic-Engine anstoßen, sonst läuft sie nie.
        fun sleepHeuristicEngine(): com.d_drostes_apps.aevum.automation.sleep.SleepHeuristicEngine
        // M18.61e: Selbstheilung — Geofencing aktivieren, wenn Geofences
        // existieren, aber das Gate (noch) aus ist (Bestandsinstallationen
        // vor dem Editor-Fix).
        fun settingsRepository(): com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
        fun geofenceRepository(): com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
        // M18.61g: Digital-Balance-Sperr-Service beim App-Start starten,
        // wenn Limits oder ein Profil aktiv sind (vorher lief er nur nach
        // manueller Limit-Änderung — nach App-Neustart nie → kein Banner).
        fun appLimitRepository(): com.d_drostes_apps.aevum.data.repository.AppLimitRepository
        fun balanceProfileRepository(): com.d_drostes_apps.aevum.data.repository.BalanceProfileRepository
    }

    /**
     * M12.1.1: Zählt aktive Activities, um Vordergrund / Hintergrund
     * zu erkennen — einfache Alternative zu ProcessLifecycleOwner, ohne
     * die zusätzliche androidx.lifecycle:lifecycle-process Dependency.
     */
    private var activeActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        // App-Sprache beim Kaltstart anwenden (synchron aus dem
        // SharedPreferences-Spiegel). "system" lässt die Systemsprache
        // unverändert. Die Activities wenden die Sprache zusätzlich in
        // onCreate an (LocalizedActivity) — hier geht es um Ressourcen,
        // die vor der ersten Activity geladen werden (z. B. WorkManager-
        // Notifications, CrashLogger).
        try {
            com.d_drostes_apps.aevum.util.LocaleHelper.applyToApplication(
                this,
                com.d_drostes_apps.aevum.data.repository.LanguageRepository.LANGUAGE_SYSTEM.let {
                    // Synchroner Read ohne Hilt: SharedPreferences direkt.
                    getSharedPreferences("aevum_language", MODE_PRIVATE)
                        .getString("app_language", it) ?: it
                }
            )
        } catch (e: Exception) {
            Log.e("AevumApplication", "Locale apply failed — continuing", e)
        }
        // M17.4: Crash-Logger als ALLERERSTES installieren (vor Hilt-Init
        // wäre noch besser, aber @HiltAndroidApp ruft Hilt-Init in
        // super.onCreate() auf — also ist "so früh wie möglich in unserer
        // onCreate" der früheste sinnvolle Punkt). Schreibt Trace nach
        // /sdcard/Android/data/<pkg>/files/last-crash.log (Files-app-reachable).
        // M18.56: Zusätzlich DB-Integrität prüfen — eine korrupte DB-Datei
        // (z.B. durch abgebrochenes Backup/Restore oder Android-Auto-Backup)
        // lässt Room beim Öffnen crashen; die ViewModels fangen das ab und
        // die App läuft mit leerer DB weiter, aber ALLE Inserts schlagen
        // stillschweigend fehl ("nichts passiert" beim Speichern, Toggles
        // tot, keine Defaults). Deshalb: korrupte DB vor dem ersten
        // Room-Zugriff erkennen und neu erstellen lassen.
        try {
            ensureDatabaseIntegrity()
        } catch (e: Exception) {
            Log.e("AevumApplication", "DB-Integritätscheck fehlgeschlagen — weiter", e)
        }
        com.d_drostes_apps.aevum.util.CrashLogger.install(this)
        // M12.0.2: Defensive Initialisierung — jede Komponente wird einzeln
        // in try-catch gewrappt. Ein Fehler in MapLibre, SleepImport oder
        // GeofenceRefresh darf niemals den App-Start abbrechen.
        try {
            MapLibre.getInstance(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "MapLibre init failed — continuing", e)
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

        // AEVUM-1: Daten-Aufräumlauf — einmalig beim App-Start Duplikate
        // entfernen (gleiche externalId ODER gleicher Typ + zeitliche
        // Überlappung). Der Garmin-Schlaf wurde anfangs mehrfach gesynct →
        // in manchen Nächten standen ~100h Schlaf in der Timeline. Der
        // Cleanup behält die NEUESTE Session jeder Duplikat-Gruppe, löscht
        // die älteren (MANUAL/user-edited/live bleiben immer geschützt).
        // Idempotent: Nach dem ersten Lauf ist er ein No-Op.
        try {
            val deps = EntryPointAccessors.fromApplication(this, Deps::class.java)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val removed = deps.cleanupDuplicateSessions().invoke()
                    if (removed > 0) {
                        Log.d("AevumApplication", "Dedup-Cleanup: $removed Duplikate entfernt")
                    }
                } catch (e: Exception) {
                    Log.e("AevumApplication", "Dedup-Cleanup failed — continuing", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AevumApplication", "Dedup-Cleanup EntryPoint init failed — continuing", e)
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
        // M18.66-FIX (Root Cause "Geofence startet keine Session"): Der
        // GeofenceForegroundService (Typ "location") ist auf Android 14+
        // PFLICHT, damit Geofence-Transitions im Hintergrund zuverlässig
        // feuern. Vorher wurde er nur indirekt über den GeofenceRefreshWorker
        // (15s Delay) gestartet — wenn der Worker fehlschlug oder die App
        // im Hintergrund startete, lief kein FGS und Geofences feuerten
        // nicht. Jetzt: FGS direkt beim App-Start starten (idempotent,
        // der Service prüft selbst, ob er schon läuft).
        try {
            com.d_drostes_apps.aevum.automation.geofence.GeofenceForegroundService.start(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "GeofenceForegroundService start failed — continuing", e)
        }
        // M18.61c-HOTFIX: Sofortige Geofence-Registrierung beim App-Start.
        // Der Periodik-Worker feuert erst nach 6h, der BootReceiver nur
        // nach Reboot. Wenn die App frisch installiert/upgedatet wurde
        // (oder die DB gerade repariert wurde), müssen die Geofences aber
        // SOFORT registriert sein — sonst läuft kein Geofence-Trigger,
        // bis der 6h-Worker oder ein Reboot kommt. REPLACE-Policy: bei
        // jedem App-Start neu enqueued (idempotent, Registrierung ist
        // eh ein Refresh).
        try {
            val immediateRefresh = androidx.work.OneTimeWorkRequestBuilder<GeofenceRefreshWorker>()
                .setInitialDelay(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(this)
                .enqueueUniqueWork(
                    "aevum.geofence_refresh_immediate",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    immediateRefresh
                )
        } catch (e: Exception) {
            Log.e("AevumApplication", "Geofence-Refresh (immediate) failed — continuing", e)
        }
        // M18.66-FIX2: Proaktiver Geofence-Check — alle 2 Minuten wird der
        // GPS-Standort gegen alle Geofences geprüft. Das ist der Fallback,
        // wenn GMS-Geofences nicht feuern (Hintergrund, Mock-Location,
        // lange Laufzeit). Ruft die bestehende Pipeline auf (Processor).
        try {
            com.d_drostes_apps.aevum.automation.geofence.ProactiveGeofenceCheckWorker.schedule(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "ProactiveGeofenceCheck start failed — continuing", e)
        }
        // M18.61e-SELBSTHEILUNG: Wenn Geofences existieren, aber das
        // Geofencing-Gate (noch) aus ist, wird es aktiviert. Root Cause
        // "kein einziger Trigger": Der Geofence-Editor setzte das Gate
        // nie (Default false) — der Registrar deregistrierte gespeicherte
        // Geofences sofort wieder. Bestandsinstallationen (Geofence schon
        // gespeichert) werden hier einmalig geheilt.
        try {
            val deps = EntryPointAccessors.fromApplication(this, Deps::class.java)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val settingsRepo = deps.settingsRepository()
                    val geofenceRepo = deps.geofenceRepository()
                    val settings = settingsRepo.get().first()
                    val hasGeofences = geofenceRepo.getAllEnabled().first().isNotEmpty()
                    if (hasGeofences && settings?.geofencingEnabled != true) {
                        settingsRepo.upsert(settings?.copy(geofencingEnabled = true)
                            ?: com.d_drostes_apps.aevum.data.model.AutomationSettings(geofencingEnabled = true))
                        Log.d("AevumApplication", "Selbstheilung: Geofencing aktiviert (${geofenceRepo.getAllEnabled().first().size} Geofences)")
                    }
                } catch (e: Exception) {
                    Log.e("AevumApplication", "Geofencing-Selbstheilung failed — continuing", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AevumApplication", "Geofencing-Selbstheilung init failed — continuing", e)
        }
        // M18.61g: Digital-Balance-Sperr-Service beim App-Start starten.
        // Vorher wurde er nur bei Limit-Änderungen im ViewModel gestartet —
        // nach einem App-Neustart lief er nie, obwohl Limits aktiv waren
        // (User: "ich dachte, wenn eine App über ihr Limit kommt, wird sie
        // blockiert und ein Banner erscheint"). Jetzt: sofort prüfen und
        // starten, wenn Limits oder ein aktives Profil existieren.
        try {
            val deps = EntryPointAccessors.fromApplication(this, Deps::class.java)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val limits = deps.appLimitRepository().getAll().first()
                    val anyActive = limits.any {
                        it.enabled && it.exceptionType != com.d_drostes_apps.aevum.data.model.AppLimit.EXCEPTION_ALWAYS_ALLOW
                    }
                    val activeProfile = deps.balanceProfileRepository().getActiveOnce()
                    if (anyActive || activeProfile != null) {
                        com.d_drostes_apps.aevum.domain.digital.AppBlockService.start(this@AevumApplication)
                        Log.d("AevumApplication", "Digital-Balance-Sperr-Service gestartet (${limits.count { it.enabled }} Limits, Profil: ${activeProfile?.name})")
                    }
                } catch (e: Exception) {
                    Log.e("AevumApplication", "AppBlockService-Start failed — continuing", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AevumApplication", "AppBlockService-Start init failed — continuing", e)
        }
        // M18.58: Garmin Connect Sync — alle 30 min (Schritte/Kalorien/
        // Distanz-Kacheln + Aktivitäts-Import). Schlaf-Import läuft über
        // denselben Worker (sleepSource-Gate).
        try {
            garminSyncScheduler.schedule()
        } catch (e: Exception) {
            Log.e("AevumApplication", "GarminSyncScheduler failed — continuing", e)
        }
        // M18.66-FIX14: ProfileScheduleWorker — prüft alle 15 Min ob ein
        // Digital-Balance-Profil nach Zeitplan aktiviert/deaktiviert werden muss.
        try {
            com.d_drostes_apps.aevum.automation.digitalbalance.ProfileScheduleWorker.schedule(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "ProfileScheduleWorker schedule failed — continuing", e)
        }
        // M17.2: Unknown Place Detector — alle 5 min, prüft ob User
        // an einem nicht-Geofence-Ort sesshaft ist (Restaurant,
        // Arzttermin, etc.) und erzeugt einen UnknownPlace-Eintrag.
        try {
            unknownPlaceScheduler.schedule()
        } catch (e: Exception) {
            Log.e("AevumApplication", "UnknownPlaceDetectorScheduler failed — continuing", e)
        }
        // M17.3: Midnight Allowance Worker — täglich um 00:05, schreibt
        // die DailyAllowance-Akkumulationen für die Statistik.
        try {
            midnightAllowanceScheduler.schedule()
        } catch (e: Exception) {
            Log.e("AevumApplication", "MidnightAllowanceScheduler failed — continuing", e)
        }
        // M18.9: Garantierter Morgen-Trigger für die Schlaf-Fusion —
        // auch wenn der User die App morgens nicht öffnet. Die Nachtsperre
        // in SleepFusionEngine macht alle Läufe außerhalb 05:00-11:59
        // automatisch zu No-Ops.
        try {
            sleepFusionMorningScheduler.schedule()
        } catch (e: Exception) {
            Log.e("AevumApplication", "SleepFusionMorningScheduler failed — continuing", e)
        }
        // M18.61g: Ping-Trigger-Scheduler (FireTV-IP → Activity starten/stoppen)
        try {
            com.d_drostes_apps.aevum.automation.ping.PingTriggerScheduler(
                this
            ).schedule()
        } catch (e: Exception) {
            Log.e("AevumApplication", "PingTriggerScheduler failed — continuing", e)
        }
        // M14: ActivityRecognition (IN_VEHICLE + STILL) Transition-Updates
        // abonnieren. No-Op, falls ACTIVITY_RECOGNITION nicht gewährt — wird
        // dann nachgeholt, sobald der User die Permission in den Settings erteilt.
        try {
            ActivityRecognitionRegistrar.register(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "ActivityRecognitionRegistrar failed — continuing", e)
        }
        // M18.66: Autofahrt-Erkennung über KONTINUIERLICHEN GPS-Stream.
        // Recherche-Befund (Google Maps / Life360 / Android-Doku): Ein
        // einmaliger getCurrentLocation()-Fix liefert fast nie hasSpeed() —
        // zuverlässige Fahrterkennung braucht einen dauerhaften
        // Location-Stream (DriveDetectionService, ForegroundService vom
        // Typ "location"). Der alte DriveProbeWorker (alle 2 Min ein
        // einmaliger Fix) war der Grund, warum die Erkennung nie zuverlässig
        // funktionierte: speedMps war fast immer null.
        try {
            com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService.start(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "DriveDetectionService start failed — continuing", e)
        }
        // M18.86: Track-Punkt-Retention (90 Tage) — einmal pro App-Start
        // als Fire-and-Forget auf IO. Die Tabelle wächst mit ~1 Punkt/25 s
        // während Fahrten (~150 Punkte/Tag bei viel Autofahrerei); ohne
        // Retention würde sie nach Jahren die DB aufblähen. Verlust-Toleranz:
        // Ein App-Start alle paar Tage reicht locker (die Karte zeigt eh
        // nur den ausgewählten Tag).
        try {
            val trackRepo = EntryPointAccessors.fromApplication(
                this,
                Deps::class.java
            ).locationTrackPointRepository()
            val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    trackRepo.deleteOlderThan(cutoff)
                } catch (e: Exception) {
                    Log.w("AevumApplication", "M18.86: Track-Retention fehlgeschlagen: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("AevumApplication", "M18.86: Track-Retention-Scheduler fehlgeschlagen: ${e.message}")
        }
        // M18.67: App-Aufzeichnung — ForegroundService starten. Der Service
        // beendet sich selbst, wenn keine App getrackt ist (Gate in
        // onStartCommand). Startet die Aufzeichnung nach App-Neustart
        // automatisch wieder, solange mindestens eine App getrackt ist.
        try {
            com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService.start(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "AppTrackingService start failed — continuing", e)
        }
        // M17.4: Initial-Activity-Snapshot — falls der User gerade im Auto
        // sitzt und die App frisch startet (Cold-Start), soll das ebenfalls
        // erkannt werden. Idempotent dank KEEP-Policy im Scheduler.
        try {
            com.d_drostes_apps.aevum.automation.activityrecognition.InitialActivitySnapshotScheduler.schedule(this)
        } catch (e: Exception) {
            Log.e("AevumApplication", "InitialActivitySnapshotScheduler failed — continuing", e)
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

        // M18.21: Notification-Restore beim App-Start.
        // Szenario: Das Handy war im Ultra-Energie-Sparmodus, der
        // Foreground-Service wurde gekillt, die Notification verschwand.
        // Beim nächsten App-Start (User öffnet die App) muss die
        // Live-Notification WIEDER erscheinen, wenn bereits eine Session
        // läuft. Wir prüfen die DB asynchron und starten den Service nur,
        // wenn wirklich eine Live-Session existiert.
        try {
            val deps = EntryPointAccessors.fromApplication(this, Deps::class.java)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val manager = deps.liveActivityManager()
                    // M18.24: liveSession ist jetzt SharingStarted.Eagerly —
                    // .value liefert IMMER den echten DB-Wert, auch ohne
                    // aktiven Subscriber. Kein first() mehr noetig.
                    val session = manager.liveSession.value
                    if (session != null && session.isLive) {
                        com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(this@AevumApplication)
                        Log.d("AevumApplication", "M18.24: Live-Session aktiv (${session.title}) — Notification wiederhergestellt")
                    }
                } catch (e: Exception) {
                    Log.e("AevumApplication", "M18.24: Notification-Restore failed — continuing", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AevumApplication", "M18.24: Notification-Restore EntryPoint init failed — continuing", e)
        }
    }

    /**
     * L10N-RUNTIME-FIX: System-Configuration-Events (Dark-Mode-Toggle,
     * Schriftgrößen-/Tastaturwechsel etc.) liefert Android dem Application-
     * Kontext eine NEUE Basiskonfiguration — der in [onCreate] gesetzte
     * Locale-Override ging dabei verloren und Ressourcen fielen auf die
     * Systemsprache zurück. Deshalb: vor dem super-Aufruf die gewählte
     * Sprache synchron aus dem SharedPreferences-Spiegel (derselbe, den
     * LanguageRepository bei jedem setLanguage mitschreibt) wieder auf die
     * neue Config anwenden. "system" → super unverändert durchreichen.
     * applyLocale mutiert die neue Config zurück auf die gewählte Sprache
     * und synchronisiert zusätzlich AppLocale (JVM-Formatter).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val language = getSharedPreferences("aevum_language", MODE_PRIVATE)
            .getString("app_language", "system") ?: "system"
        if (language != "system") {
            try {
                com.d_drostes_apps.aevum.util.LocaleHelper.applyLocale(this, language)
            } catch (e: Exception) {
                Log.e("AevumApplication", "Locale re-apply on config change failed — continuing", e)
            }
        }
        super.onConfigurationChanged(newConfig)
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
        val receiver = com.d_drostes_apps.aevum.automation.sleep.ScreenEventReceiver()
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

    /**
     * M18.56: DB-Integritätscheck beim App-Start.
     *
     * Eine korrupte SQLite-Datei (z.B. durch abgebrochenes Backup/Restore,
     * Android-Auto-Backup-Wiederherstellung oder Dateisystem-Fehler) lässt
     * Room beim Öffnen crashen. Die ViewModels fangen das mit .catch ab —
     * die App läuft dann mit leerer DB weiter, aber ALLE Inserts schlagen
     * stillschweigend fehl. Symptome: "nichts passiert" beim Speichern,
     * Toggles springen zurück, keine Default-Activities nach Neuinstallation.
     *
     * Fix: Vor dem ersten Room-Zugriff PRAGMA quick_check ausführen. Bei
     * Korruption werden die DB-Dateien gelöscht — Room erstellt sie beim
     * nächsten Zugriff frisch (inkl. Seeds). Datenverlust nur im
     * Crash-Fall; Backup/Export existieren als Schutz.
     */
    private fun ensureDatabaseIntegrity() {
        val dbFile = getDatabasePath("aevum_database")
        if (!dbFile.exists()) return
        // Nur prüfen, wenn die Datei plausibel groß ist (leere/0-Byte-Datei
        // ist kein Korruptionsfall — Room erstellt sie ohnehin neu).
        if (dbFile.length() < 1024) return
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val result = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "error"
            }
            db.close()
            if (result != "ok") {
                Log.e("AevumApplication", "DB-Integrität FEHLERHAFT ($result) — Dateien werden neu erstellt")
                dbFile.delete()
                getDatabasePath("aevum_database-wal").delete()
                getDatabasePath("aevum_database-shm").delete()
            } else {
                Log.d("AevumApplication", "DB-Integrität OK")
            }
        } catch (e: Exception) {
            // Datei nicht lesbar (z.B. noch von anderem Prozess offen) —
            // nicht löschen, Room entscheidet selbst.
            Log.w("AevumApplication", "DB-Integritätscheck nicht möglich: ${e.message}")
        }
    }
}
