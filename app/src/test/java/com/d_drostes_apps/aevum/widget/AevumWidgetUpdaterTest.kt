package com.d_drostes_apps.aevum.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** M23: Timer-Format des Homescreen-Widgets. */
class AevumWidgetUpdaterTest {

    @Test
    fun `unter einer Stunde MM-SS`() {
        assertThat(AevumWidgetUpdater.formatTimer(0L)).isEqualTo("00:00")
        assertThat(AevumWidgetUpdater.formatTimer(65_000L)).isEqualTo("01:05")
        assertThat(AevumWidgetUpdater.formatTimer(59L * 60_000L + 59_000L)).isEqualTo("59:59")
    }

    @Test
    fun `ab einer Stunde H-MM-SS`() {
        assertThat(AevumWidgetUpdater.formatTimer(3_600_000L)).isEqualTo("1:00:00")
        assertThat(AevumWidgetUpdater.formatTimer(3_723_000L)).isEqualTo("1:02:03")
        assertThat(AevumWidgetUpdater.formatTimer(25L * 3_600_000L)).isEqualTo("25:00:00")
    }

    @Test
    fun `negative Dauer wird als null behandelt`() {
        assertThat(AevumWidgetUpdater.formatTimer(-5_000L)).isEqualTo("00:00")
    }
}
