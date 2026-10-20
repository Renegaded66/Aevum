package com.d_drostes_apps.aevum.ui.screens.automation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M24: Vorbefüllung des Geofence-Editors aus der Orts-Timeline.
 * Reine Funktion — keine Android-/DB-Abhängigkeit.
 */
class GeofencePrefillTest {

    @Test
    fun `gueltige Koordinaten fuellen lat lon und Radius`() {
        val form = geofencePrefillFromArgs("52.520008", "13.404954", "Bibliothek")

        assertThat(form).isNotNull()
        assertThat(form!!.latitude).isEqualTo("52.520008")
        assertThat(form.longitude).isEqualTo("13.404954")
        assertThat(form.name).isEqualTo("Bibliothek")
        assertThat(form.radius).isEqualTo("150")
        assertThat(form.id).isNull()
    }

    @Test
    fun `unbenannter Ort laesst den Namen leer`() {
        val form = geofencePrefillFromArgs("48.137154", "11.576124", null)

        assertThat(form).isNotNull()
        assertThat(form!!.name).isEmpty()
        assertThat(form.latitude).isEqualTo("48.137154")
    }

    @Test
    fun `Komma-Dezimaltrenner wird akzeptiert`() {
        val form = geofencePrefillFromArgs("52,52", "13,405", "Gym")

        assertThat(form).isNotNull()
        assertThat(form!!.latitude).isEqualTo("52.520000")
        assertThat(form.longitude).isEqualTo("13.405000")
    }

    @Test
    fun `fehlende oder unsinnige Koordinaten werden abgelehnt`() {
        assertThat(geofencePrefillFromArgs(null, "13.4", "X")).isNull()
        assertThat(geofencePrefillFromArgs("52.5", null, "X")).isNull()
        assertThat(geofencePrefillFromArgs("abc", "13.4", "X")).isNull()
        assertThat(geofencePrefillFromArgs("91.0", "13.4", "X")).isNull()
        assertThat(geofencePrefillFromArgs("52.5", "181.0", "X")).isNull()
        // Null-Insel = kein echter Standort.
        assertThat(geofencePrefillFromArgs("0", "0", "X")).isNull()
    }

    @Test
    fun `Name wird getrimmt und auf 80 Zeichen begrenzt`() {
        val form = geofencePrefillFromArgs("52.5", "13.4", "  " + "A".repeat(120) + "  ")

        assertThat(form).isNotNull()
        assertThat(form!!.name).hasLength(80)
        assertThat(form.name.first()).isEqualTo('A')
    }
}
