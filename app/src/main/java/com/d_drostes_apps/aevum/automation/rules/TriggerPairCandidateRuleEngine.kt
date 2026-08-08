package com.d_drostes_apps.aevum.automation.rules

import com.d_drostes_apps.aevum.automation.model.AutomationConstants
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import java.util.Locale
import javax.inject.Inject

/**
 * Local, transparent and deterministic candidate rule engine.
 *
 * M7 extends M6.2 with specific travel-pair rules:
 * - Home → Work = Arbeitsweg
 * - Work → Home = Heimweg
 * - Home → Gym = Anfahrt Fitness
 * - Home → Shop/Supermarkt = Einkauf
 * - Generic Exit → Enter = Transit (lower confidence)
 *
 * Design rules:
 * - Trigger events are facts; candidates are suggestions only.
 * - Rules are intentionally explainable and deterministic.
 * - Candidate IDs are stable per trigger pair, so rerunning the engine is idempotent.
 * - Open exits without a later destination intentionally produce no candidate yet.
 *
 * M16.7: Plausibility-Check ("Devon-Heuristik"). Phantom-Travel-Candidates
 * (z.B. "Gym → Home 11–13 Uhr" obwohl der User nie das Haus verlassen hat)
 * entstehen, wenn ein Geofence-Rand kurz GPS-Drift zeigt. Wir filtern
 * solche Candidates, indem wir prüfen:
 *  - Wenn der Travel "von einem Nicht-Home Geofence zurück zu Home" geht,
 *    muss ein HOME_LEFT-Trigger innerhalb von [travel_start - 4h, travel_end]
 *    liegen. Sonst ist es ein Phantom.
 *  - Wenn der Travel "von einem Geofence A zu Geofence B" geht und beide
 *    ungleich Home sind, prüfen wir, ob A ein HIGH-anchor hat (DWELL oder
 *    kein Drift). Wenn A nur ein LOW-Anchor ist, ignorieren.
 */
class TriggerPairCandidateRuleEngine @Inject constructor() {
    fun evaluate(
        triggers: List<TriggerEvent>,
        geofences: List<PlaceGeofence>,
        now: Long = System.currentTimeMillis()
    ): List<ActivityCandidate> {
        if (triggers.size < 2) return emptyList()
        val byGeofence = geofences.associateBy { it.id }
        val ordered = triggers
            .filter { it.geofenceId != null && it.occurredAt <= now }
            .sortedBy { it.occurredAt }

        return ordered
            // M16.6: LOW-anchor-Trigger (vom SleepShield markiert) ignorieren.
            // Sie stehen weiterhin in der DB für Debugging, erzeugen aber
            // keine Travel-Candidates, weil sie mitten in einem Schlaf-
            // Fenster oder in der Aufwachphase liegen können.
            .filter { it.anchorQuality != "LOW" }
            .zipWithNext()
            .mapNotNull { (first, second) -> candidateForPair(first, second, byGeofence, ordered) }
            .filter { it.endAt - it.startAt in MIN_DURATION_MS..MAX_DURATION_MS }
            .distinctBy { it.id }
    }

