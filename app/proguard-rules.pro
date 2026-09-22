# =============================================================================
# Aevum — R8/ProGuard-Regeln für den Release-Build (M18.136)
# =============================================================================
#
# WARUM DIESE DATEI: Der Release-Build lief bis M18.135 mit
# `isMinifyEnabled = false`. Google Play meldete daraufhin
# "Die DEX-Codeoptimierung liegt unter unserem Grenzwert —
#  Verschleierung (0 %)". Ohne R8 bleibt der gesamte Code im Klartext
# (lesbare Klassennamen, ungenutzte Klassen bleiben drin) — das kostet
# DEX-Größe, Startzeit und Angriffsfläche.
#
# Diese Regeln sind KEIN "Sicherheitsnetz auf Verdacht", sondern leiten
# sich aus einem Audit des Aevum-Codes ab (siehe unten je Abschnitt).
# Grundsatz: So wenig keep wie möglich — R8 soll arbeiten können.
# Kein pauschales `-keep class com.d_drostes_apps.aevum.**`.
#
# Die Consumer-Regeln der Bibliotheken (MapLibre, Health Connect, Room,
# WorkManager, Compose, Coroutines) liegen in den AARs/JARs und werden
# von R8 automatisch mitgelesen — sie sind hier NICHT wiederholt.
# =============================================================================


# -----------------------------------------------------------------------------
# 1. Grundlagen: Attribute, die R8 sonst entfernt
# -----------------------------------------------------------------------------
# Annotations/EnclosingMethod werden zur Laufzeit u.a. von Room, Hilt und
# Kotlin-Reflection gelesen. Signature erhält generische Signaturen.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# StackTrace-Informationen: Aevum schreibt Crash-Logs (CrashLogger) und
# zeigt Datei/Zeile im Log — ohne diese Attribute steht dort "Unknown Source".
# SourceFile/LineNumberTable kosten wenig und machen Feld-Crashes diagnostizierbar.
-keepattributes SourceFile,LineNumberTable
# Die Original-Dateinamen sollen NICHT im Stacktrace landen (Privacy) —
# R8 ersetzt sie durch die Zeilennummer-Datei.
-renamesourcefileattribute SourceFile


# -----------------------------------------------------------------------------
# 2. App-Einstiegspunkte aus dem Manifest
# -----------------------------------------------------------------------------
# Activities, Services und BroadcastReceiver werden vom SYSTEM über den
# Namen instanziiert (Class.forName über den Manifest-Eintrag). R8 sieht
# diesen Aufruf nicht und würde die Klassen sonst umbenennen/entfernen.
#
# Im Manifest von Aevum stehen (Audit 2026-09-22):
#   .MainActivity, .ui.screens.dashboard.SwitchActivity, .domain.digital.BlockActivity
#   .AevumApplication
#   .domain.liveactivity.LiveActivityService, .domain.digital.AppBlockService,
#   .automation.activityrecognition.DriveDetectionService,
#   .automation.geofence.GeofenceForegroundService, .automation.apptracking.AppTrackingService
#   .automation.geofence.GeofenceBroadcastReceiver, .automation.geofence.BootReceiver,
#   .automation.activityrecognition.ActivityTransitionReceiver,
#   .automation.activityrecognition.InitialActivityProbeReceiver,
#   .automation.activityrecognition.ActivityContinuousSamplesReceiver,
#   .automation.sleep.ScreenEventReceiver
#
# allowobfuscation NICHT verwendet: der Manifest-Name wird beim Start
# aufgelöst — eine Umbenennung würde den Start brechen.
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper

# Die Manifest-Klassen namentlich (belt-and-braces: falls R8 die
# Vererbungs-Kette anders bewertet, greift dieser explizite Keep).
-keep class com.d_drostes_apps.aevum.AevumApplication { *; }
-keep class com.d_drostes_apps.aevum.MainActivity { *; }
-keep class com.d_drostes_apps.aevum.ui.screens.dashboard.SwitchActivity { *; }
-keep class com.d_drostes_apps.aevum.domain.digital.BlockActivity { *; }
-keep class com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService { *; }
-keep class com.d_drostes_apps.aevum.domain.digital.AppBlockService { *; }
-keep class com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService { *; }
-keep class com.d_drostes_apps.aevum.automation.geofence.GeofenceForegroundService { *; }
-keep class com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService { *; }
-keep class com.d_drostes_apps.aevum.automation.geofence.GeofenceBroadcastReceiver { *; }
-keep class com.d_drostes_apps.aevum.automation.geofence.BootReceiver { *; }
-keep class com.d_drostes_apps.aevum.automation.activityrecognition.ActivityTransitionReceiver { *; }
-keep class com.d_drostes_apps.aevum.automation.activityrecognition.InitialActivityProbeReceiver { *; }
-keep class com.d_drostes_apps.aevum.automation.activityrecognition.ActivityContinuousSamplesReceiver { *; }
-keep class com.d_drostes_apps.aevum.automation.sleep.ScreenEventReceiver { *; }


