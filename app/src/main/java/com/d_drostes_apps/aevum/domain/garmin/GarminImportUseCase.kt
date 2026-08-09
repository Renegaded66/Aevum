package com.d_drostes_apps.aevum.domain.garmin

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.GarminActivity
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.GarminRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.58: Garmin-Aktivitäts-Import mit ZEITRAUM-ÜBERSCHREIBUNG.
 *
 * User-Wunsch: "Man zeichnet mit Garmin ja Aktivitäten wie joggen auf.
 * Diese soll auch automatisch in die Timeline eingetragen werden. Falls
 * was zu der Zeit in der Timeline steht, soll das überschrieben werden,
 * aber nicht die gesamte schon aufgezeichnete Activity, sondern nur der
 * Zeitraum, in dem die neue Activity eingefügt wird."
 *
 * Logik pro Garmin-Aktivität:
 *  1. Dedup: externalId bereits importiert? → skip (idempotent).
 *  2. Alle bestehenden Sessions, die [startAt, endAt] überlappen, werden
 *     GEKÜRZT — nicht gelöscht:
 *       a. Session beginnt VOR der Aktivität → endAt = activity.startAt
 *          (der überlappte Teil wird entfernt, der Rest bleibt)
 *       b. Session endet NACH der Aktivität → startAt = activity.endAt
 *          (der überlappte Teil wird entfernt, der Rest bleibt)
 *       c. Session liegt KOMPLETT innerhalb → wird gelöscht (softDelete)
 *          — sie ist vollständig durch die Garmin-Aktivität ersetzt.
 *       d. Aktivität liegt MITTEN in einer Session → Session wird in zwei
 *          Teile gesplittet (vorher/nachher), der überlappte Teil entfällt.
 *  3. Die Garmin-Aktivität wird als neue Session eingetragen
 *     (sourceType = "GARMIN_AUTO").
 *
 * Der User will "überschreiben, aber nicht die gesamte Activity löschen" —
 * also wird die bestehende Session nur im überlappten Zeitraum entfernt.
 *
 * M18.58-FIX (FK-Sicherheit): Die Ziel-ActivityType-ID wird NICHT hart
 * gemappt ("joggen", "radfahren" existieren nicht in den Seeds). Statt-
 * dessen wird zur Laufzeit gegen die echten Typen in der DB aufgelöst:
 *   running → joggen, sonst fitness, sonst other
 *   cycling → radfahren, sonst fitness, sonst other
 *   walking/hiking → spazieren, sonst leisure, sonst other
 *   strength_training/swimming → fitness/sport, sonst other
 * "other" ist ein System-Typ und kann vom User nicht gelöscht werden
 * (M18.51) — damit kann der Insert nie an einem FK scheitern.
 */
