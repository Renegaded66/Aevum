package de.devondroste.aevum.automation.sleep

import java.time.Instant
import java.time.ZoneId

/**
 * M16.3: Wake-Time-Priorisierung für die Schlaf-Heuristik.
 *
 * Problem (vor M16.3):
 *   Der Lifecycle-Fallback (ActivityLifecycleCallbacks → "ON") schreibt
 *   ein App-Vordergrund-Event in dieselbe Screen-Event-Liste wie echte
 *   SCREEN_ON/USER_PRESENT-Broadcasts. Beim OFF→ON-Pairing wurde dann
 *   der App-Öffnen-Zeitpunkt (z.B. 08:35) als Aufwachzeit gewertet,
 *   obwohl der User schon um 08:20 sein Phone benutzt hatte.
 *
 * Lösung:
 *   1. ScreenEvents tragen jetzt einen `source`-Marker
 *      ("BROADCAST" / "LIFECYCLE" / "USAGE_STATS").
 *   2. Beim Pairing wird die **früheste BROADCAST-ON/UNLOCK** im
 *      Morgen-Fenster als Wake-Time bevorzugt.
 *   3. LIFECYCLE-Events sind nur ein Fallback, wenn gar kein
 *      BROADCAST-Event im Fenster liegt.
 *
 * Prioritäts-Reihenfolge (höchste zuerst):
 *   1. UNLOCK  (USER_PRESENT — User hat das Phone entsperrt)
 *   2. ON mit source = BROADCAST (echter SCREEN_ON Broadcast)
 *   3. ON mit source = USAGE_STATS (zukünftige Erweiterung)
 *   4. ON mit source = LIFECYCLE (App in den Vordergrund — nur Fallback)
 *
 * Public top-level-Funktion statt Methode auf einer Klasse, damit
 * beide Engines (SleepHeuristicEngine + SleepFusionEngine) sie ohne
 * gemeinsame Basisklasse nutzen können.
 */

/**
 * Paart OFF- mit ON/UNLOCK-Events für die Schlaf-Erkennung.
 *
 * Sucht alle OFF→ON-Paare, bei denen das OFF innerhalb des
 * Schlaf-Fensters liegt (20:00–02:00 nächster Tag) und das ON
 * innerhalb des Morgen-Fensters (04:00–12:00). Unter allen
 * gültigen Paaren wird das mit der längsten Dauer gewählt,
 * aber bei der Wake-Time wird die **höchste verfügbare Priorität**
 * genommen statt nur das früheste ON.
 *
 * @return Triple<Long, Long, Long>? → (sleepStartMs, wakeTimeMs, durationMs)
 *         oder null wenn kein gültiges Paar gefunden wurde.
 */
