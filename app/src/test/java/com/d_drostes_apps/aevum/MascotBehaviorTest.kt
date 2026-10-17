package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.domain.mascot.MascotBehavior
import com.d_drostes_apps.aevum.domain.mascot.MascotReaction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.5: Reine JVM-Suite für die Maskottchen-Regeln (kein Android nötig).
 *
 * Deckt exakt die Devon-Regeln ab:
 *  - „Zwischen 22 und 6 Uhr soll das maskottchen schlafen, außer es wird
 *    etwas aufgezeichnet" (inkl. Grenzen 21/22/6),
 *  - „solange was aufgezeichnet wird schläft es nicht … sobald nicht mehr
 *    schläft es" (isRecording übersteuert),
 *  - „je besser die güte, desto mehr soll das maskottchen lächeln",
 *  - deterministischer Reaktions-Zyklus beim Tippen (M19.6: Sternchen
 *    statt Herzchen).
 */
class MascotBehaviorTest {

    @Test
    fun sleepsAtNightWhileIdle() {
        // Nachtstunden 22, 23, 0…5 → Schlaf (ohne Aufzeichnung).
        listOf(22, 23, 0, 1, 3, 5).forEach { hour ->
            assertThat(MascotBehavior.shouldSleep(hour, isRecording = false)).isTrue()
        }
    }

    @Test
    fun staysAwakeDuringTheDay() {
        // Tagstunden 6…21 → nie Schlaf.
        (6..21).forEach { hour ->
            assertThat(MascotBehavior.shouldSleep(hour, isRecording = false)).isFalse()
        }
    }

    @Test
    fun recordingOverridesNightSleepLive() {
        // 22–6 Uhr MIT Aufzeichnung → wach („wechselnd").
        listOf(22, 23, 0, 5).forEach { hour ->
            assertThat(MascotBehavior.shouldSleep(hour, isRecording = true)).isFalse()
        }
        // Und die Grenzen sauber: 21:59 wach, 6:00 wach.
        assertThat(MascotBehavior.shouldSleep(21, isRecording = false)).isFalse()
        assertThat(MascotBehavior.shouldSleep(6, isRecording = false)).isFalse()
    }

    @Test
    fun hourOfDayMapsMinutesAndClamps() {
        assertThat(MascotBehavior.hourOfDay(0)).isEqualTo(0)
        assertThat(MascotBehavior.hourOfDay(16 * 60)).isEqualTo(16)
        assertThat(MascotBehavior.hourOfDay(23 * 60 + 59)).isEqualTo(23)
        // 1440 (= Tagesende bei Nicht-Heute-Ansichten) wird geklemmt.
        assertThat(MascotBehavior.hourOfDay(1440)).isEqualTo(23)
        assertThat(MascotBehavior.hourOfDay(-5)).isEqualTo(0)
    }

    @Test
    fun smileLevelScalesWithQuality() {
        assertThat(MascotBehavior.smileLevel(0)).isEqualTo(0f)
        assertThat(MascotBehavior.smileLevel(50)).isEqualTo(0.5f)
        assertThat(MascotBehavior.smileLevel(100)).isEqualTo(1f)
        assertThat(MascotBehavior.smileLevel(75)).isEqualTo(0.75f)
        // Randfälle werden geklemmt.
        assertThat(MascotBehavior.smileLevel(-20)).isEqualTo(0f)
        assertThat(MascotBehavior.smileLevel(130)).isEqualTo(1f)
    }

    @Test
    fun reactionForCyclesDeterministically() {
        assertThat(MascotBehavior.reactionFor(0)).isNull()
        assertThat(MascotBehavior.reactionFor(-1)).isNull()
        assertThat(MascotBehavior.reactionFor(1)).isEqualTo(MascotReaction.STAR_HOP)
        assertThat(MascotBehavior.reactionFor(2)).isEqualTo(MascotReaction.WIGGLE_SPARKLES)
        assertThat(MascotBehavior.reactionFor(3)).isEqualTo(MascotReaction.BLUSH_POP)
        // Zyklisch: der 4. Tipp beginnt wieder bei der ersten Reaktion.
        assertThat(MascotBehavior.reactionFor(4)).isEqualTo(MascotReaction.STAR_HOP)
        assertThat(MascotBehavior.reactionFor(7)).isEqualTo(MascotReaction.STAR_HOP)
    }

    @Test
    fun isNightHourMatchesShouldSleepBoundaries() {
        assertThat(MascotBehavior.isNightHour(21)).isFalse()
        assertThat(MascotBehavior.isNightHour(22)).isTrue()
        assertThat(MascotBehavior.isNightHour(23)).isTrue()
        assertThat(MascotBehavior.isNightHour(0)).isTrue()
        assertThat(MascotBehavior.isNightHour(5)).isTrue()
        assertThat(MascotBehavior.isNightHour(6)).isFalse()
        assertThat(MascotBehavior.isNightHour(12)).isFalse()
    }
}
