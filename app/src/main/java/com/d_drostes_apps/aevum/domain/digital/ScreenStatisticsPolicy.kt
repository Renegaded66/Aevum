package com.d_drostes_apps.aevum.domain.digital

import com.d_drostes_apps.aevum.data.model.ActivitySession
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.138: Bildschirm-Aufzeichnung vs. Bildschirmzeit — Trennung der Quellen.
 *
 * User-Spec (2026-10-01):
 *  - „Es scheint, als würden Aufzeichnungen nicht gestoppt werden, wenn ich
 *    das Handy weglege […] Das ist falsch."
 *  - „Im Dashboard […] soll die sein, die auch bei Digital Balance steht."
 *  - „Die Einstellung [Vorlaufzeit] bezieht sich aber nur auf die Timeline."
 *  - „Im Dashboard und generell was die Statistik angeht, soll nur die
 *    Digital-Balance-Zeit zählen, nicht die Zeit, die sich aus den
 *    Aufzeichnungen ergibt."
 *
 * Daraus folgen ZWEI getrennte Rollen:
 *
 *  1. **Aufzeichnung** (`sourceType = SCREEN_AUTO`, Typ „Digital"):
 *     erzeugt sichtbare Blöcke in der TIMELINE. Sie ist eine DARSTELLUNG
 *     der Nutzung, keine Messung — sie startet erst nach x Minuten
 *     aktiver Nutzung (damit keine 10-Sekunden-Fragmente entstehen) und
 *     ist dadurch zwangsläufig ungenau.
 *
 *  2. **Bildschirmzeit** (UsageStatsManager, sichtbar im Digital-Balance-Tab):
 *     die echte, sekundengenaue Messung. NUR sie fließt in Dashboard,
 *     Insights, Weekly Review, Todos, Ziele und Life-View ein.
 *
 * Aufzeichnungs-Sessions werden deshalb aus JEDER Statistik entfernt
 * ([forStatistics]) und durch den Digital-Balance-Wert ersetzt
 * ([digitalBalanceSessions]). In der Timeline bleiben sie unverändert
 * sichtbar — dort wird [forStatistics] bewusst NICHT angewandt.
 *
 * Bewusst als pure Funktionen ohne Android-Abhängigkeit — unit-testbar.
 */
object ScreenStatisticsPolicy {

    /** sourceType der automatischen Bildschirm-Aufzeichnung (Timeline-Blöcke). */
    const val SOURCE_SCREEN_RECORDING = "SCREEN_AUTO"

    /**
     * sourceType der künstlichen Digital-Balance-Sessions, die die
     * Statistik mit der echten Bildschirmzeit versorgen. Diese Sessions
     * existieren NUR im Speicher — sie werden nie persistiert.
     */
    const val SOURCE_DIGITAL_BALANCE = "DIGITAL_BALANCE"

    /** activityTypeId der Aktivität „Digital". */
    const val DIGITAL_TYPE_ID = "digital"

    /** categoryId der Kategorie „Digital". */
    const val DIGITAL_CATEGORY_ID = "digital"

    /** Anzeigename der Balance-Sessions (wird in Statistiken gezeigt). */
    const val DIGITAL_TITLE = "Digital"

    /**
     * Ist diese Session eine Bildschirm-Aufzeichnung (Timeline-Block)?
     * Solche Sessions zählen in keiner Statistik.
     */
    fun isScreenRecording(sourceType: String?): Boolean =
        sourceType == SOURCE_SCREEN_RECORDING

    /** Ist diese Session ein künstlicher Digital-Balance-Beitrag? */
    fun isDigitalBalance(sourceType: String?): Boolean =
        sourceType == SOURCE_DIGITAL_BALANCE

    /**
     * Zählt diese Session in die Statistik (Dashboard, Insights, Weekly,
     * Heatmap, Todos, Ziele, Life-View)?
     */
    fun countsInStatistics(sourceType: String?): Boolean =
        !isScreenRecording(sourceType)

    /**
     * Sessions für statistische Auswertungen: Bildschirm-Aufzeichnungen
     * sind entfernt, alles andere bleibt unverändert.
     *
     * Die Aufzeichnung erscheint weiterhin in der Timeline — dort wird
     * diese Funktion bewusst NICHT angewandt.
     */
    fun forStatistics(sessions: List<ActivitySession>): List<ActivitySession> =
        sessions.filter { countsInStatistics(it.sourceType) }

    /**
     * M18.138: Bildschirmzeit je Tag auf 24 h begrenzen.
     *
     * Die Phase-Summe kann in Randfällen über einen Tag hinauslaufen (zwei
     * Apps gleichzeitig im Vordergrund, z. B. Split-Screen oder ein
     * verspätetes MOVE_TO_BACKGROUND). Für die Statistik ist alles über
     * einem Tag physikalisch unmöglich und würde die Tagesdauer sprengen.
     */
    private const val MAX_MS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * Bildschirmzeit-Beiträge für die Statistik: pro Tag EINE künstliche
     * „Digital"-Session mit der echten Bildschirmzeit dieses Tages.
     *
     * Die Session liegt innerhalb ihres Tages (Start = Tagesbeginn), damit
     * jede Tages-Aggregation (Heatmap, Tagesdurchschnitte) den Betrag
     * korrekt dem richtigen Tag zuordnet. Die Uhrzeit ist bedeutungslos:
     * die Session wird nur für Summen/Dauern ausgewertet, nie gerendert.
     *
     * WICHTIG: Das Ergebnis wird NIE persistiert. Es darf ausschließlich an
     * Statistik-Funktionen übergeben werden — niemals an die Timeline, den
     * Tagesfluss oder den Kalender (dort würde ein Block um 00:00 entstehen).
     *
     * @param balanceMsPerDay Bildschirmzeit je Tag (UsageStats). Tage mit
     *        0 oder negativem Wert erzeugen keine Session.
     */
    fun digitalBalanceSessions(
        balanceMsPerDay: Map<LocalDate, Long>,
        zoneId: ZoneId
    ): List<ActivitySession> = balanceMsPerDay
        .mapValues { (_, ms) -> ms.coerceIn(0L, MAX_MS_PER_DAY) }
        .filterValues { it > 0L }
        .map { (date, ms) ->
            val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            ActivitySession(
                id = "digital_balance_$date",
                title = DIGITAL_TITLE,
                categoryId = DIGITAL_CATEGORY_ID,
                activityTypeId = DIGITAL_TYPE_ID,
                startAt = dayStart,
                endAt = dayStart + ms,
                timezoneId = zoneId.id,
                sourceType = SOURCE_DIGITAL_BALANCE,
                createdBy = SOURCE_DIGITAL_BALANCE,
                sessionStatus = "FINISHED"
            )
        }

    /**
     * Einzelner Balance-Beitrag für genau einen Tag. Bequemlichkeit für
     * Sichten, die nur einen Tag betrachten (Dashboard, Todos).
     */
    fun digitalBalanceSessionForDay(
        date: LocalDate,
        balanceMs: Long,
        zoneId: ZoneId
    ): List<ActivitySession> = digitalBalanceSessions(mapOf(date to balanceMs), zoneId)
}
