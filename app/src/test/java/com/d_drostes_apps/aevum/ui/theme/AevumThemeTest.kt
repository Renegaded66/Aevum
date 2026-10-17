package com.d_drostes_apps.aevum.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.2: Die Identität ist angelötet — Eisblau und Feuerrot.
 *
 * WARUM: Genau diese vier Werte machen den Look wiedererkennbar; wenn ein
 * Refactor die Palette verschiebt, failt der Test lauter, als die Optik
 * es auffallen würde.
 *
 * WIE: Die Schemata sind `internal` (statt privat), damit der Test sie ohne
 * Activity erreicht. Compose.Color ist eine Value-Klasse und funktioniert
 * in der reinen JVM-Suite. Kein Android-Framework nötig.
 */
class AevumThemeTest {

    @Test
    fun darkSchemePinsIceBlueAndFireRed() {
        assertThat(DarkColorScheme.primary).isEqualTo(Color(0xFF7DD3FC))
        assertThat(DarkColorScheme.secondary).isEqualTo(Color(0xFFF87171))
    }

    @Test
    fun lightSchemePinsDeepIceAndFireRed() {
        assertThat(LightColorScheme.primary).isEqualTo(Color(0xFF0369A1))
        assertThat(LightColorScheme.secondary).isEqualTo(Color(0xFFDC2626))
    }
}