fun selectSleepWindowWithPrioritizedWake(
    events: List<ScreenEvent>,
    zoneId: ZoneId
): Triple<Long, Long, Long>? {
    if (events.size < 2) return null

    // M16.2: Für jedes ON das zuletzt davor liegende OFF nehmen.
    // Siehe SleepHeuristicEngine für die Begründung: das erste OFF
    // vor einem ON kann zu früh liegen, wenn ein kurzes ON-Event
    // durch OEM-Suppression nicht erfasst wurde.
    //
    // M16.7: WICHTIG — wir setzen `lastOff = null` ERST, wenn das Pair die
    // Morgen-Filter (onInMorningWindow + offInSleepWindow) überlebt. Sonst
    // verbraucht ein nächtlicher Weckruf das OFF des Vorabends, und das
    // morgendliche ON hat kein Pair → kein Schlaf erkannt.
    val offOnPairs = mutableListOf<Pair<ScreenEvent, ScreenEvent>>()
    var lastOff: ScreenEvent? = null
    for (event in events.sortedBy { it.timestamp }) {
        if (event.type == "OFF") {
            lastOff = event
        } else if (event.type == "ON" || event.type == "UNLOCK") {
            val currentOff = lastOff
            if (currentOff != null) {
                val offHour = Instant.ofEpochMilli(currentOff.timestamp).atZone(zoneId).hour
                val onHour = Instant.ofEpochMilli(event.timestamp).atZone(zoneId).hour
                val offInSleepWindow = offHour >= 20 || offHour < 2
                val onInMorningWindow = onHour in 4..11
                if (offInSleepWindow && onInMorningWindow) {
                    offOnPairs.add(currentOff to event)
                    lastOff = null
                }
                // Sonst: lastOff bleibt für nachfolgende ON-Events.
            }
        }
    }
    if (offOnPairs.isEmpty()) return null

    // Filtere auf gültige Schlaf-Paare und wähle das längste.
    val validPairs = offOnPairs.mapNotNull { (off, on) ->
        val offHour = Instant.ofEpochMilli(off.timestamp).atZone(zoneId).hour
        val onHour = Instant.ofEpochMilli(on.timestamp).atZone(zoneId).hour
        val offInSleepWindow = offHour >= 20 || offHour < 2
        val onInMorningWindow = onHour in 4..11
        if (!offInSleepWindow || !onInMorningWindow) return@mapNotNull null

        val durationMs = on.timestamp - off.timestamp
        val hours = durationMs / 3_600_000.0
        if (hours < 3.0 || hours > 14.0) return@mapNotNull null

        Triple(off.timestamp, on.timestamp, durationMs)
    }
    if (validPairs.isEmpty()) return null

    // Längste gültige Periode = Hauptschlaf-Phase.
    val (sleepStartMs, rawWakeMs, _) = validPairs.maxBy { it.third }

    // Wake-Time-Korrektur: suche zwischen (sleepStartMs, rawWakeMs + 30min)
    // das früheste Event mit höchster Priorität.
    val wakeWindowEnd = rawWakeMs + 30L * 60 * 1000  // 30min nach Pairing-ON
    val wakeCandidates = events
        .filter {
            val isOnLike = it.type == "ON" || it.type == "UNLOCK"
            isOnLike && it.timestamp in sleepStartMs..wakeWindowEnd
        }

    val prioritizedWake = prioritizeWakeTime(wakeCandidates)
    val finalWakeMs = prioritizedWake ?: rawWakeMs

    return Triple(sleepStartMs, finalWakeMs, finalWakeMs - sleepStartMs)
}

/**
 * Bestimmt aus einer Liste von möglichen Aufwach-Events den
 * semantisch korrektesten Zeitpunkt.
 *
 * Priorität (höchste zuerst):
 *   1. UNLOCK (USER_PRESENT) — der User hat das Phone entsperrt.
 *   2. ON + source = BROADCAST — echter SCREEN_ON Broadcast.
 *   3. ON + source = USAGE_STATS — UsageStats-Event (zukünftig).
 *   4. ON + source = LIFECYCLE — App in den Vordergrund.
 *
 * Innerhalb derselben Prioritätsstufe zählt der **früheste** Zeitstempel.
 * Gibt null zurück, wenn die Liste leer ist.
 */
fun prioritizeWakeTime(events: List<ScreenEvent>): Long? {
    if (events.isEmpty()) return null

    // Sortiere nach (Priorität, Zeitstempel). Priorität 1 = höchste.
    return events.minWithOrNull(
        compareBy({ wakeEventPriority(it) }, { it.timestamp })
    )?.timestamp
}

/**
 * Niedrigerer Wert = höhere Priorität.
 *   0 = UNLOCK
 *   1 = ON + BROADCAST
 *   2 = ON + USAGE_STATS
 *   3 = ON + LIFECYCLE
 *   99 = unbekannt (Fallback)
 */
private fun wakeEventPriority(event: ScreenEvent): Int = when {
    event.type == "UNLOCK" -> 0
    event.type == "ON" && event.source == "BROADCAST" -> 1
    event.type == "ON" && event.source == "USAGE_STATS" -> 2
    event.type == "ON" && event.source == "LIFECYCLE" -> 3
    else -> 99
}