    private fun candidateForPair(
        first: TriggerEvent,
        second: TriggerEvent,
        geofences: Map<String, PlaceGeofence>,
        // M16.7: Wir brauchen alle Trigger für den Plausibility-Check,
        // nicht nur die zwei im Pair.
        allTriggers: List<TriggerEvent>
    ): ActivityCandidate? {
        val firstPlace = geofences[first.geofenceId] ?: return null
        val secondPlace = geofences[second.geofenceId] ?: return null
        val firstKind = first.transitionKind()
        val secondKind = second.transitionKind()

        val candidate = when {
            // Stay: Enter → Exit at same geofence
            firstKind == TriggerKind.Enter && secondKind == TriggerKind.Exit && first.geofenceId == second.geofenceId ->
                stayCandidate(first, second, firstPlace)

            // Specific travel: known pairs with better naming and confidence
            firstKind == TriggerKind.Exit && secondKind == TriggerKind.Enter && first.geofenceId != second.geofenceId ->
                specificTravelCandidate(first, second, firstPlace, secondPlace, geofences, allTriggers)

            // Away from home: Exit → Enter at same geofence (home-like)
            firstKind == TriggerKind.Exit && secondKind == TriggerKind.Enter && first.geofenceId == second.geofenceId && firstPlace.isHomeLike() ->
                awayFromHomeCandidate(first, second, firstPlace)

            else -> null
        } ?: return null

        // M16.7: Plausibility-Check ("Devon-Heuristik").
        // Phantom-Travel-Candidates wie "Gym → Home 11–13 Uhr" entstehen,
        // wenn ein Geofence-Rand GPS-Drift zeigt. Wir prüfen zwei Bedingungen:
        //  1. Wenn das Travel von einem Nicht-Home Geofence zurück zu Home
        //     geht (also "Ankommen"), muss ein HOME_LEFT in den letzten
        //     [HOME_LEFT_LOOKBACK_MS] vor dem EXIT-Trigger liegen. Wenn
        //     nicht, ist der Travel ein Phantom (User war nie weg).
        //  2. Wenn das Travel zwischen zwei Nicht-Home Geofences geht und
        //     der EXIT-Trigger LOW-Anchor wäre (zur Sicherheit nochmal
        //     prüfen), wird er gefiltert. (Eigentlich schon durch
        //     filter { anchorQuality != "LOW" } oben erledigt — doppelte
        //     Sicherheit.)
        if (!passesPlausibilityCheck(candidate, first, second, allTriggers, geofences)) {
            // Keine Android-Log-Aufrufe in der reinen Rule-Engine: Die Klasse
            // wird auch in JVM-Unit-Tests ohne Android-Runtime ausgeführt.
            return null
        }
        return candidate
    }

    /**
     * M16.7: Plausibility-Check für Travel-Candidates.
     *
     * @return true wenn der Travel legitim erscheint, false wenn er verworfen
     *         werden soll (Phantom).
     */
    private fun passesPlausibilityCheck(
        candidate: ActivityCandidate,
        first: TriggerEvent,
        second: TriggerEvent,
        allTriggers: List<TriggerEvent>,
        geofences: Map<String, PlaceGeofence>
    ): Boolean {
        // Nur für Travel-Candidates prüfen (Stay/AwayFromHome sind lokal)
        if (candidate.activityTypeId != "transport") return true

        val firstPlace = geofences[first.geofenceId] ?: return true
        val secondPlace = geofences[second.geofenceId] ?: return true

        // Fall 1: "Zurück nach Hause" — wenn der Travel bei einem Home-Geofence
        // endet und bei einem Nicht-Home-Geofence startet, suchen wir nach
        // einem HOME_LEFT in den letzten Stunden. Ohne HOME_LEFT ist es ein
        // Phantom: der User war die ganze Zeit zu Hause, und der Geofence-
        // Rand des Nicht-Home-Geofences hat nur GPS-Drift gezeigt.
        if (secondPlace.isHomeLike() && !firstPlace.isHomeLike() && !firstPlace.isWorkLike()) {
            val homeLeftExists = allTriggers.any { t ->
                t.type == AutomationConstants.TRIGGER_HOME_LEFT &&
                    t.occurredAt in (first.occurredAt - HOME_LEFT_LOOKBACK_MS)..second.occurredAt
            }
            // Wenn KEIN HOME_LEFT gefunden wird, ist der Travel verdächtig.
            // Aber: HOME_LEFT wird nur persistiert, wenn der User wirklich
            // das Haus verlassen hat. Wenn der HOME_LEFT fehlt, hieß das
            // auch in der Vergangenheit "User war zu Hause". Daher: Phantom.
            if (!homeLeftExists) return false
        }

        // Fall 2: Travel zwischen zwei Nicht-Home Geofences. Hier ist die
        // Plausibility schwieriger — wir prüfen, ob einer der beiden
        // Trigger ein LOW-Anchor wäre (dann ist es Drift). Schon oben
        // gefiltert, doppelte Sicherheit.
        if (first.anchorQuality == "LOW") return false

        return true
    }