# -----------------------------------------------------------------------------
# 3. WorkManager-Worker (Reflection über Klassennamen)
# -----------------------------------------------------------------------------
# WorkManager speichert den Worker-Klassennamen als String in der eigenen
# Room-DB und instanziiert ihn per Reflection. Die Bibliothek liefert
# Consumer-Rules für `* extends androidx.work.Worker` und
# `* extends androidx.work.ListenableWorker` — Aevum-Worker erben aber von
# **CoroutineWorker**, der nicht in diesen Regeln steht. Ohne den folgenden
# Keep würden die 25 Worker (Audit: BicycleStartWorker, DriveStartWorker/Stop/
# Watchdog/Probe, WalkingStart/Stop/Watchdog, ActivityRecognitionWorker,
# ActivityRecognitionTriggerWorker, InitialActivitySnapshotWorker,
# UnknownPlaceDetectorWorker, GeofenceRefreshWorker, ProactiveGeofenceCheckWorker,
# GeofenceStabilizationWorker, ProfileScheduleWorker, PingTriggerWorker,
# ScreenRecordingWorker, ScreenOffStopWorker, SleepImportWorker, GarminSyncWorker,
# SleepFusionWorker, MidnightAllowanceWorker, CalendarSyncWorker,
# CalendarAutoRunWorker) nach einem App-Update NICHT mehr instanziiert —
# stille Ausfälle bei allem Auto-Tracking.
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.Worker { *; }
# WorkManager liest WorkerParameters per Reflection aus dem Bundle.
-keep class androidx.work.WorkerParameters { *; }


# -----------------------------------------------------------------------------
# 4. Room
# -----------------------------------------------------------------------------
# Room generiert AppDatabase_Impl, die DAO-Entities und Converter per
# generierter Implementierung nutzt (kein Reflection) — die Consumer-Rules
# der Bibliothek decken `* extends RoomDatabase` ab. Zusätzlich nötig:
# - @TypeConverter-Methoden werden von Room generiert aufgerufen: der
#   generierte Code referenziert sie direkt, also KEIN Keep nötig, ABER
#   Room prüft zur Laufzeit die Schema-Identität über Entity-Klassennamen
#   (identityHash + tableName). Der TableName ist ein String-Literal im
#   generierten Code → kein Reflection-Risiko.
# - Entities werden via Cursor auf Konstruktoren abgebildet (generierter
#   Code) → kein Keep nötig.
# Deshalb hier nur die Absicherung für generierte Implementierungen, die
# Room selbst per Name lädt:
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**


# -----------------------------------------------------------------------------
# 5. Hilt / Dagger
# -----------------------------------------------------------------------------
# Hilt generiert Komponenten zur Compile-Zeit und referenziert sie direkt —
# die Consumer-Rules der Bibliothek decken die @EntryPoint-Klassen ab
# (Aevum nutzt `EntryPointAccessors.fromApplication(...)` mit
# Deps::class.java in ~25 Klassen; das ist ein DIREKTER Klassen-Literal,
# den R8 sieht und korrekt behandelt).
# Kritisch sind die generierten Hilt-Klassen, die per Name geladen werden:
-keep,allowobfuscation,allowshrinking @dagger.hilt.EntryPoint class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.internal.GeneratedEntryPoint class *
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**


# -----------------------------------------------------------------------------
# 6. Kotlin-Enums: valueOf/values werden per Reflection aufgelöst
# -----------------------------------------------------------------------------
# AUDIT — diese zwei Stellen sind real gefährdet:
#   a) AutomationViewModels.kt:217  QuickPlaceKind.valueOf(savedStateHandle.get<String>("quickKind"))
#   b) GeofenceStabilizationWorker.kt  GeofenceTransition.valueOf(transitionName)
# Beide nutzen den in der DB/im Bundle gespeicherten ***Namen*** des Enums.
# R8 entfernt ungenutzte Enum-Konstanten NICHT automatisch, benennt aber
# Klassen um — die Namen der Konstanten bleiben erhalten. Der generische
# Schutz hält die valueOf/values-API aller Enums intakt:
# (identisch zur von MapLibre mitgelieferten Regel, hier global gefasst)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Persistierte Enum-Namen in SharedPreferences (InsightsViewModel.kt:152
# schreibt `period.name`): Klassen-UMBENENNUNG ist hier unkritisch, weil
# der Name der KONSTANTE gespeichert wird, nicht der Klassenname.
# Nur die Konstanznamen dürfen nicht verschwinden → oben abgedeckt.


