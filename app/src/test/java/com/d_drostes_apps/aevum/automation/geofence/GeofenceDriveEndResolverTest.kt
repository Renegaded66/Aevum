package com.d_drostes_apps.aevum.automation.geofence

import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.114: Geofence-Re-Enter nach Drive-Ende (Kanban t_d6639d07).
 *
 * Szenario aus dem Bug-Report:
 *   1. User betritt Geofence mit Auto-Start → Geofence-Activity startet.
 *   2. Autofahrt wird erkannt → Drive-Session override'd die Activity.
 *   3. Fahrt endet (Watchdog/Google-EXIT/manueller Stop).
 *   4. Geofence-Activity startet neu, WENN der User noch in der Zone ist;
 *      NICHT, wenn er sie verlassen hat.
 *
 * Der Resolver ist die reine Entscheidung (JVM) — hier sind alle
 * Gate-Bedingungen des Akzeptanzkriterien-Katalogs getestet.
 */
class GeofenceDriveEndResolverTest {

    private fun geofence(
        id: String = "gym",
        name: String = "Gym",
        autoType: String? = "fitness",
        enabled: Boolean = true,
        deleted: Long? = null,
        lat: Double = 51.5136,
        lon: Double = 7.4653,
        radius: Float = 120f
    ) = PlaceGeofence(
        id = id,
        name = name,
        latitude = lat,
        longitude = lon,
        radiusMeters = radius,
        autoStartActivityTypeId = autoType,
        enabled = enabled,
        deletedAt = deleted
    )

    // ── AK 1: User ist noch in der Zone → Restart ──

    @Test
    fun `user noch in der Zone nach Fahrt-Ende - Restart mit Geofence und Type`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence()),
            // Zentrum + ~10 m Offset: klar innerhalb von 120 m.
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L, // 30s alt
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(
            GeofenceDriveEndResolver.Decision.Restart("gym", "fitness")
        )
    }

    // ── AK 2: User hat die Zone verlassen → kein Restart ──

    @Test
    fun `user ausserhalb der Zone - kein Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence()),
            // ~1 km entfernt: klar außerhalb von 120 m.
            fixLat = 51.5136 + 0.009,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    // ── AK 3: Live-Session läuft → kein Restart (Kollisionsschutz) ──

    @Test
    fun `laufende Live-Session blockiert Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence()),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = "driving",
            liveSessionIsLive = true
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    // ── Gate: kein Auto-Start konfiguriert ──

    @Test
    fun `geofence ohne autoStartActivityTypeId - kein Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence(autoType = null)),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    // ── Gate: deaktivierter / gelöschter Geofence ──

    @Test
    fun `deaktivierter Geofence - kein Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence(enabled = false)),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    @Test
    fun `geloeschter Geofence - kein Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence(deleted = 1L)),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    // ── Gate: Stale-Fix / schlechte Genauigkeit / kein Fix ──

    @Test
    fun `zu alter Fix - kein Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence()),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + GeofenceDriveEndResolver.MAX_FIX_AGE_MS + 1_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    @Test
    fun `ungenauer Fix - kein Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence()),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = GeofenceDriveEndResolver.MAX_ACCURACY_M + 1f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    @Test
    fun `kein GPS-Fix - kein Restart`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence()),
            fixLat = null,
            fixLon = null,
            fixAccuracyM = null,
            fixAtMs = 0L,
            nowMs = 1_000_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }

    // ── Mehrere Zonen: der richtige Geofence wird gefunden ──

    @Test
    fun `mehrere Geofences - richtige Zone wird gefunden`() {
        val home = geofence(id = "home", name = "Zuhause", autoType = "home_time", lat = 51.5136, lon = 7.4653)
        val gym = geofence(id = "gym", name = "Gym", autoType = "fitness", lat = 51.5200, lon = 7.4800)
        // Fix am Gym.
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(home, gym),
            fixLat = 51.5201,
            fixLon = 7.4800,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )
        assertThat(decision).isEqualTo(
            GeofenceDriveEndResolver.Decision.Restart("gym", "fitness")
        )
    }

    // ── PAUSED-Session zählt als live (kein Kollisions-Restart) ──

    @Test
    fun `pausierte Session blockiert Restart ebenfalls`() {
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(geofence()),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            liveSessionTypeId = "other",
            liveSessionIsLive = true
        )
        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
    }
}