    private fun specificTravelCandidate(
        exit: TriggerEvent,
        enter: TriggerEvent,
        from: PlaceGeofence,
        to: PlaceGeofence,
        geofences: Map<String, PlaceGeofence>,
        @Suppress("UNUSED_PARAMETER") allTriggers: List<TriggerEvent>
    ): ActivityCandidate {
        return when {
            from.isHomeLike() && to.isWorkLike() ->
                travelWithName(exit, enter, "Arbeitsweg", 0.85f, geofences)

            from.isWorkLike() && to.isHomeLike() ->
                travelWithName(exit, enter, "Heimweg", 0.85f, geofences)

            from.isHomeLike() && to.isGymLike() ->
                travelWithName(exit, enter, "Anfahrt: Fitnessstudio", 0.78f, geofences)

            from.isHomeLike() && (to.isShopLike() || to.isSupermarketLike()) ->
                travelWithName(exit, enter, "Einkauf: ${to.name}", 0.72f, geofences, categoryId = "household", activityTypeId = "household")

            else -> genericTravelCandidate(exit, enter, from, to)
        }
    }

    private fun genericTravelCandidate(
        exit: TriggerEvent,
        enter: TriggerEvent,
        from: PlaceGeofence,
        to: PlaceGeofence
    ): ActivityCandidate {
        val title = when {
            from.isGymLike() && to.isHomeLike() -> "Rückfahrt: Fitnessstudio"
            else -> "Unterwegs: ${from.name} → ${to.name}"
        }
        return ActivityCandidate(
            id = stableId("travel", exit.id, enter.id),
            suggestedTitle = title,
            suggestedCategoryId = "transport",
            activityTypeId = "transport",
            startAt = exit.occurredAt,
            endAt = enter.occurredAt,
            confidence = 0.60f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Trigger-Paar erkannt: ${from.name} verlassen → ${to.name} betreten. Als Wegzeit vorgeschlagen.",
            createdBy = AutomationConstants.CREATED_BY_TRIGGER_PAIR_RULES,
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = "${exit.id}:${enter.id}"
        )
    }

    private fun travelWithName(
        exit: TriggerEvent,
        enter: TriggerEvent,
        title: String,
        confidence: Float,
        geofences: Map<String, PlaceGeofence>,
        categoryId: String = "transport",
        activityTypeId: String = "transport"
    ): ActivityCandidate {
        val fromName = geofences[exit.geofenceId]?.name ?: "?"
        val toName = geofences[enter.geofenceId]?.name ?: "?"
        val fromTo = "$fromName → $toName"
        return ActivityCandidate(
            id = stableId("travel", exit.id, enter.id),
            suggestedTitle = title,
            suggestedCategoryId = categoryId,
            activityTypeId = activityTypeId,
            startAt = exit.occurredAt,
            endAt = enter.occurredAt,
            confidence = confidence,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Trigger-Paar erkannt: $fromTo. $title als Wegzeit vorgeschlagen.",
            createdBy = AutomationConstants.CREATED_BY_TRIGGER_PAIR_RULES,
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = "${exit.id}:${enter.id}"
        )
    }

    private fun stayCandidate(enter: TriggerEvent, exit: TriggerEvent, place: PlaceGeofence): ActivityCandidate {
        val (title, confidence) = when {
            place.isWorkLike() -> "Arbeit" to 0.90f
            place.isGymLike() -> "Fitnessstudio" to 0.90f
            place.isHomeLike() -> "Zuhause" to 0.88f
            place.isSupermarketLike() || place.isShopLike() -> "Einkauf: ${place.name}" to 0.80f
            else -> place.name to 0.75f
        }
        return ActivityCandidate(
            id = stableId("stay", enter.id, exit.id),
            suggestedTitle = title,
            suggestedCategoryId = place.categoryId ?: place.categoryFallbackForStay(),
            activityTypeId = place.activityTypeId ?: place.activityTypeFallbackForStay(),
            startAt = enter.occurredAt,
            endAt = exit.occurredAt,
            confidence = confidence,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Trigger-Paar erkannt: ${place.name} betreten → verlassen. Vorschlag bleibt überprüfbar.",
            createdBy = AutomationConstants.CREATED_BY_TRIGGER_PAIR_RULES,
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = "${enter.id}:${exit.id}"
        )
    }

