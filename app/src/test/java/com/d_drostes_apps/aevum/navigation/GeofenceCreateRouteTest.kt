package com.d_drostes_apps.aevum.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.URLDecoder

/** M24: Route Orts-Timeline → Geofence-Editor mit Standort. */
class GeofenceCreateRouteTest {

    @Test
    fun `Route enthaelt Koordinaten und kodierten Namen`() {
        val route = geofenceCreateRoute(52.520008, 13.404954, "Gym Mitte")

        assertThat(route).startsWith("geofence/new?")
        assertThat(route).contains("lat=52.520008")
        assertThat(route).contains("lon=13.404954")
        assertThat(route).contains("name=Gym%20Mitte")
        assertThat(route).doesNotContain("+")
    }

    @Test
    fun `leerer Name bleibt leer`() {
        val route = geofenceCreateRoute(48.137154, 11.576124, null)

        assertThat(route).contains("name=")
        val encoded = route.substringAfter("name=")
        assertThat(URLDecoder.decode(encoded, "UTF-8")).isEmpty()
    }

    @Test
    fun `Sonderzeichen im Namen werden dekodierbar kodiert`() {
        val route = geofenceCreateRoute(52.5, 13.4, "Büro & Café")
        val encoded = route.substringAfter("name=")

        assertThat(URLDecoder.decode(encoded, "UTF-8")).isEqualTo("Büro & Café")
    }
}
