package com.d_drostes_apps.aevum.automation.garmin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.garmin.GarminApiClient
import com.d_drostes_apps.aevum.data.garmin.GarminRemoteActivity
import com.d_drostes_apps.aevum.data.model.GarminActivity
import com.d_drostes_apps.aevum.data.model.GarminDailySummary
import com.d_drostes_apps.aevum.data.repository.GarminRepository
import com.d_drostes_apps.aevum.domain.garmin.GarminImportUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * M18.58: Garmin Connect Sync-Worker.
 *
 * Holt von der Aevum-Garmin-Bridge (Server):
 *   1. Tageszusammenfassung (Schritte, Distanz, Kalorien) → Kachel-Daten
 *   2. Schlaf (letzte 7 Nächte) → Sleep-Sessions direkt in die Timeline
 *      (nur wenn sleepSource == "garmin")
 *   3. Aktivitäten (letzte 20, z.B. Joggen) → Timeline mit Zeitraum-
 *      Überschreibung via [GarminImportUseCase]
 *
 * Läuft als periodischer Worker (30 min) + manuell aus den Einstellungen.
 */
class GarminSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun garminApiClient(): GarminApiClient
        fun garminRepository(): GarminRepository
        fun garminImportUseCase(): GarminImportUseCase
        // M18.58: Schlaf-Import (letzte 7 Nächte)
        fun activityRepository(): com.d_drostes_apps.aevum.data.repository.ActivityRepository
        // M18.61g-FIX 4: ActivityType "sleep" sicherstellen (FK-Schutz)
        fun activityTypeDao(): com.d_drostes_apps.aevum.data.db.ActivityTypeDao
        fun categoryDao(): com.d_drostes_apps.aevum.data.db.CategoryDao
    }

    override suspend fun doWork(): Result {
        // M18.63-CRITICAL (Root Cause "Garmin-Schlaf wird mehrfach
        // synchronisiert"): Serialisierung aller Sync-Läufe (periodisch
        // 30min, manuell, Sleep-Import-on-arrival). Zwei parallele Läufe
        // lesen dieselben Nacht-Sessions und legen ohne Mutex beide ein
        // Duplikat an, BEVOR der Dedup des jeweils anderen den Insert
        // sieht. Der Mutex macht den Sync prozessintern strikt sequentiell.
        // (Über Prozessgrenzen hinweg schützt zusätzlich die uniqueWork-
        // Vergabe + der intervall-basierte Dedup in importSleep.)
        syncMutex.withLock {
            return doWorkLocked()
        }
    }

    private suspend fun doWorkLocked(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val api = deps.garminApiClient()
        val repo = deps.garminRepository()
        val importUseCase = deps.garminImportUseCase()
        val activityRepository = deps.activityRepository()
        val activityTypeDao = deps.activityTypeDao()
        val categoryDao = deps.categoryDao()

        // M18.61g-FIX 4 (User: "Schlaf wird nicht eingefügt"): Der Insert
        // nutzt activityTypeId = "sleep" — ein Foreign-Key auf activity_type.
        // Wurde der Typ gelöscht/umbenannt (Lösch-Funktion seit M18.50),
        // schlägt der Insert still fehl (FK-Verletzung) -> kein Schlaf.
        // Idempotenter Upsert stellt Typ + Kategorie vor dem Import sicher
        // (activity_type.default_category_id ist FK auf category).
        try {
            categoryDao.insert(
                com.d_drostes_apps.aevum.data.model.Category(
                    id = "sleep",
                    name = "Schlaf",
                    color = "#334155",
                    icon = "◒",
                    isSystem = true,
                    sortOrder = 20
                )
            )
            activityTypeDao.insert(
                com.d_drostes_apps.aevum.data.model.ActivityType(
                    id = "sleep",
                    name = "Schlaf",
                    defaultCategoryId = "sleep",
                    isSystem = true,
                    propertiesJson = "{\"overlay\": false}",
                    positivityScore = 70,
                    icon = "🌙",
                    color = 0xFF3949AB.toLong()
                )
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ActivityType/Category sleep upsert fehlgeschlagen: ${e.message}")
        }

        // Bridge erreichbar?
        val status = api.getStatus()
        if (!status.connected) {
            android.util.Log.w(TAG, "Garmin-Bridge nicht verbunden: ${status.error}")
            // M18.61g-FIX 3: Tote gespeicherte Tunnel-URL (Cloudflare
            // rotiert) -> Override verwerfen, beim nächsten Sync die
            // aktuelle BuildConfig-URL nutzen.
            api.resetBaseUrlIfStale()
            return Result.retry()
        }

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)

        // 1) Tageszusammenfassung (heute + gestern für die Kacheln)
        try {
            val todayData = api.getToday(today.toString())
            if (todayData != null) {
                repo.upsertSummary(
                    GarminDailySummary(
                        date = todayData.date,
                        steps = todayData.steps,
                        distanceMeters = todayData.distanceMeters,
                        calories = todayData.totalCalories,
                        activeCalories = todayData.activeCalories,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            val yesterday = api.getToday(today.minusDays(1).toString())
            if (yesterday != null) {
                repo.upsertSummary(
                    GarminDailySummary(
                        date = yesterday.date,
                        steps = yesterday.steps,
                        distanceMeters = yesterday.distanceMeters,
                        calories = yesterday.totalCalories,
                        activeCalories = yesterday.activeCalories,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Tageszusammenfassung fehlgeschlagen: ${e.message}")
        }

        // 2) Schlaf (letzte 7 Nächte).
        // M18.61g-FIX 2 (User: "Der Schlaf von Garmin letzte Nacht soll
        // eingefügt werden"): Garmin-Schlaf ist eine ECHTE Messung (wie
        // Garmin-Aktivitäten) und wird IMMER importiert — unabhängig von
        // der Schlaf-Quelle. Die Schlaf-Quelle steuert nur, ob die
        // Bildschirmzeit-Heuristik läuft. Der Import ersetzt eine
        // bestehende Screen-Heuristik-Session der Nacht (Garmin gewinnt).
        try {
            importSleep(api, activityRepository, today)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Schlaf-Import fehlgeschlagen: ${e.message}")
        }

        // 3) Aktivitäten → Timeline (Zeitraum-Überschreibung)
        try {
            val activities = api.getActivities(limit = 20)
            if (activities.isNotEmpty()) {
                val mapped = activities.map { it.toEntity() }
                val imported = importUseCase.importActivities(mapped)
                if (imported > 0) {
                    android.util.Log.i(TAG, "$imported Garmin-Aktivitäten importiert")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Aktivitäts-Import fehlgeschlagen: ${e.message}")
        }

        api.lastSyncAt = System.currentTimeMillis()
        return Result.success()
    }

    /**
     * M18.58: Garmin-Schlaf DIREKT in die Timeline (kein Review).
     * M18.59-FIX (User: "Schlaf der letzten Nacht wurde nicht mit den
     * Garmin-Daten überschrieben"): Vorher wurde bei Überlappung mit
     * einer bestehenden Sleep-Session NUR dedupliziert (skip) — die
     * per Bildschirmzeit erkannte Session blieb stehen. Jetzt: Wenn
     * Garmin die Schlaf-Quelle ist, ERSETZT der Garmin-Schlaf die
     * bestehende Sleep-Session der Nacht (softDelete + Insert). Nur
     * Sleep-Sessions werden ersetzt — andere Aktivitäten bleiben
     * unberührt.
     *
     * M18.59-FIX 2 (User: "letzte Nacht wird nicht überschrieben"):
     * Garmin ordnet Schlaf dem AUFWACH-Tag zu (die Bridge liefert für
     * date=X den Schlaf, der am Morgen von X endet). Der Import startete
     * bei minusDays(1) — damit fehlte die NACHT ZUM HEUTE (die letzte
     * Nacht!) und die Screen-Heuristik-Session blieb stehen. Jetzt:
     * 0..6 (heute + 6 zurück) — die letzte Nacht wird mitimportiert.
     *
     * M18.62-FIX (User: "3x 8h Schlaf übereinander = 24h heute, obwohl
     * erst 11 Uhr"): Garmin ändert die Schlafzeit NACH dem Sync noch
     * nachträglich (8h -> 9h). Der alte Code machte bei JEDER Änderung
     * softDelete + Insert mit NEUER UUID. Wenn die neue Zeit die alte
     * nicht mehr überlappt (z.B. leicht verschoben), wurde die alte
     * Session nicht gefunden -> Duplikat. Jetzt wird die Nacht über den
     * AUFWACH-TAG identifiziert (date=X = Schlaf der Nacht zum Morgen
     * von X) und die bestehende GARMIN_SLEEP_AUTO-Session der Nacht
     * UPDATET (gleiche ID) statt neu angelegt. Bestands-Duplikate
     * derselben Nacht werden bereinigt.
     */
    private suspend fun importSleep(
        api: GarminApiClient,
        repo: com.d_drostes_apps.aevum.data.repository.ActivityRepository,
        today: LocalDate
    ) {
        val zone = ZoneId.systemDefault()
        for (i in 0..6) {
            val day = today.minusDays(i.toLong())
            val sleep = api.getSleep(day.toString()) ?: continue

            val now = System.currentTimeMillis()

            // M18.64: STABILE NACHT-IDENTITÄT statt Zeit-Überlappung.
            //
            // Root-Cause (empirisch via Bridge-Caches belegt): Garmin
            // ändert dieselbe Nacht NACH dem Sync mehrfach nachträglich
            // (z.B. 23:46–08:01 → 00:10–08:00). Ein Dedup, der an der
            // exakten Zeit oder einem endAt-Fenster hängt, verfehlt die
            // Session nach einer solchen Änderung und legt ein Duplikat
            // an — das sich bei jedem 30-Min-Sync weiter aufschaukelt.
            //
            // Die einzig stabile Semantik ist der AUFWACH-TAG (date=X =
            // Schlaf der Nacht zum Morgen von X). Diese Identität wird
            // in activity_session.external_id persistiert:
            //   Sync 1  → Insert mit externalId="garmin_sleep_<date>"
            //   Sync 2..N → getByExternalId findet die Session → UPDATE
            //   (Zeiten übernehmen, revision+1) — NIE ein zweiter Insert.
            // Garmin-Änderungen aktualisieren also denselben Eintrag.
            val externalId = GarminSleepDedup.externalIdForNight(day.toString())

            // 1) Bereits persistierte Nacht-Identität?
            val byExternalId = repo.getByExternalId(externalId).first()
            if (byExternalId.isNotEmpty()) {
                // Primär = älteste (deterministisch); Rest = Duplikate
                // (sollte es nicht geben, aber heilt z.B. abgebrochene
                // Syncs) → bereinigen.
                val primary = GarminSleepDedup.primaryByExternalId(byExternalId)!!
                for (dup in byExternalId.filter { it.id != primary.id }) {
                    repo.softDelete(dup.id, now)
                    android.util.Log.i(TAG, "Garmin-Schlaf-Duplikat (externalId) bereinigt ${dup.id}")
                }
                repo.update(
                    primary.copy(
                        startAt = sleep.startGmtMs,
                        endAt = sleep.endGmtMs,
                        updatedAt = now,
                        revision = primary.revision + 1
                    )
                )
                android.util.Log.i(
                    TAG,
                    "Garmin-Schlaf $day aktualisiert (externalId=$externalId, ${sleep.sleepTimeSeconds}s)"
                )
                continue
            }

            // 2) Keine persistierte Identität → Alt-Bestand / Heuristik
            //    im Nachtfenster suchen (Überlappungs-basiert, M18.63).
            val nightStart = day.atStartOfDay(zone).minusHours(12).toInstant().toEpochMilli()
            val nightEnd = day.atStartOfDay(zone).plusHours(14).toInstant().toEpochMilli()
            val nightSessions = repo.getOverlappingRange(nightStart, nightEnd)
                .first()
                .filter {
                    it.deletedAt == null && it.activityTypeId == "sleep"
                }
            val nightSessionsOverlapping = GarminSleepDedup.overlappingSessions(
                nightSessions, sleep.startGmtMs, sleep.endGmtMs
            )
            val garminSessions = nightSessionsOverlapping.filter { it.sourceType == "GARMIN_SLEEP_AUTO" }
            val otherSleep = nightSessionsOverlapping.filter { it.sourceType != "GARMIN_SLEEP_AUTO" }

            // Heuristik-Sessions der Nacht löschen (Garmin gewinnt —
            // M18.59-Policy: die gewählte Quelle ist die Wahrheit).
            for (old in otherSleep) {
                repo.softDelete(old.id, now)
                android.util.Log.i(TAG, "Garmin-Schlaf ersetzt Heuristik-Session ${old.id}")
            }

            if (garminSessions.isNotEmpty()) {
                // Alt-Bestand OHNE externalId (vor M18.64 importiert):
                // älteste Session als Primär übernehmen, externalId
                // nachtragen, Zeiten aktualisieren; Duplikate derselben
                // Nacht bereinigen. Damit werden Bestands-Duplikate
                // sicher erkannt und zusammengeführt (Akzeptanzkriterium 2).
                val primary = GarminSleepDedup.primarySession(garminSessions)!!
                for (dup in GarminSleepDedup.duplicateSessions(garminSessions)) {
                    repo.softDelete(dup.id, now)
                    android.util.Log.i(TAG, "Garmin-Schlaf-Duplikat bereinigt ${dup.id}")
                }
                repo.update(
                    primary.copy(
                        externalId = externalId,
                        startAt = sleep.startGmtMs,
                        endAt = sleep.endGmtMs,
                        updatedAt = now,
                        revision = primary.revision + 1
                    )
                )
                android.util.Log.i(
                    TAG,
                    "Garmin-Schlaf $day übernommen + externalId nachgetragen (${sleep.sleepTimeSeconds}s)"
                )
            } else {
                // 3) Wirklich neu → Insert MIT externalId (idempotent für
                //    alle weiteren Syncs).
                val session = com.d_drostes_apps.aevum.data.model.ActivitySession(
                    id = UUID.randomUUID().toString(),
                    title = "Schlaf",
                    categoryId = null,
                    activityTypeId = "sleep",
                    startAt = sleep.startGmtMs,
                    endAt = sleep.endGmtMs,
                    sourceType = "GARMIN_SLEEP_AUTO",
                    createdBy = "GARMIN_IMPORT",
                    updatedBy = null,
                    sourceCandidateId = null,
                    externalId = externalId,
                    confidence = 1.0f,
                    isUserEdited = false,
                    createdAt = now,
                    updatedAt = now,
                    revision = 1
                )
                repo.insert(session)
                android.util.Log.i(TAG, "Garmin-Schlaf $day importiert (externalId=$externalId, ${sleep.sleepTimeSeconds}s)")
            }
        }
    }

    /** M18.58: Bridge-DTO → Garmin-Entity. */
    private fun GarminRemoteActivity.toEntity(): GarminActivity {
        // start_gmt z.B. "2026-08-08 15:57:49" (Garmin GMT, keine Zulu-Suffixe)
        val startEpoch = parseGarminTimestamp(startGmt)
        return GarminActivity(
            id = UUID.randomUUID().toString(),
            externalId = activityId,
            activityType = type,
            title = name,
            startAt = startEpoch,
            endAt = startEpoch + (durationSeconds * 1000L).toLong(),
            distanceMeters = distanceMeters,
            calories = calories,
            importedAt = System.currentTimeMillis(),
            sessionId = null
        )
    }

    companion object {
        private const val TAG = "GarminSyncWorker"

        // M18.63: Prozessinterner Mutex — serialisiert alle Sync-Läufe.
        private val syncMutex = kotlinx.coroutines.sync.Mutex()

        /**
         * Parst Garmin-Zeitstempel "2026-08-08 15:57:49" (GMT) in epochMillis.
         * Fallback: jetzt.
         */
        fun parseGarminTimestamp(raw: String): Long {
            return try {
                val dt = java.time.LocalDateTime.parse(
                    raw.trim(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                )
                dt.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}