    private fun awayFromHomeCandidate(exit: TriggerEvent, enter: TriggerEvent, home: PlaceGeofence): ActivityCandidate =
        ActivityCandidate(
            id = stableId("away", exit.id, enter.id),
            suggestedTitle = "Ausflug",
            suggestedCategoryId = "leisure",
            activityTypeId = "leisure",
            startAt = exit.occurredAt,
            endAt = enter.occurredAt,
            confidence = 0.62f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Trigger-Paar erkannt: ${home.name} verlassen → wieder angekommen. Kein Ziel bekannt, daher vorsichtig als Ausflug vorgeschlagen.",
            createdBy = AutomationConstants.CREATED_BY_TRIGGER_PAIR_RULES,
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = "${exit.id}:${enter.id}"
        )

    private fun stableId(prefix: String, firstId: String, secondId: String): String = "rule_${prefix}_${firstId}_${secondId}"

    private fun TriggerEvent.transitionKind(): TriggerKind = when {
        type.endsWith("_LEFT") || type.endsWith("_EXIT") || type == AutomationConstants.TRIGGER_CUSTOM_PLACE_LEFT -> TriggerKind.Exit
        type.endsWith("_ARRIVED") || type.endsWith("_ENTERED") || type == AutomationConstants.TRIGGER_CUSTOM_PLACE_ENTERED -> TriggerKind.Enter
        type == AutomationConstants.TRIGGER_GEOFENCE_EXIT -> TriggerKind.Exit
        type == AutomationConstants.TRIGGER_GEOFENCE_ENTER -> TriggerKind.Enter
        else -> TriggerKind.Unknown
    }

    // --- Place type heuristics (M7: extended with shops/supermarkets/transit) ---
    private fun PlaceGeofence.isHomeLike(): Boolean = lowerName().let {
        it.contains("zuhause") || it.contains("home") || it.contains("wohnung")
    }
    private fun PlaceGeofence.isWorkLike(): Boolean = lowerName().let {
        it.contains("arbeit") || it.contains("work") || it.contains("büro") || it.contains("office") ||
        it.contains("rewe") || it.contains("frischezentrum")
    }
    private fun PlaceGeofence.isGymLike(): Boolean = lowerName().let {
        it.contains("fitness") || it.contains("gym") || it.contains("studio") || it.contains("sport")
    }
    private fun PlaceGeofence.isSupermarketLike(): Boolean = lowerName().let {
        it.contains("supermarkt") || it.contains("edeka") || it.contains("aldi") ||
        it.contains("lidl") || it.contains("rewe") || it.contains("netto") ||
        it.contains("kaufland") || it.contains("penny") || it.contains("dm") ||
        it.contains("rossmann")
    }
    private fun PlaceGeofence.isShopLike(): Boolean = lowerName().let {
        it.contains("einkauf") || it.contains("shop") || it.contains("geschäft") ||
        it.contains("markt") || it.contains("store")
    }
    private fun PlaceGeofence.lowerName(): String = name.lowercase(Locale.GERMAN)

    private fun PlaceGeofence.categoryFallbackForStay(): String = when {
        isWorkLike() -> "work"
        isGymLike() -> "sport"
        isHomeLike() -> "household"
        isSupermarketLike() || isShopLike() -> "household"
        else -> "unknown"
    }

    private fun PlaceGeofence.activityTypeFallbackForStay(): String = when {
        isWorkLike() -> "work"
        isGymLike() -> "fitness"
        isHomeLike() -> "household"
        isSupermarketLike() || isShopLike() -> "household"
        else -> "other"
    }

    private enum class TriggerKind { Enter, Exit, Unknown }

    private companion object {
        const val MIN_DURATION_MS = 5 * 60 * 1000L
        const val MAX_DURATION_MS = 14 * 60 * 60 * 1000L
        // M16.7: Wie weit schauen wir für HOME_LEFT zurück, um einen
        // Travel "Zurück nach Hause" als legitim zu akzeptieren? 4 Stunden
        // deckt einen normalen Einkauf/Besuch ab. Wenn in den letzten 4h
        // kein HOME_LEFT-Trigger existiert, ist der Travel ein Phantom
        // (User war nie weg).
        const val HOME_LEFT_LOOKBACK_MS = 4L * 60 * 60 * 1000L
    }
}