@Singleton
class GarminImportUseCase @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val garminRepository: GarminRepository,
    private val activityTypeRepository: ActivityTypeRepository
) {
    /**
     * Importiert eine Liste von Garmin-Aktivitäten (idempotent).
     *
     * @param activities Garmin-Aktivitäten (bereits von der API gefetcht)
     * @return Anzahl der neu eingetragenen Sessions
     */
    suspend fun importActivities(
        activities: List<GarminActivity>
    ): Int {
        if (activities.isEmpty()) return 0
        // Einmal laden: alle existierenden ActivityTypes (FK-Sicherheit).
        val typeIds = activityTypeRepository.getAll().first().map { it.id }.toSet()
        val typeIdResolver: (String) -> String? = { garminTypeToAevumTypeId(it, typeIds) }

        var imported = 0
        for (activity in activities) {
            // 1) Dedup: schon importiert?
            val existing = garminRepository.getActivityByExternalId(activity.externalId)
            if (existing != null) continue

            // M18.61f-FUSION: Garmin-Typ früh auflösen, um manuelle
            // Sessions desselben Typs zu erkennen (User: "manuell
            // Krafttraining + Fitnesstracker synchronisiert -> doppelte
            // Aktivität. Es sollte nur eine sein, frühester Start +
            // spätester Endpunkt").
            val typeId = typeIdResolver(activity.activityType)

            // 2) Überlappende Sessions kürzen/löschen
            val overlapping = activityRepository.getOverlappingRange(
                activity.startAt - 1000L,
                activity.endAt + 1000L
            ).first().filter { it.deletedAt == null }

            // WICHTIG: Keine Sleep-Sessions kürzen! Schlaf ist geschützt
            // (User-Policy M18.51) — eine Garmin-Aktivität überschreibt
            // nie den Schlaf.
            val splittable = overlapping.filter { it.activityTypeId != "sleep" }

            // M18.61f-FUSION: Gibt es eine MANUELLE Session mit demselben
            // Aktivitätstyp im Überlappungsbereich? Dann wird NICHT
            // gesplittet, sondern fusioniert: frühester Start + spätester
            // Endpunkt, eine Session. (Garmin-Auto-Sessions werden nicht
            // fusioniert — die sind bereits der Import selbst.)
            val manualMatch = splittable.firstOrNull {
                it.sourceType != "GARMIN_AUTO" &&
                    it.activityTypeId != null &&
                    it.activityTypeId == typeId
            }

            val now = System.currentTimeMillis()
            if (manualMatch != null) {
                // FUSION: manuelle Session erweitern auf [min(Start), max(Ende)]
                val fusedStart = minOf(manualMatch.startAt, activity.startAt)
                val fusedEnd = maxOf(manualMatch.endAt ?: now, activity.endAt)
                activityRepository.update(
                    manualMatch.copy(
                        startAt = fusedStart,
                        endAt = fusedEnd,
                        title = manualMatch.title,
                        updatedAt = now,
                        revision = manualMatch.revision + 1
                    )
                )
                // Garmin-Aktivität als importiert markieren (Link auf die
                // fusionierte Session — kein neuer Eintrag).
                garminRepository.upsertActivity(
                    activity.copy(
                        sessionId = manualMatch.id,
                        importedAt = now
                    )
                )
                imported++
                continue
            }

            for (session in splittable) {
                val sessionStart = session.startAt
                val sessionEnd = session.endAt ?: now
                val activityStart = activity.startAt
                val activityEnd = activity.endAt

                // a) Session beginnt vor der Aktivität und endet nach deren Anfang
                //    → endAt kürzen auf activityStart (wenn der Rest sinnvoll bleibt)
                // b) Session endet nach der Aktivität und beginnt vor deren Ende
                //    → startAt kürzen auf activityEnd
                // c) Komplett innerhalb → softDelete
                val startsBefore = sessionStart < activityStart
                val endsAfter = sessionEnd > activityEnd

                when {
                    startsBefore && endsAfter -> {
                        // Aktivität liegt MITTEN in der Session → zwei Teile:
                        // Teil 1: [sessionStart, activityStart], Teil 2: [activityEnd, sessionEnd]
                        activityRepository.update(
                            session.copy(
                                endAt = activityStart,
                                updatedAt = now,
                                revision = session.revision + 1
                            )
                        )
                        // Zweiten Teil als neue Session anlegen (gleicher Typ!)
                        activityRepository.insert(
                            session.copy(
                                id = UUID.randomUUID().toString(),
                                startAt = activityEnd,
                                endAt = sessionEnd,
                                title = session.title,
                                updatedAt = now,
                                revision = 1,
                                createdAt = now
                            )
                        )
                    }
                    startsBefore -> {
                        // Session beginnt vorher, endet aber in der Aktivität
                        // → nur Anfang behalten: endAt = activityStart
                        if (activityStart - sessionStart > 60_000L) {
                            activityRepository.update(
                                session.copy(
                                    endAt = activityStart,
                                    updatedAt = now,
                                    revision = session.revision + 1
                                )
                            )
                        } else {
                            // Rest wäre < 1min → komplett ersetzen
                            activityRepository.softDelete(session.id, now)
                        }
                    }
                    endsAfter -> {
                        // Session beginnt in der Aktivität, endet danach
                        // → nur Ende behalten: startAt = activityEnd
                        if (sessionEnd - activityEnd > 60_000L) {
                            activityRepository.update(
                                session.copy(
                                    startAt = activityEnd,
                                    updatedAt = now,
                                    revision = session.revision + 1
                                )
                            )
                        } else {
                            activityRepository.softDelete(session.id, now)
                        }
                    }
                    else -> {
                        // Komplett innerhalb der Garmin-Aktivität → ersetzen
                        activityRepository.softDelete(session.id, now)
                    }
                }
            }

            // 3) Neue Garmin-Session eintragen (FK-sicher aufgelöst)
            val session = ActivitySession(
                id = UUID.randomUUID().toString(),
                title = activity.title,
                categoryId = null, // Kategorie leitet die Timeline aus dem Typ ab
                activityTypeId = typeId,
                startAt = activity.startAt,
                endAt = activity.endAt,
                sourceType = "GARMIN_AUTO",
                createdBy = "GARMIN_IMPORT",
                updatedBy = null,
                sourceCandidateId = null,
                confidence = 1.0f,
                isUserEdited = false,
                createdAt = now,
                updatedAt = now,
                revision = 1
            )
            activityRepository.insert(session)

            // 4) Garmin-Aktivität als importiert markieren (mit Session-Link)
            garminRepository.upsertActivity(
                activity.copy(
                    sessionId = session.id,
                    importedAt = now
                )
            )
            imported++
        }
        return imported
    }

    /**
     * M18.58: Garmin-Typ → Aevum-ActivityType-ID — dynamisch gegen die
     * wirklich existierenden Typen aufgelöst (FK-Sicherheit, s. Klassen-
     * Doku). [typeIds] = IDs aller Typen in der DB.
     */
    private fun garminTypeToAevumTypeId(garminType: String, typeIds: Set<String>): String? {
        val preferred = when (garminType.lowercase()) {
            "running" -> listOf("joggen", "fitness", "other")
            "cycling" -> listOf("radfahren", "fitness", "other")
            "walking", "hiking" -> listOf("spazieren", "leisure", "other")
            "swimming" -> listOf("fitness", "sport", "other")
            "strength_training" -> listOf("fitness", "sport", "other")
            "other" -> listOf("other")
            else -> listOf("other", "fitness")
        }
        return preferred.firstOrNull { it in typeIds } ?: "other"
    }
}
