package com.d_drostes_apps.aevum.domain.mascot

/**
 * M19.5 — Reine Verhaltens-Logik des Maskottchens (testbar ohne Android).
 *
 * Drei Regeln aus dem Devon-Auftrag:
 *  1. „Zwischen 22 und 6 Uhr soll das maskottchen schlafen, außer es wird
 *     etwas aufgezeichnet. […] solange was aufgezeichnet wird schläft es
 *     nicht, und sobald nicht mehr schläft es, aber falls doch wieder was
 *     aufgezeichnet wird schläft es nicht."
 *  2. „je besser die güte, desto mehr soll das maskottchen lächeln" —
 *     Lächel-Level 0..1 ist die (geklemmte) Güte.
 *  3. Tippen löst abwechselnde, niedliche Reaktionen aus (deterministischer
 *     Zyklus — besser testbar und fühlt sich trotzdem lebendig an).
 */
object MascotBehavior {

    /** Ab 22:00 Uhr … */
    const val NIGHT_START_HOUR = 22

    /** … bis 06:00 Uhr morgens. */
    const val NIGHT_END_HOUR = 6

    /**
     * Nachtschlaf-Zustand: zwischen 22 und 6 Uhr schlafen — es sei denn,
     * es läuft gerade eine Aufzeichnung (Running/Paused-Session).
     */
    fun shouldSleep(hourOfDay: Int, isRecording: Boolean): Boolean =
        isNightHour(hourOfDay) && !isRecording

    fun isNightHour(hourOfDay: Int): Boolean =
        hourOfDay >= NIGHT_START_HOUR || hourOfDay < NIGHT_END_HOUR

    /** Minuten seit Mitternacht → Stunde des Tages (0..23, geklemmt). */
    fun hourOfDay(minuteOfDay: Int): Int =
        (minuteOfDay / 60).coerceIn(0, 23)

    /** Güte (0..100) → Lächel-Level (0..1), geklemmt. */
    fun smileLevel(score: Int): Float =
        score.coerceIn(0, 100) / 100f

    /**
     * 1. Tipp → Reaktion 1, 2. Tipp → Reaktion 2, … zyklisch.
     * tapCount ≤ 0 → null (noch nie getippt = Idle).
     */
    fun reactionFor(tapCount: Int): MascotReaction? {
        if (tapCount <= 0) return null
        val idx = ((tapCount - 1) % MascotReaction.entries.size +
            MascotReaction.entries.size) % MascotReaction.entries.size
        return MascotReaction.entries[idx]
    }
}

/** Die drei niedlichen Tipp-Reaktionen (bewusst klein gehalten). */
enum class MascotReaction {
    /** Hüpfer + schwebende Herzchen. */
    BOUNCE_HEARTS,

    /** Wackel-Tanz + goldene Funkelsterne. */
    WIGGLE_SPARKLES,

    /** Bäckchen-Pop: glühende Wangen + Freuden-Augen + Mini-Pop. */
    BLUSH_POP
}