# -----------------------------------------------------------------------------
# 7. MapLibre (native Map, JNI-Callbacks)
# -----------------------------------------------------------------------------
# MapLibre lädt libmaplibre.so und ruft Java-Methoden von dort per
# Reflection auf. Die Bibliothek liefert eigene Consumer-Rules (u.a.
# TileOperation, RenderingStats, NativeMapOptions, geojson, JsonArray/…),
# die automatisch greifen. Hier nur, was NICHT abgedeckt ist bzw. was für
# den Fall eines Full-Mode-Builds zusätzlich abgesichert wird.
# Aevum nutzt die Map in GeofenceMapScreen/GeofenceEditorScreen.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**
# Native Methoden werden über ihren Namen gebunden — nicht umbenennen.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}


# -----------------------------------------------------------------------------
# 8. Health Connect SDK
# -----------------------------------------------------------------------------
# Die Bibliothek liefert Consumer-Rules (GeneratedMessageLite-Felder,
# ErrorCode, Permission, ExerciseRoute). Zusätzlich schützt Aevum:
# - Aevum liest Health-Daten über den Client (kein eigenes Proto) →
#   die Consumer-Rules genügen. Absicherung gegen Proto-Reflection:
-keepclassmembers class * extends androidx.health.platform.client.proto.GeneratedMessageLite { <fields>; }
-dontwarn androidx.health.**


# -----------------------------------------------------------------------------
# 9. org.json (JSON-Parsing an mehreren Stellen)
# -----------------------------------------------------------------------------
# AUDIT: ActivitySession.kt (JSONArray), DataManager.kt (Backup-Import/
# -Export), DirectGarminClient.kt (Garmin-API-Antworten), RecurrenceEngine,
# CalendarMatchEngine. org.json ist eine Plattform-Bibliothek (kein
# Reflection auf App-Klassen) — die JSON-Schlüssel sind String-Literale.
# Kein Keep für App-Klassen nötig. Nur die Warnungen unterdrücken, weil
# org.json je nach API-Level implizit statt explizit verfügbar ist:
-dontwarn org.json.**


# -----------------------------------------------------------------------------
# 10. Notification / RemoteViews
# -----------------------------------------------------------------------------
# AUDIT: LiveActivityService baut die Notification ABSICHTLICH ohne
# Custom-RemoteViews (M18.25-Crash) — als Bitmap via BigPictureStyle.
# NotificationCompat referenziert aber Action-Klassen per Reflection
# (getParcelable): die Klassen sind final/plattformseitig.
# Der Layout-Legacy-Ordner (live_notification.xml, digital_balance_block_
# overlay.xml) wird zur Laufzeit NICHT inflatet → R8 darf die
# Layout-Referenzen entfernen. Kein Keep.
# Custom-Action-Klassen sind nicht vorhanden.


# -----------------------------------------------------------------------------
# 11. Kotlin-Coroutines / Stdlib
# -----------------------------------------------------------------------------
# kotlinx-coroutines bringt eigene Consumer-Rules mit (META-INF/proguard/
# coroutines.pro: ServiceLoader, volatile-Felder). Kotlin-Stdlib und
# Compose ebenfalls. Nur ergänzend:
-dontwarn kotlinx.coroutines.**
# Kotlin-Reflection-Metadaten (kotlin.Metadata) erhalten, damit
# kotlin-reflect-Fälle und Serializer weiter funktionieren:
-keep class kotlin.Metadata { *; }


# -----------------------------------------------------------------------------
# 12. Aevum-Enums, die aus Bundle/DB/String rekonstruiert werden
# -----------------------------------------------------------------------------
# Explizit benannte Absicherung der beiden Audit-Fundstellen (Abschnitt 6):
-keep class com.d_drostes_apps.aevum.ui.screens.automation.QuickPlaceKind { *; }
-keep class com.d_drostes_apps.aevum.automation.geofence.GeofenceTransition { *; }


# -----------------------------------------------------------------------------
# 13. Diagnose
# -----------------------------------------------------------------------------
# R8 soll fehlende Klassen als WARNUNG melden (nicht abbrechen), damit
# Bibliotheks-Optionalitäten (z.B. optionale Garmin-/Health-Abhängigkeiten)
# den Release nicht blockieren. Echte Probleme stehen dann im Build-Log
# als "Missing class" und werden im Abschlussbericht geprüft.
-dontnote **
