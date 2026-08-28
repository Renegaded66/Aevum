package com.d_drostes_apps.aevum.domain.garmin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
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
    private val activityTypeRepository: ActivityTypeRepository,
    @ApplicationContext private val context: Context? = null
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
        // M18.66-FIX17: MutableSet — Auto-erstellte Typen werden während
        // der Import-Runde hinzugefügt, damit mehrere Activities desselben
        // Garmin-Typs denselben (neuen) Typ nutzen.
        val allTypes = activityTypeRepository.getAll().first()
        val typeIds = allTypes.map { it.id }.toMutableSet()
        // M18.67: Name → ID (case-insensitive) für das name-basierte
        // Matching — existierende Typen behalten ihre (vom User angepasste)
        // Güte, weil sie nie neu erstellt werden.
        val nameToId = allTypes.associate { it.name.lowercase() to it.id }.toMutableMap()
        val typeIdResolver: suspend (String, String, Boolean) -> String? = { garminType, displayName, hasPrefix ->
            resolveOrCreateTypeId(garminType, displayName, nameToId, typeIds, hasPrefix)
        }

        var imported = 0
        for (activity in activities) {
            // 1) Dedup: schon importiert?
            val existing = garminRepository.getActivityByExternalId(activity.externalId)
            if (existing != null) continue

            // M18.67: Garmin-Namen sind oft "<Ortsname> <Typ>" (z.B.
            // "Dortmund Laufen", "Dortmund Yoga"). Der Ortsname ist für
            // Aevum irrelevant — der Typ-Name wird aus dem Namen extrahiert
            // und der Titel auf den reinen Typ-Namen gekürzt.
            val cleanTitle = cleanActivityTitle(activity.title)
            // M18.67-FIX: Hat der Garmin-Name einen Ortsnamen-Präfix
            // (2+ Wörter, z.B. "Syddjurs Laufen")? Dann ist das letzte Wort
            // der Typ-Name und wird als eigener Typ erstellt (Güte 50).
            val hasLocationPrefix = activity.title.trim().split(Regex("\\s+")).size >= 2

            // M18.61f-FUSION: Garmin-Typ früh auflösen, um manuelle
            // Sessions desselben Typs zu erkennen (User: "manuell
            // Krafttraining + Fitnesstracker synchronisiert -> doppelte
            // Aktivität. Es sollte nur eine sein, frühester Start +
            // spätester Endpunkt").
            val typeId = typeIdResolver(activity.activityType, cleanTitle, hasLocationPrefix)

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
                title = cleanTitle,
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
     *
     * M18.66-FIX17 (User: "es gibt aktuell gar keine Activity Krafttraining.
     * Wenn eine Activity gesynct wird und es gibt noch nicht diese Activity,
     * soll eine automatisch erstellt werden"): Bekannte Typen werden wie
     * bisher auf bestehende Aevum-Typen gemappt. UNBEKANNTE Garmin-Typen
     * (z.B. strength_training, yoga, pilates) erzeugen jetzt automatisch
     * einen neuen ActivityType in der DB (isSystem=false, keine Kategorie —
     * der User weist sie selbst zu). Danach wird jede weitere Session
     * desselben Garmin-Typs dem erstellten Typ zugeordnet.
     *
     * M18.67 (User: "Garmin liefert '<Ortsname> Laufen' statt 'Laufen'"):
     * 1. NAME-basiertes Matching zuerst: "Dortmund Yoga" → "Yoga". Existiert
     *    bereits ein ActivityType mit diesem Namen (case-insensitive), wird
     *    GENAU dieser Typ genutzt — die vom User angepasste Güte bleibt
     *    erhalten (User: "weil die Güte vom Nutzer vielleicht schon
     *    angepasst wurde und natürlich bestehen bleiben soll").
     * 2. Fallback: typeKey-Mapping auf bekannte Seeds (bestehendes
     *    Verhalten für z.B. "Abendrunde" → running → joggen).
     * 3. Kein Treffer → Auto-Erstellung mit dem extrahierten Namen und
     *    Güte 50 (neutral, User: "erstelle erst einen Activity Type Yoga
     *    mit Güte 50").
     */
    private suspend fun resolveOrCreateTypeId(
        garminType: String,
        displayName: String,
        nameToId: MutableMap<String, String>,
        typeIds: MutableSet<String>,
        hasLocationPrefix: Boolean
    ): String? {
        // 1) Name-basiertes Matching (M18.67)
        nameToId[displayName.lowercase()]?.let { return it }

        // 2) M18.67-FIX (User: "Syddjurs Laufen" → Typ "Laufen" ERSTELLEN):
        //    Hat der Garmin-Name einen Ortsnamen-Präfix (2+ Wörter), ist das
        //    letzte Wort der Typ-Name — der wird IMMER als eigener Typ
        //    angelegt (Güte 50), NICHT auf einen Seed gemappt. Nur bei
        //    1-Wort-Namen (z.B. "Abendrunde") greift der Seed-Fallback.
        if (hasLocationPrefix) {
            return createType(garminType, displayName, nameToId, typeIds)
        }

        // 3) Fallback: typeKey-Mapping auf bekannte Seeds
        val lower = garminType.lowercase()
        val known = when (lower) {
            "running" -> listOf("joggen", "fitness", "other")
            "cycling" -> listOf("radfahren", "fitness", "other")
            "walking", "hiking" -> listOf("spazieren", "leisure", "other")
            "swimming" -> listOf("fitness", "sport", "other")
            "other" -> listOf("other")
            else -> emptyList()
        }
        known.firstOrNull { it in typeIds }?.let { return it }

        // 4) Auto-Erstellung (M18.66-FIX17 + M18.67)
        return createType(garminType, displayName, nameToId, typeIds)
    }

    /** Erstellt einen neuen ActivityType (Güte 50) mit stabilem Slug als ID. */
    private suspend fun createType(
        garminType: String,
        displayName: String,
        nameToId: MutableMap<String, String>,
        typeIds: MutableSet<String>
    ): String? {
        val lower = garminType.lowercase()
        val id = lower
        if (id in typeIds) return id
        val icon = when (lower) {
            "strength_training" -> "🏋️"
            "yoga", "pilates" -> "🧘"
            "elliptical", "indoor_cycling" -> "🚴"
            "rowing" -> "🚣"
            "cardio", "hiit" -> "❤️🔥"
            "dance" -> "💃"
            "stair_climbing" -> "🪜"
            else -> "🏃"
        }
        try {
            val newType = ActivityType(
                id = id,
                name = displayName,
                defaultCategoryId = null,
                isSystem = false,
                propertiesJson = "{\"overlay\": false}",
                positivityScore = 50,
                icon = icon,
                color = 0L
            )
            activityTypeRepository.insert(newType)
            typeIds.add(id)
            nameToId[displayName.lowercase()] = id
        } catch (e: Exception) {
            // Konkurrenz: Typ wurde parallel schon erstellt — ID erneut prüfen.
            if (id !in typeIds) return "other"
        }
        return id
    }

    /**
     * M18.67: Garmin-Namen sind oft "<Ortsname> <Typ>" (z.B. "Dortmund
     * Laufen", "Dortmund Yoga"). Der Ortsname ist für Aevum irrelevant —
     * der reine Typ-Name (letztes Wort) wird als Titel + Typ-Name genutzt.
     */
    private fun cleanActivityTitle(raw: String): String {
        // M18.67-FIX (live verifiziert): Garmin fügt bei manchen Typen ein
        // unsichtbares Zero-Width-Space ein (z.B. "Krafttrai\u200bning") —
        // vor dem Split entfernen, sonst schlägt der Name-Match fehl.
        val noInvisible = raw.replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "")
        val trimmed = noInvisible.trim()
        if (trimmed.isBlank()) return context?.getString(com.d_drostes_apps.aevum.R.string.common_activity_fallback) ?: "Aktivität"
        val words = trimmed.split(Regex("\\s+"))
        return if (words.size >= 2) words.last() else trimmed
    }

    /**
     * M18.58: Alte, rein auflösende Variante — nur noch für die Tests
     * relevant. Die Produktion nutzt [resolveOrCreateTypeId].
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
